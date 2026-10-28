package com.sos.nearby

import android.Manifest
import android.app.*
import android.bluetooth.BluetoothAdapter
import android.content.*
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.*
import android.util.Base64
import java.util.UUID

internal data class RelayState(val foreground: Boolean, val radioRunning: Boolean, val status: String,
    val speechStatus: String, val own: SosMessage?, val messages: List<SosMessage>)

/** Owns radio, deduplication, announcements and deadlines independently of the activity. */
class SosService : Service() {
    companion object {
        const val ACTION_START = "com.sos.nearby.START_LISTENING"
        const val ACTION_STOP = "com.sos.nearby.STOP_LISTENING"
        const val ONGOING_CHANNEL = "nearby_listening"
        const val ALERT_CHANNEL = "nearby_requests"
        private const val ONGOING_ID = 1
        private const val ALERT_ID = 2
        fun bluetoothPermissions(): Array<String> = if (Build.VERSION.SDK_INT >= 31) arrayOf(
            Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_ADVERTISE, Manifest.permission.BLUETOOTH_CONNECT
        ) else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        fun foregroundPermissionsGranted(context: Context) = bluetoothPermissions().all {
            context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED
        }
        fun backgroundPermissionGranted(context: Context) = (Build.VERSION.SDK_INT < 29 || Build.VERSION.SDK_INT > 30) ||
            context.checkSelfPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED
    }
    inner class LocalBinder : Binder() { val service get() = this@SosService }
    private val binder = LocalBinder()
    private val handler = Handler(Looper.getMainLooper())
    private val listeners = mutableSetOf<(RelayState) -> Unit>()
    private lateinit var radio: BleRelay
    private lateinit var speaker: PersianSpeaker
    private lateinit var wakeLock: PowerManager.WakeLock
    private val profile get() = getSharedPreferences("profile", MODE_PRIVATE)
    private val notifications get() = getSystemService(NotificationManager::class.java)
    private var foreground = false
    private var receiverRegistered = false
    private var connectionStatus = ""
    private var speechStatus = ""
    private var own: SosMessage? = null
    private val displayed = linkedMapOf<UUID, SosMessage>()
    private val inbox by lazy { RelayInbox(::claimRequest) }
    private val preferencesChanged = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == "speechEnabled") speaker.setEnabled(profile.getBoolean("speechEnabled", true))
    }
    private val bluetoothChanged = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (!foreground || intent.action != BluetoothAdapter.ACTION_STATE_CHANGED) return
            when (intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)) {
                BluetoothAdapter.STATE_ON -> if (foregroundPermissionsGranted(this@SosService)) radio.start()
                BluetoothAdapter.STATE_OFF, BluetoothAdapter.STATE_TURNING_OFF -> {
                    radio.stop(); stopOwn(); updateConnection(getString(R.string.enable_bluetooth))
                }
            }
        }
    }
    override fun onCreate() {
        super.onCreate()
        connectionStatus = getString(R.string.background_stopped)
        notifications.createNotificationChannel(NotificationChannel(ONGOING_CHANNEL,
            getString(R.string.background_channel), NotificationManager.IMPORTANCE_LOW))
        notifications.createNotificationChannel(NotificationChannel(ALERT_CHANNEL,
            getString(R.string.alert_channel), NotificationManager.IMPORTANCE_HIGH).apply {
                enableVibration(true); lockscreenVisibility = Notification.VISIBILITY_PRIVATE
            })
        wakeLock = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "$packageName:sos-relay").apply {
            setReferenceCounted(false)
        }
        radio = BleRelay(this, ::receive, ::updateConnection)
        refreshSpeech()
        profile.registerOnSharedPreferenceChangeListener(preferencesChanged)
        getSharedPreferences("active_alerts", MODE_PRIVATE).all.values.forEach { encoded ->
            if (encoded is String) try {
                SosMessage.decode(Base64.decode(encoded, Base64.NO_WRAP), System.currentTimeMillis())?.let { displayed[it.id] = it }
            } catch (_: IllegalArgumentException) { }
        }
        saveAlerts() // Remove expired/corrupt persisted display records when the process returns.
        handler.post(tick)
    }
    override fun onBind(intent: Intent): IBinder { speaker.resume(); return binder }
    override fun onUnbind(intent: Intent): Boolean { if (!foreground) speaker.pause(); return false }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) { stopListening(); return START_NOT_STICKY }
        if (!profile.getBoolean("relay_enabled", true) || !foregroundPermissionsGranted(this) || !backgroundPermissionGranted(this)) {
            stopRuntime(); stopSelf(); return START_NOT_STICKY
        }
        try {
            if (!foreground) connectionStatus = getString(R.string.background_starting)
            // connectedDevice foreground type is available from API 29; Bluetooth grants are checked above.
            if (Build.VERSION.SDK_INT >= 29) startForeground(ONGOING_ID, ongoingNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
            else startForeground(ONGOING_ID, ongoingNotification())
            foreground = true
            speaker.resume()
            if (!receiverRegistered) {
                val filter = IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED)
                // Bluetooth broadcasts can originate from a privileged Bluetooth process, not the system UID.
                if (Build.VERSION.SDK_INT >= 33) registerReceiver(bluetoothChanged, filter, RECEIVER_EXPORTED)
                else registerReceiver(bluetoothChanged, filter)
                receiverRegistered = true
            }
            if (!radio.isRunning) radio.start()
            updateOngoing(); emit()
        } catch (_: RuntimeException) {
            connectionStatus = getString(R.string.background_start_failed)
            stopRuntime(); stopSelf(); emit()
            return START_NOT_STICKY
        }
        return START_STICKY
    }
    internal fun addListener(listener: (RelayState) -> Unit) { listeners.add(listener); listener(snapshot()) }
    internal fun removeListener(listener: (RelayState) -> Unit) { listeners.remove(listener) }
    internal fun snapshot(): RelayState {
        val now = System.currentTimeMillis()
        return RelayState(foreground, radio.isRunning, connectionStatus, speechStatus,
            own?.takeIf { it.expiresAt > now }, displayed.values.filter { it.expiresAt > now })
    }
    private fun emit() { val state = snapshot(); listeners.toList().forEach { it(state) } }
    private fun updateConnection(text: String) { connectionStatus = text; updateOngoing(); emit() }
    internal fun refreshSpeech() {
        if (::speaker.isInitialized) speaker.close()
        speechStatus = getString(R.string.voice_checking)
        speaker = PersianSpeaker(this) { speechStatus = it; emit() }
        speaker.setEnabled(profile.getBoolean("speechEnabled", true)); speaker.resume()
    }
    internal fun testSpeech() { speaker.resume(); speaker.test() }
    internal fun setSpeechEnabled(enabled: Boolean) { profile.edit().putBoolean("speechEnabled", enabled).apply() }
    internal fun sendOwn(person: String, home: String): Boolean {
        if (!foreground || !radio.isRunning) { updateConnection(getString(R.string.connect_first)); return false }
        if (own?.let { it.expiresAt <= System.currentTimeMillis() } == true) stopOwn()
        if (own != null || !SosMessage.validProfile(person, home)) return false
        val message = SosMessage(UUID.randomUUID(), person, home, System.currentTimeMillis() + 120_000)
        if (!inbox.accept(message, System.currentTimeMillis()) || !radio.publish(message)) return false
        own = message; keepAwake(message.expiresAt); emit(); return true
    }
    internal fun stopOwn() { own?.let { radio.remove(it.id) }; own = null; emit() }
    internal fun stopListening() {
        profile.edit().putBoolean("relay_enabled", false).apply()
        stopRuntime(); connectionStatus = getString(R.string.background_stopped)
        stopSelf(); emit()
    }
    private fun stopRuntime() {
        foreground = false; stopForeground(STOP_FOREGROUND_REMOVE); radio.stop(); own = null; speaker.pause()
        if (receiverRegistered) { unregisterReceiver(bluetoothChanged); receiverRegistered = false }
        if (wakeLock.isHeld) wakeLock.release()
    }
    private fun receive(message: SosMessage) {
        val now = System.currentTimeMillis()
        if (!inbox.accept(message, now)) return
        displayed.entries.removeAll { it.value.expiresAt <= now }
        if (displayed.size >= 32) displayed.remove(displayed.keys.first())
        displayed[message.id] = message; saveAlerts()
        keepAwake(message.expiresAt)
        speaker.announce(message)
        postAlert(message)
        inbox.relay(message, now)?.let { radio.publish(it) }
        // Alert details expire with their notification; the persistent service notification describes connectivity only.
        emit()
    }
    private fun saveAlerts() {
        val edit = getSharedPreferences("active_alerts", MODE_PRIVATE).edit().clear()
        displayed.values.forEach { edit.putString(it.id.toString(), Base64.encodeToString(it.encode(), Base64.NO_WRAP)) }
        edit.apply()
    }
    private fun claimRequest(message: SosMessage, now: Long): Boolean {
        val ledger = getSharedPreferences("sos_request_claims", MODE_PRIVATE)
        val key = message.id.toString()
        if (ledger.getLong(key, 0) > now) return false
        val expired = ledger.all.filterValues { (it as? Long ?: 0) <= now }.keys
        if (ledger.all.size - expired.size >= 4096) { updateConnection(getString(R.string.storage_failed)); return false }
        val edit = ledger.edit(); expired.forEach { edit.remove(it) }
        // Durably claim before forwarding. A killed/restarted service must not re-spend a branch budget.
        val saved = edit.putLong(key, message.expiresAt + 86_400_000L).commit()
        if (!saved) updateConnection(getString(R.string.storage_failed))
        return saved
    }
    private fun keepAwake(expiry: Long) {
        val latest = maxOf(expiry, own?.expiresAt ?: 0, displayed.values.maxOfOrNull { it.expiresAt } ?: 0)
        val timeout = (latest - System.currentTimeMillis() + 5_000).coerceIn(1, 125_000)
        wakeLock.acquire(timeout)
    }
    private val tick = object : Runnable { override fun run() {
        val now = System.currentTimeMillis()
        if (own?.let { it.expiresAt <= now } == true) stopOwn()
        if (displayed.entries.removeAll { it.value.expiresAt <= now }) { saveAlerts(); emit() }
        if (own == null && displayed.isEmpty() && wakeLock.isHeld) wakeLock.release()
        handler.postDelayed(this, 1_000)
    } }
    private fun openApp() = PendingIntent.getActivity(this, 0,
        Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    private fun ongoingNotification(): Notification {
        val stop = PendingIntent.getService(this, 1, Intent(this, SosService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, ONGOING_CHANNEL).setSmallIcon(R.drawable.ic_stat_sos)
            .setContentTitle(getString(R.string.background_notification_title)).setContentText(connectionStatus)
            .setStyle(Notification.BigTextStyle().bigText(connectionStatus)).setContentIntent(openApp())
            .setOngoing(true).setOnlyAlertOnce(true).setCategory(Notification.CATEGORY_SERVICE)
            .addAction(Notification.Action.Builder(null, getString(R.string.stop_background), stop).build())
            .apply { if (Build.VERSION.SDK_INT >= 31) setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE) }.build()
    }
    private fun updateOngoing() {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        if (foreground) notifications.notify(ONGOING_ID, ongoingNotification())
    }
    private fun postAlert(message: SosMessage) {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        if (!notifications.areNotificationsEnabled()) return
        val text = getString(R.string.alert_notification_body, message.name, message.unit)
        notifications.notify(message.id.toString(), ALERT_ID, Notification.Builder(this, ALERT_CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_sos).setContentTitle(getString(R.string.alert_notification_title))
            .setContentText(text).setStyle(Notification.BigTextStyle().bigText(text)).setContentIntent(openApp())
            .setAutoCancel(true).setOnlyAlertOnce(true).setVisibility(Notification.VISIBILITY_PRIVATE)
            .setCategory(Notification.CATEGORY_EVENT).setTimeoutAfter((message.expiresAt - System.currentTimeMillis()).coerceAtLeast(1)).build())
    }
    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        profile.unregisterOnSharedPreferenceChangeListener(preferencesChanged)
        stopRuntime(); speaker.close(); listeners.clear()
        super.onDestroy()
    }
}
