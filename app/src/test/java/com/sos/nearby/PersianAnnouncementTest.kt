package com.sos.nearby

import org.junit.Assert.*
import org.junit.Test

class PersianAnnouncementTest {
    @Test fun includesOriginalPersianNameAndUnit() {
        val speech = PersianAnnouncement.text("امینه", "B-204")
        assertTrue(speech.contains("امینه"))
        assertTrue(speech.contains("واحد B-۲۰۴"))
        assertTrue(speech.contains("کمک اضطراری"))
    }
    @Test fun acceptsPersianAndArabicUnitDigits() {
        assertEquals(PersianAnnouncement.text("علی", "۲۰۴"), PersianAnnouncement.text("علی", "٢٠٤"))
    }
    @Test fun stripsControlCharactersFromSpokenIdentity() {
        val speech = PersianAnnouncement.text("\u202eعلی\n", "\t2\u202c")
        assertFalse(speech.any { it.isISOControl() || it == '\u202e' || it == '\u202c' })
        assertTrue(speech.contains("علی"))
        assertTrue(speech.contains("واحد ۲"))
    }
}
