package com.sos.nearby

import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import android.os.SystemClock
import java.nio.ByteBuffer
import java.util.UUID

/** Presence advertisements contain no SOS. Requests are written only to budgeted recipients. */
@SuppressLint("MissingPermission")
internal class BleRelay(private val context: Context, private val received: (SosMessage) -> Unit,
                        private val status: (String) -> Unit) {
    // New service separates directed budgeted delivery from the old unrestricted pull protocol.
    private val serviceId = UUID.fromString("0000fef0-6c7a-4de1-b7b0-51a8b77d3054")
    private val valueId = UUID.fromString("51db9970-95df-42d7-a6cf-3d825ac68121")
    private val identityId = UUID.fromString("51db9970-95df-42d7-a6cf-3d825ac68122")
    private val handler = Handler(Looper.getMainLooper())
    private val adapter get() = context.getSystemService(BluetoothManager::class.java)?.adapter
    private var server: BluetoothGattServer? = null
    private var client: BluetoothGatt? = null
    private var active = false
    val isRunning get() = active
    private var nodeBytes = byteArrayOf()
    private var nodeId = ""
    private data class Peer(val device: BluetoothDevice, val identity: ByteArray)
    private data class Job(val message: SosMessage, val readyAt: Long, var plan: RecipientPlan? = null)
    private data class Transfer(val job: Job, val id: String, val peer: Peer, val bytes: ByteArray)
    private var nodes = NearbyNodes()
    private val peers = mutableMapOf<String, Peer>()
    private val jobs = linkedMapOf<UUID, Job>()
    private var transfer: Transfer? = null
    private var cursor = 0
    private var serviceReady = false
    private var generation = 0L
    private val inbound = mutableMapOf<String, Long>()
    private var inboundCounter = 0L
    private fun report(id: Int) = status(context.getString(id))
    private val advertiserCallback = object : AdvertiseCallback() {
        override fun onStartFailure(errorCode: Int) { handler.post { if (active) report(R.string.bluetooth_failed) } }
    }
    private fun serverCallback(epoch: Long) = object : BluetoothGattServerCallback() {
        override fun onConnectionStateChange(device: BluetoothDevice, code: Int, state: Int) { handler.post {
            if (!active || generation != epoch) return@post
            val id = try { device.address } catch (_: SecurityException) { return@post }
            if (state == BluetoothProfile.STATE_CONNECTED) {
                val ticket = ++inboundCounter
                inbound[id] = ticket
                handler.postDelayed({
                    if (active && generation == epoch && inbound[id] == ticket) {
                        inbound.remove(id)
                        try { server?.cancelConnection(device) } catch (_: RuntimeException) { }
                    }
                }, 8_000)
            } else if (state == BluetoothProfile.STATE_DISCONNECTED) inbound.remove(id)
        } }
        override fun onServiceAdded(code: Int, service: BluetoothGattService) { handler.post {
            if (!active || generation != epoch) return@post
            if (code != BluetoothGatt.GATT_SUCCESS) { stop(); report(R.string.relay_service_failed); return@post }
            serviceReady = true
            try {
                // 128-bit service-data header + eight-byte node ID + flags fits legacy 31-byte BLE adverts.
                adapter?.bluetoothLeAdvertiser?.startAdvertising(AdvertiseSettings.Builder().setConnectable(true)
                    .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_BALANCED)
                    .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH).build(),
                    AdvertiseData.Builder().addServiceData(ParcelUuid(serviceId), nodeBytes).build(), advertiserCallback)
                    ?: report(R.string.receive_only)
            } catch (_: RuntimeException) { report(R.string.bluetooth_failed) }
        } }
        override fun onCharacteristicReadRequest(device: BluetoothDevice, requestId: Int, offset: Int, characteristic: BluetoothGattCharacteristic) { handler.post {
            if (!active || generation != epoch) return@post
            val valid = characteristic.uuid == identityId && offset in 0..nodeBytes.size
            try { server?.sendResponse(device, requestId,
                if (valid) BluetoothGatt.GATT_SUCCESS else BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED,
                offset, if (valid) nodeBytes.copyOfRange(offset, nodeBytes.size) else null)
            } catch (_: RuntimeException) { report(R.string.bluetooth_permission_revoked) }
        } }
        override fun onCharacteristicWriteRequest(device: BluetoothDevice, requestId: Int, characteristic: BluetoothGattCharacteristic,
            preparedWrite: Boolean, responseNeeded: Boolean, offset: Int, bytes: ByteArray) { handler.post {
            if (!active || generation != epoch) return@post
            val message = if (!preparedWrite && offset == 0 && characteristic.uuid == valueId)
                SosMessage.decode(bytes, System.currentTimeMillis()) else null
            try {
                if (responseNeeded) server?.sendResponse(device, requestId,
                    if (message != null) BluetoothGatt.GATT_SUCCESS else BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED, offset, null)
            } catch (_: RuntimeException) { report(R.string.bluetooth_permission_revoked) }
            if (message != null) received(message)
        } }
    }
    private val scan = object : ScanCallback() {
        override fun onScanFailed(errorCode: Int) { handler.post { if (active) report(R.string.listening_failed) } }
        override fun onScanResult(callbackType: Int, result: ScanResult) { handler.post {
            if (!active) return@post
            val identity = result.scanRecord?.getServiceData(ParcelUuid(serviceId)) ?: return@post
            if (identity.size != 8 || identity.contentEquals(nodeBytes)) return@post
            val id = ByteBuffer.wrap(identity).long.toString()
            val now = SystemClock.elapsedRealtime()
            nodes.observe(id, result.rssi, now)
            peers.keys.retainAll(nodes.ids(now))
            if (id in nodes.ids(now)) peers[id] = Peer(result.device, identity.copyOf())
        } }
    }
    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, code: Int, state: Int) { handler.post {
            if (!active || client !== gatt) { gatt.close(); return@post }
            try {
                if (code == BluetoothGatt.GATT_SUCCESS && state == BluetoothProfile.STATE_CONNECTED) {
                    if (!gatt.requestMtu(185)) finish(gatt, false)
                } else finish(gatt, false)
            } catch (_: RuntimeException) { finish(gatt, false) }
        } }
        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, code: Int) { handler.post {
            if (client !== gatt || !active) return@post
            try {
                if (code != BluetoothGatt.GATT_SUCCESS || mtu < (transfer?.bytes?.size ?: 512) + 3 || !gatt.discoverServices()) finish(gatt, false)
            } catch (_: RuntimeException) { finish(gatt, false) }
        } }
        override fun onServicesDiscovered(gatt: BluetoothGatt, code: Int) { handler.post {
            if (client !== gatt || !active) return@post
            try {
                val identity = gatt.getService(serviceId)?.getCharacteristic(identityId)
                if (code != BluetoothGatt.GATT_SUCCESS || identity == null || !gatt.readCharacteristic(identity)) finish(gatt, false)
            } catch (_: RuntimeException) { finish(gatt, false) }
        } }
        @Deprecated("Compatibility with Android 8–12")
        override fun onCharacteristicRead(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, code: Int) {
            readIdentity(gatt, characteristic.uuid, characteristic.value ?: byteArrayOf(), code)
        }
        override fun onCharacteristicRead(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, bytes: ByteArray, code: Int) {
            readIdentity(gatt, characteristic.uuid, bytes, code)
        }
        override fun onCharacteristicWrite(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, code: Int) { handler.post {
            if (client === gatt && characteristic.uuid == valueId) finish(gatt, code == BluetoothGatt.GATT_SUCCESS)
        } }
    }
    private fun readIdentity(gatt: BluetoothGatt, uuid: UUID, bytes: ByteArray, code: Int) { handler.post {
        if (client !== gatt || !active) return@post
        val current = transfer ?: return@post
        if (code != BluetoothGatt.GATT_SUCCESS || uuid != identityId || !bytes.contentEquals(current.peer.identity) ||
            current.job.message.expiresAt <= System.currentTimeMillis()) { finish(gatt, false); return@post }
        try {
            val characteristic = gatt.getService(serviceId)?.getCharacteristic(valueId)
            if (characteristic == null) { finish(gatt, false); return@post }
            val started = if (Build.VERSION.SDK_INT >= 33)
                gatt.writeCharacteristic(characteristic, current.bytes, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) == BluetoothStatusCodes.SUCCESS
            else {
                characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                characteristic.value = current.bytes
                gatt.writeCharacteristic(characteristic)
            }
            if (!started) finish(gatt, false)
        } catch (_: RuntimeException) { finish(gatt, false) }
    } }
    private fun finish(gatt: BluetoothGatt, delivered: Boolean) {
        if (client === gatt) {
            if (delivered) transfer?.let { it.job.plan?.delivered(it.id) }
            client = null; transfer = null
        }
        try { gatt.disconnect(); gatt.close() } catch (_: RuntimeException) { }
    }
    fun start() {
        stop()
        try {
            if (adapter?.isEnabled != true) { report(R.string.enable_bluetooth); return }
            val preferences = context.getSharedPreferences("node_identity", Context.MODE_PRIVATE)
            val identity = if (preferences.contains("id")) preferences.getLong("id", 0) else UUID.randomUUID().mostSignificantBits.also {
                if (!preferences.edit().putLong("id", it).commit()) { report(R.string.storage_failed); return }
            }
            nodeId = identity.toString(); nodeBytes = ByteBuffer.allocate(8).putLong(identity).array()
            active = true
            server = context.getSystemService(BluetoothManager::class.java).openGattServer(context, serverCallback(generation))
            val service = BluetoothGattService(serviceId, BluetoothGattService.SERVICE_TYPE_PRIMARY)
            service.addCharacteristic(BluetoothGattCharacteristic(identityId, BluetoothGattCharacteristic.PROPERTY_READ, BluetoothGattCharacteristic.PERMISSION_READ))
            service.addCharacteristic(BluetoothGattCharacteristic(valueId, BluetoothGattCharacteristic.PROPERTY_WRITE, BluetoothGattCharacteristic.PERMISSION_WRITE))
            if (server?.addService(service) != true) { stop(); report(R.string.relay_service_failed); return }
            val scanner = adapter?.bluetoothLeScanner ?: run { stop(); report(R.string.scanner_unavailable); return }
            scanner.startScan(listOf(ScanFilter.Builder().setServiceData(ParcelUuid(serviceId), null).build()),
                ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_BALANCED).build(), scan)
            report(R.string.listening)
            handler.post(pump)
        } catch (_: RuntimeException) { stop(); report(R.string.bluetooth_unavailable) }
    }
    fun publish(message: SosMessage): Boolean {
        if (!active) { report(R.string.connect_first); return false }
        jobs.entries.removeAll { it.value.message.expiresAt <= System.currentTimeMillis() }
        if (jobs.size >= 8 || jobs.containsKey(message.id)) { report(R.string.queue_full); return false }
        if (message.remainingNodes !in 1..29 || message.expiresAt <= System.currentTimeMillis()) return false
        jobs[message.id] = Job(message, SystemClock.elapsedRealtime() + 5_000)
        return true
    }
    fun remove(id: UUID) {
        jobs.remove(id)
        if (transfer?.job?.message?.id == id) client?.let { finish(it, false) }
    }
    private val pump = object : Runnable { override fun run() {
        if (!active) return
        try { sendNext() } catch (_: RuntimeException) {
            client?.let { finish(it, false) }; report(R.string.relay_interrupted)
        }
        handler.postDelayed(this, 300)
    } }
    private fun sendNext() {
        val wallNow = System.currentTimeMillis()
        jobs.entries.removeAll { it.value.message.expiresAt <= wallNow }
        if (client != null || !serviceReady) return
        val now = SystemClock.elapsedRealtime()
        val list = jobs.values.toList()
        for (step in list.indices) {
            val job = list[(cursor + step) % list.size]
            if (now < job.readyAt) continue
            // Freeze the IDs AND branch grants once. Never redistribute a possibly delivered grant.
            if (job.plan == null) {
                val candidates = nodes.closest(now, job.message.remainingNodes).filter { it != nodeId }
                if (candidates.isEmpty()) continue
                job.plan = RecipientPlan(candidates, job.message.remainingNodes)
            }
            val plan = job.plan ?: continue
            val id = plan.next() ?: continue
            plan.attempted(id)
            val peer = peers[id] ?: continue
            val packet = job.message.copy(remainingNodes = plan.grants.getValue(id)).encode()
            transfer = Transfer(job, id, peer, packet)
            cursor = (cursor + step + 1) % list.size
            client = peer.device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
            val current = client
            if (current == null) transfer = null
            else handler.postDelayed({ if (client === current) finish(current, false) }, 5_000)
            return
        }
    }
    fun stop() {
        active = false; serviceReady = false; generation++; inbound.clear()
        handler.removeCallbacksAndMessages(null)
        client?.let { finish(it, false) }
        try { adapter?.bluetoothLeScanner?.stopScan(scan) } catch (_: RuntimeException) { }
        try { adapter?.bluetoothLeAdvertiser?.stopAdvertising(advertiserCallback) } catch (_: RuntimeException) { }
        try { server?.close() } catch (_: RuntimeException) { }
        server = null; jobs.clear(); peers.clear(); nodes = NearbyNodes(); transfer = null; cursor = 0
    }
}
