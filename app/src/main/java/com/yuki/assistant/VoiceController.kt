package com.yuki.assistant

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer

/**
 * Слух Юки.
 * WAKE — слушает по кругу и ждёт своё имя.
 * COMMAND — слушает одну фразу-просьбу.
 */
class VoiceController(
    private val ctx: Context,
    private val prefs: Prefs,
    private val listener: Listener,
) {
    interface Listener {
        fun onWake(rest: String)
        fun onCommand(text: String)
        fun onNothingHeard()
        fun onPartial(text: String)
        fun onVoiceError(msg: String)
    }

    enum class Mode { OFF, WAKE, COMMAND }

    var mode = Mode.OFF
        private set

    private var sr: SpeechRecognizer? = null
    private val h = Handler(Looper.getMainLooper())
    private val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var muted = false
    private var errors = 0

    fun startWake() { mode = Mode.WAKE; begin() }
    fun startCommand() { mode = Mode.COMMAND; begin() }

    fun stop() {
        mode = Mode.OFF
        h.removeCallbacksAndMessages(null)
        try { sr?.cancel() } catch (_: Exception) {}
        unmute()
    }

    fun destroy() {
        stop()
        try { sr?.destroy() } catch (_: Exception) {}
        sr = null
    }

    private fun begin() {
        h.removeCallbacksAndMessages(null)
        if (!SpeechRecognizer.isRecognitionAvailable(ctx)) {
            mode = Mode.OFF
            listener.onVoiceError("…Не нашла распознавание речи. Установи или обнови приложение Google.")
            return
        }
        if (sr == null) {
            sr = SpeechRecognizer.createSpeechRecognizer(ctx).also { it.setRecognitionListener(rl) }
        }
        if (mode == Mode.WAKE && prefs.muteBeep) mute() else unmute()
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ru-RU")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, ctx.packageName)
            if (mode == Mode.COMMAND) {
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1500L)
            }
        }
        try {
            sr?.cancel()
            sr?.startListening(i)
        } catch (e: Exception) {
            retryWake(1000)
        }
    }

    private fun retryWake(ms: Long) {
        if (mode != Mode.WAKE) return
        h.postDelayed({ if (mode == Mode.WAKE) begin() }, ms)
    }

    private val rl = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {}
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}
        override fun onEvent(eventType: Int, params: Bundle?) {}

        override fun onPartialResults(partialResults: Bundle?) {
            if (mode != Mode.COMMAND) return
            val p = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
            if (!p.isNullOrBlank()) listener.onPartial(p)
        }

        override fun onResults(results: Bundle?) {
            errors = 0
            val list = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION) ?: arrayListOf()
            when (mode) {
                Mode.WAKE -> {
                    for (s in list) {
                        val rest = afterWakeWord(s)
                        if (rest != null) {
                            mode = Mode.OFF
                            unmute()
                            listener.onWake(rest)
                            return
                        }
                    }
                    retryWake(100)
                }
                Mode.COMMAND -> {
                    mode = Mode.OFF
                    val text = list.firstOrNull()?.trim()
                    if (text.isNullOrEmpty()) listener.onNothingHeard() else listener.onCommand(text)
                }
                Mode.OFF -> {}
            }
        }

        override fun onError(error: Int) {
            when (mode) {
                Mode.WAKE -> {
                    // Тишина/«ничего не понял» — норма, просто слушаем дальше.
                    val normal = error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT
                    if (normal) errors = 0 else errors++
                    val delay = if (normal) 150L else minOf(300L shl minOf(errors, 5), 8000L)
                    retryWake(delay)
                }
                Mode.COMMAND -> {
                    mode = Mode.OFF
                    if (error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) {
                        listener.onVoiceError("…Нет доступа к микрофону.")
                    } else {
                        listener.onNothingHeard()
                    }
                }
                Mode.OFF -> {}
            }
        }
    }

    /** Если во фразе есть имя Юки — вернёт всё, что сказано после него (может быть пустым). */
    private fun afterWakeWord(phrase: String): String? {
        val s = phrase.lowercase()
        for (w in prefs.wakeList()) {
            var idx = s.indexOf(w)
            while (idx >= 0) {
                val before = if (idx == 0) ' ' else s[idx - 1]
                if (!before.isLetter()) {
                    var end = idx + w.length
                    while (end < s.length && s[end].isLetter()) end++   // «юки», «юкичка»…
                    return phrase.substring(end).trim(' ', ',', '.', '!', '?', '-', '—')
                }
                idx = s.indexOf(w, idx + 1)
            }
        }
        return null
    }

    // Google-распознавалка пищит при каждом перезапуске — в режиме ожидания глушим системные звуки.
    private fun mute() {
        if (muted) return
        try {
            am.adjustStreamVolume(AudioManager.STREAM_NOTIFICATION, AudioManager.ADJUST_MUTE, 0)
            am.adjustStreamVolume(AudioManager.STREAM_SYSTEM, AudioManager.ADJUST_MUTE, 0)
        } catch (_: Exception) {}
        muted = true
    }

    private fun unmute() {
        if (!muted) return
        try {
            am.adjustStreamVolume(AudioManager.STREAM_NOTIFICATION, AudioManager.ADJUST_UNMUTE, 0)
            am.adjustStreamVolume(AudioManager.STREAM_SYSTEM, AudioManager.ADJUST_UNMUTE, 0)
        } catch (_: Exception) {}
        muted = false
    }
}
