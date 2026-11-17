package com.yuki.assistant

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Сердце Юки: держит её на экране, слушает, думает и говорит. */
class YukiService : Service(), VoiceController.Listener, Brain.Callback {

    companion object {
        @Volatile var running = false
        const val ACTION_STOP = "com.yuki.assistant.STOP"
        private const val CHANNEL = "yuki"
        private const val NOTIF_ID = 7
        private const val SLEEP_AFTER_MS = 5 * 60 * 1000L
    }

    private lateinit var prefs: Prefs
    private lateinit var overlay: OverlayController
    private lateinit var voice: VoiceController
    private lateinit var speaker: Speaker
    private lateinit var brain: Brain
    private val h = Handler(Looper.getMainLooper())

    private var thinking = false
    private var expectAnswer = false
    private var lastActive = SystemClock.uptimeMillis()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        running = true
        prefs = Prefs(this)
        startInForeground()

        val tools = PhoneTools(this, prefs) { visible -> setOverlayVisibleSync(visible) }
        brain = Brain(prefs, tools)
        overlay = OverlayController(this, prefs, onTap = { talkNow() }, onLongPress = { hush() })
        overlay.show()
        speaker = Speaker(this, prefs).also { sp ->
            sp.onStart = { overlay.avatar.speaking = true }
            sp.onDone = { overlay.avatar.speaking = false; afterSpeech() }
        }
        voice = VoiceController(this, prefs, this)

        h.postDelayed({ greet() }, 900)
        h.postDelayed(idleTick, 30_000)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        running = false
        h.removeCallbacksAndMessages(null)
        voice.destroy()
        speaker.shutdown()
        brain.shutdown()
        overlay.remove()
        super.onDestroy()
    }

    // ---------- Сценарий ----------

    private fun greet() {
        if (prefs.apiKey.isBlank()) {
            overlay.avatar.emotion = Emotion.SAD
            reply("…Мне нужен ключ API, чтобы думать. Открой настройки, пожалуйста.", false)
            return
        }
        overlay.avatar.emotion = Emotion.SHY
        val name = (prefs.wakeList().firstOrNull() ?: "юки").replaceFirstChar { it.uppercase() }
        reply("…Я тут. Если что — позови: «$name». Или просто коснись меня.", false)
    }

    private fun reply(text: String, waitForAnswer: Boolean) {
        expectAnswer = waitForAnswer
        overlay.busy = true
        voice.stop()
        overlay.say(text, 9000)
        speaker.speak(text)
    }

    private fun afterSpeech() {
        if (expectAnswer) {
            expectAnswer = false
            overlay.avatar.emotion = Emotion.LISTENING
            h.postDelayed({ voice.startCommand() }, 250)
        } else {
            h.postDelayed({ toIdle() }, 1500)
        }
    }

    private fun toIdle() {
        if (thinking || speaker.isSpeaking || voice.mode == VoiceController.Mode.COMMAND) return
        overlay.busy = false
        if (overlay.avatar.emotion != Emotion.SLEEPY) overlay.avatar.emotion = Emotion.NEUTRAL
        voice.startWake()
    }

    private fun listen(prompt: String) {
        lastActive = SystemClock.uptimeMillis()
        speaker.stop()
        overlay.avatar.speaking = false
        overlay.busy = true
        overlay.avatar.emotion = Emotion.LISTENING
        overlay.say(prompt, 0)
        voice.startCommand()
    }

    private fun talkNow() {
        if (thinking) {
            overlay.say("…Я ещё думаю. Подержи меня пальцем, чтобы отменить.", 3000)
            return
        }
        listen("…Слушаю.")
    }

    private fun hush() {
        brain.cancel()
        thinking = false
        expectAnswer = false
        speaker.stop()
        overlay.avatar.speaking = false
        voice.stop()
        overlay.avatar.emotion = Emotion.SHY
        overlay.say("…Хорошо. Молчу.", 2500)
        h.postDelayed({ toIdle() }, 1500)
    }

    private fun handle(text: String) {
        lastActive = SystemClock.uptimeMillis()
        voice.stop()
        overlay.busy = true
        overlay.say("«$text»", 0)
        if (prefs.apiKey.isBlank()) {
            overlay.avatar.emotion = Emotion.SAD
            reply("…Без ключа API я не могу думать. Впиши его в настройках.", false)
            return
        }
        thinking = true
        overlay.avatar.emotion = Emotion.THINKING
        brain.ask(text, this)
    }

    private val idleTick = object : Runnable {
        override fun run() {
            val idle = SystemClock.uptimeMillis() - lastActive
            if (!overlay.busy && !thinking && idle > SLEEP_AFTER_MS && overlay.avatar.emotion == Emotion.NEUTRAL) {
                overlay.avatar.emotion = Emotion.SLEEPY
            }
            h.postDelayed(this, 30_000)
        }
    }

    // ---------- Слух ----------

    override fun onWake(rest: String) {
        if (thinking) return
        if (rest.length >= 2) handle(rest) else listen("…Да?")
    }

    override fun onCommand(text: String) = handle(text)

    override fun onNothingHeard() {
        overlay.say(null)
        toIdle()
    }

    override fun onPartial(text: String) = overlay.say("«$text»", 0)

    override fun onVoiceError(msg: String) {
        overlay.avatar.emotion = Emotion.SAD
        overlay.say(msg, 8000)
        overlay.busy = false
    }

    // ---------- Мозг (приходит из фонового потока) ----------

    override fun onStatus(emotion: Emotion, text: String?) {
        h.post {
            if (!thinking) return@post
            overlay.avatar.emotion = emotion
            if (text != null) overlay.say(text, 0)
        }
    }

    override fun onReply(emotion: Emotion, text: String) {
        h.post {
            if (!thinking) return@post
            thinking = false
            lastActive = SystemClock.uptimeMillis()
            overlay.avatar.emotion = emotion
            reply(text, text.trimEnd().endsWith("?"))
        }
    }

    // ---------- Служебное ----------

    private fun setOverlayVisibleSync(visible: Boolean) {
        val latch = CountDownLatch(1)
        h.post { overlay.setShotHidden(!visible); latch.countDown() }
        latch.await(1, TimeUnit.SECONDS)
    }

    private fun startInForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Юки", NotificationManager.IMPORTANCE_LOW))
        val stopPi = PendingIntent.getService(
            this, 1, Intent(this, YukiService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE
        )
        val openPi = PendingIntent.getActivity(
            this, 2, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val n = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("Юки рядом")
            .setContentText("Позови её по имени или коснись")
            .setContentIntent(openPi)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null as Icon?, "Убрать Юки", stopPi).build())
            .build()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(NOTIF_ID, n)
        }
    }
}
