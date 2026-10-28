package com.sos.nearby

import android.content.Context
import android.media.AudioAttributes
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.UUID

/** Only explicitly local, installed Persian voices are eligible. Never use a network voice. */
internal class PersianSpeaker(context: Context, private val status: (String) -> Unit) {
    private val handler = Handler(Looper.getMainLooper())
    private var engine: TextToSpeech? = null
    private var initialized = false
    private var initializationFailed = false
    private var ready = false
    private var active = false
    private var closed = false
    private var enabled = true
    private val pending = linkedMapOf<String, SosMessage>()
    private val speaking = mutableSetOf<String>()

    init {
        engine = TextToSpeech(context.applicationContext) { result -> handler.post {
            if (closed) return@post
            initialized = result == TextToSpeech.SUCCESS
            initializationFailed = !initialized
            if (!initialized) {
                status("گفتار در دسترس نیست. موتور تبدیل متن به گفتار را در تنظیمات بررسی کنید.")
                pending.clear()
            } else {
                engine?.setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                engine?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) { }
                    override fun onDone(utteranceId: String?) { handler.post { speaking.remove(utteranceId) } }
                    @Deprecated("Compatibility callback")
                    override fun onError(utteranceId: String?) { handler.post {
                        if (!closed) { speaking.remove(utteranceId); status("پخش گفتار ناموفق بود. هشدار متنی را بررسی کنید.") }
                    } }
                })
                checkVoice()
            }
        } }
    }
    fun resume() { active = true; if (initialized) checkVoice() }
    fun pause() { active = false; pending.clear(); speaking.clear(); engine?.stop() }
    fun setEnabled(value: Boolean) {
        enabled = value
        if (!value) { pending.clear(); speaking.clear(); engine?.stop(); status("اعلان صوتی خاموش است. هشدارهای متنی فعال هستند.") }
        else if (initialized) checkVoice()
    }
    private fun checkVoice() {
        ready = false
        try {
            val voice = engine?.voices?.filter {
                it.locale.language == "fa" && !it.isNetworkConnectionRequired &&
                    it.features?.contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED) != true
            }?.sortedWith(compareBy({ it.locale.country != "IR" }, { it.name }))?.firstOrNull()
            ready = voice != null && engine?.setVoice(voice) == TextToSpeech.SUCCESS
            if (ready) {
                engine?.setSpeechRate(0.9f)
                status(if (enabled) "صدای فارسی آفلاین آماده است. بلندی صدای رسانه را بررسی کنید."
                    else "اعلان صوتی خاموش است. هشدارهای متنی فعال هستند.")
                val waiting = pending.values.toList(); pending.clear()
                waiting.forEach { announce(it) }
            } else {
                pending.clear()
                status("صدای فارسی آفلاین نصب نیست. در تنظیمات گفتار، صدای فارسی را دانلود کنید و سپس با حالت پرواز آزمایش کنید.")
            }
        } catch (_: RuntimeException) {
            pending.clear(); status("بررسی صدای آفلاین ناموفق بود. تنظیمات گفتار را بررسی کنید.")
        }
    }
    fun announce(message: SosMessage) {
        if (initializationFailed || !active || !enabled || message.expiresAt <= System.currentTimeMillis()) return
        val id = message.id.toString()
        if (id in speaking || id in pending) return
        if (!initialized) { if (pending.size < 8) pending[id] = message; return }
        if (!ready) return
        speak(PersianAnnouncement.text(message.name, message.unit), id)
    }
    fun test() {
        if (!active) return
        if (!enabled) { status("برای آزمایش، اعلان صوتی را روشن کنید."); return }
        if (!initialized) {
            status(if (initializationFailed) "موتور گفتار در دسترس نیست. آن را در تنظیمات دستگاه نصب یا فعال کنید؛ سپس برنامه را دوباره باز کنید."
                else "موتور گفتار هنوز آماده نیست. کمی بعد دوباره آزمایش کنید.")
            return
        }
        checkVoice()
        if (ready) speak(PersianAnnouncement.text("آزمایش", "۲۰۴"), "test-${UUID.randomUUID()}")
    }
    private fun speak(text: String, id: String) {
        if (speaking.size >= 8) { status("صف گفتار پر است. هشدارهای متنی را بررسی کنید."); return }
        speaking.add(id)
        val result = try { engine?.speak(text, TextToSpeech.QUEUE_ADD, null, id) } catch (_: RuntimeException) { TextToSpeech.ERROR }
        if (result != TextToSpeech.SUCCESS) {
            speaking.remove(id); status("پخش گفتار ناموفق بود. هشدار متنی را بررسی کنید.")
        }
    }
    fun close() { closed = true; pause(); engine?.shutdown(); engine = null; handler.removeCallbacksAndMessages(null) }
}
