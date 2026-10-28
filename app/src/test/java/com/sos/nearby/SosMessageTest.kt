package com.sos.nearby

import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class SosMessageTest {
    private val now = 1_000_000L
    private fun message() = SosMessage(UUID.randomUUID(), "Amina 李", "B-204", now + 120_000)
    @Test fun identitySurvivesEncodingAndRelay() {
        val original = message()
        val relayed = RelayInbox().relay(original, now)!!
        assertEquals(original.copy(hops = 1), SosMessage.decode(relayed.encode(), now))
    }
    @Test fun meshLoopIsSuppressedByOriginalId() {
        val inbox = RelayInbox()
        val original = message()
        assertTrue(inbox.accept(original, now))
        assertFalse(inbox.accept(original.copy(hops = 2), now + 1))
        assertTrue(inbox.accept(message(), now + 2))
    }
    @Test fun expiryAndHopLimitStopPropagation() {
        val inbox = RelayInbox()
        assertNull(inbox.relay(message().copy(hops = 3), now))
        assertNull(inbox.relay(message(), now + 120_000))
        assertNull(SosMessage.decode(message().encode(), now + 120_000))
        assertNull(SosMessage.decode(message().copy(hops = 4).encode(), now))
        assertNull(SosMessage.decode(message().copy(expiresAt = now + 120_001).encode(), now))
    }
    @Test fun zeroBudgetCannotBeRelayedAndInvalidBudgetsAreRejected() {
        val inbox = RelayInbox()
        assertNull(inbox.relay(message().copy(remainingNodes = 0), now))
        assertNull(SosMessage.decode(message().copy(remainingNodes = 30).encode(), now))
        assertEquals(7, SosMessage.decode(message().copy(remainingNodes = 7).encode(), now)?.remainingNodes)
    }
    @Test fun persistedClaimsSurviveRecreatingTheInbox() {
        val stored = mutableSetOf<UUID>()
        val claim: (SosMessage, Long) -> Boolean = { m, _ -> stored.add(m.id) }
        val original = message()
        assertTrue(RelayInbox(claim).accept(original, now))
        assertFalse(RelayInbox(claim).accept(original.copy(hops = 1), now + 1))
        assertTrue(RelayInbox(claim).accept(message(), now + 2))
    }
    @Test fun failedDurableClaimDoesNotPermitForwarding() {
        assertFalse(RelayInbox { _, _ -> false }.accept(message(), now))
    }
    @Test fun malformedAndOversizedProfilesAreRejected() {
        assertFalse(SosMessage.validProfile("", "2"))
        assertFalse(SosMessage.validProfile("a".repeat(49), "2"))
        assertFalse(SosMessage.validProfile("A\nB", "2"))
        assertNull(SosMessage.decode(byteArrayOf(1, 2, 3), now))
        assertNull(SosMessage.decode(message().encode() + byteArrayOf(0), now))
    }
}
