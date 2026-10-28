package com.sos.nearby

import java.io.*
import java.util.UUID

internal data class SosMessage(val id: UUID, val name: String, val unit: String, val expiresAt: Long, val hops: Int = 0, val remainingNodes: Int = 29) {
    fun encode(): ByteArray = ByteArrayOutputStream().also { bytes ->
        DataOutputStream(bytes).use {
            it.writeByte(2); it.writeLong(id.mostSignificantBits); it.writeLong(id.leastSignificantBits)
            it.writeLong(expiresAt); it.writeByte(hops); it.writeByte(remainingNodes); it.writeUTF(name); it.writeUTF(unit)
        }
    }.toByteArray()
    companion object {
        fun validProfile(name: String, unit: String) = name.isNotBlank() && unit.isNotBlank() &&
            name.toByteArray(Charsets.UTF_8).size <= 48 && unit.toByteArray(Charsets.UTF_8).size <= 24 &&
            name.none { it.isISOControl() } && unit.none { it.isISOControl() }
        fun decode(bytes: ByteArray, now: Long): SosMessage? = try {
            if (bytes.size > 160) null else DataInputStream(ByteArrayInputStream(bytes)).use {
                if (it.readUnsignedByte() != 2) return null
                val message = SosMessage(UUID(it.readLong(), it.readLong()), "", "", it.readLong(), it.readUnsignedByte(), it.readUnsignedByte())
                val result = message.copy(name = it.readUTF(), unit = it.readUTF())
                result.takeIf { m -> it.available() == 0 && validProfile(m.name, m.unit) &&
                    m.hops in 0..3 && m.remainingNodes in 0..29 && m.expiresAt > now && m.expiresAt <= now + 120_000 }
            }
        } catch (_: Exception) { null }
    }
}

internal class RelayInbox(private val persistClaim: (SosMessage, Long) -> Boolean = { _, _ -> true }) {
    private val seen = linkedMapOf<UUID, Long>()
    fun accept(message: SosMessage, now: Long): Boolean {
        seen.entries.removeAll { it.value <= now }
        if (message.expiresAt <= now || message.id in seen || seen.size >= 256) return false
        if (!persistClaim(message, now)) return false
        seen[message.id] = message.expiresAt
        return true
    }
    fun relay(message: SosMessage, now: Long): SosMessage? =
        message.takeIf { it.expiresAt > now && it.hops < 3 && it.remainingNodes > 0 }?.copy(hops = message.hops + 1)
}
