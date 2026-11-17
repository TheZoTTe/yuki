package com.yuki.assistant

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale

/** Голос Юки (встроенный синтез речи Android, чуть выше обычного тона). */
class Speaker(ctx: Context, private val prefs: Prefs) : TextToSpeech.OnInitListener {
    var onStart: (() -> Unit)? = null
    var onDone: (() -> Unit)? = null

    private val h = Handler(Looper.getMainLooper())
    private val tts = TextToSpeech(ctx.applicationContext, this)
    private var ready = false

    @Volatile var isSpeaking = false
        private set

    override fun onInit(status: Int) {
        if (status != TextToSpeech.SUCCESS) return
        tts.language = Locale("ru", "RU")
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                h.post { isSpeaking = true; onStart?.invoke() }
            }
            override fun onDone(utteranceId: String?) {
                h.post { finished() }
            }
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                h.post { finished() }
            }
            override fun onStop(utteranceId: String?, interrupted: Boolean) {
                h.post { isSpeaking = false }
            }
        })
        ready = true
    }

    private fun finished() {
        if (!isSpeaking) { onDone?.invoke(); return }
        isSpeaking = false
        onDone?.invoke()
    }

    fun speak(text: String) {
        val clean = text.replace(Regex("[*_#`~]"), "").replace("…", ", ").trim()
        if (!ready || clean.isEmpty()) {
            h.post { onDone?.invoke() }
            return
        }
        tts.setPitch(prefs.pitch)
        tts.setSpeechRate(1.0f)
        tts.speak(clean, TextToSpeech.QUEUE_FLUSH, null, "yuki" + System.currentTimeMillis())
    }

    fun stop() {
        isSpeaking = false
        if (ready) tts.stop()
    }

    fun shutdown() {
        try { tts.stop(); tts.shutdown() } catch (_: Exception) {}
    }
}
