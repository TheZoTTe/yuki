package com.yuki.assistant

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast

/** Экран настроек: разрешения, ключ, характер, запуск. */
class MainActivity : Activity() {

    private lateinit var prefs: Prefs
    private lateinit var col: LinearLayout
    private lateinit var statusView: TextView
    private lateinit var startBtn: Button
    private lateinit var keyEdit: EditText
    private lateinit var geminiKeyEdit: EditText
    private lateinit var geminiModelEdit: EditText
    private lateinit var providerGroup: RadioGroup
    private var claudeRadioId = 0
    private lateinit var modelEdit: EditText
    private lateinit var wakeEdit: EditText
    private lateinit var pitchEdit: EditText
    private lateinit var blockedEdit: EditText
    private lateinit var wanderSw: Switch
    private lateinit var beepSw: Switch

    private val accent = Color.parseColor("#8B6FD6")
    private val ink = Color.parseColor("#2E2640")
    private val muted = Color.parseColor("#6F6688")

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        window.statusBarColor = Color.parseColor("#EFEAFB")

        col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(12), dp(20), dp(40))
        }

        val avatar = AvatarView(this).apply { emotion = Emotion.SHY }
        col.addView(avatar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(210)))
        val emotions = Emotion.values()
        var ei = emotions.indexOf(Emotion.SHY)
        val emoLabel = text("Коснись её — покажет эмоции", 13f, muted).apply { gravity = Gravity.CENTER }
        avatar.setOnClickListener {
            ei = (ei + 1) % emotions.size
            avatar.emotion = emotions[ei]
            emoLabel.text = emotions[ei].name.lowercase()
        }
        col.addView(text("Юки", 30f, ink, bold = true).apply { gravity = Gravity.CENTER })
        col.addView(emoLabel)

        statusView = text("", 15f, ink).apply {
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#F4F0FF")); cornerRadius = dp(14).toFloat()
            }
        }
        col.addView(statusView, lpTop(16))

        header("1. Разрешения")
        button("Поверх других окон") {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        }
        button("Микрофон, звонки, контакты, уведомления") { askRuntimePermissions() }
        button("Спецвозможности (чтобы видела экран и нажимала)") {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        note("В списке спецвозможностей найди «Юки — управление телефоном» и включи. " +
            "Если переключатель серый и пишет про ограниченные настройки: нажми кнопку ниже → ⋮ (три точки вверху) → " +
            "«Разрешить ограниченные настройки», и снова включи.")
        button("Сведения о приложении") {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
        }

        header("2. Мозг")
        providerGroup = RadioGroup(this).apply { orientation = RadioGroup.VERTICAL }
        val rbClaude = RadioButton(this).apply {
            id = View.generateViewId(); text = "Claude — умнее, платно (есть стартовый бонус)"; textSize = 15f; setTextColor(ink)
        }
        val rbGemini = RadioButton(this).apply {
            id = View.generateViewId(); text = "Gemini — бесплатно, но мало запросов в день"; textSize = 15f; setTextColor(ink)
        }
        claudeRadioId = rbClaude.id
        providerGroup.addView(rbClaude)
        providerGroup.addView(rbGemini)
        providerGroup.check(if (prefs.useGemini) rbGemini.id else rbClaude.id)
        col.addView(providerGroup, lpTop(6))

        keyEdit = field("Ключ Claude API (начинается с sk-ant-)", prefs.apiKey,
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
        button("Где взять ключ Claude") {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://console.anthropic.com/")))
        }
        modelEdit = field("Модель Claude", prefs.model, InputType.TYPE_CLASS_TEXT)
        note("claude-sonnet-5-5 — умнее, лучше справляется с телефоном. claude-haiku-5-5 — быстрее и дешевле.")

        geminiKeyEdit = field("Ключ Gemini (начинается с AIza…)", prefs.geminiKey,
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
        button("Где взять ключ Gemini (бесплатно)") {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://aistudio.google.com/apikey")))
        }
        geminiModelEdit = field("Модель Gemini", prefs.geminiModel, InputType.TYPE_CLASS_TEXT)
        note("gemini-flash-latest — всегда свежая быстрая модель. Если выбор мозга поменял — перезапусти Юки.")

        header("3. Характер и поведение")
        wakeEdit = field("Как её звать (слова через запятую)", prefs.wakeWords, InputType.TYPE_CLASS_TEXT)
        pitchEdit = field("Высота голоса (1.0 — обычная, 1.3 — выше)", prefs.pitch.toString(),
            InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL)
        wanderSw = switch("Гулять по экрану", prefs.wander)
        beepSw = switch("Глушить писк микрофона в режиме ожидания (заодно глушит звук уведомлений, пока Юки слушает)", prefs.muteBeep)
        blockedEdit = field("Приватные приложения — сюда Юки не смотрит (названия пакетов через запятую)",
            prefs.blocked, InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE)
        button("Сохранить") { save(); toast("Сохранено") }

        startBtn = button("▶  Запустить Юки", primary = true) { toggle() }
        col.addView(text("Остановить можно здесь или кнопкой в уведомлении. Подержи Юки пальцем — она замолчит и отменит текущее дело.",
            13f, muted), lpTop(8))

        setContentView(ScrollView(this).apply {
            setBackgroundColor(Color.parseColor("#FBFAFF"))
            addView(col)
        })
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        refresh()
    }

    // ---------- Логика ----------

    private fun save() {
        prefs.apiKey = keyEdit.text.toString()
        prefs.model = modelEdit.text.toString()
        prefs.geminiKey = geminiKeyEdit.text.toString()
        prefs.geminiModel = geminiModelEdit.text.toString()
        prefs.provider = if (providerGroup.checkedRadioButtonId == claudeRadioId) "claude" else "gemini"
        prefs.wakeWords = wakeEdit.text.toString()
        pitchEdit.text.toString().replace(',', '.').toFloatOrNull()?.let { prefs.pitch = it }
        prefs.blocked = blockedEdit.text.toString()
        prefs.wander = wanderSw.isChecked
        prefs.muteBeep = beepSw.isChecked
    }

    private fun toggle() {
        val svc = Intent(this, YukiService::class.java)
        if (YukiService.running) {
            stopService(svc)
        } else {
            save()
            if (!Settings.canDrawOverlays(this)) { toast("Сначала разреши «Поверх других окон»"); return }
            if (!granted(Manifest.permission.RECORD_AUDIO)) { toast("Нужен доступ к микрофону"); askRuntimePermissions(); return }
            if (prefs.activeKey.isBlank()) toast("Без ключа API она сможет только смущённо молчать")
            startForegroundService(svc)
        }
        Handler(Looper.getMainLooper()).postDelayed({ refresh() }, 600)
    }

    private fun askRuntimePermissions() {
        val list = arrayListOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.CALL_PHONE,
            Manifest.permission.READ_CONTACTS,
        )
        if (Build.VERSION.SDK_INT >= 33) list.add(Manifest.permission.POST_NOTIFICATIONS)
        requestPermissions(list.toTypedArray(), 1)
    }

    private fun granted(p: String) = checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED

    private fun a11yEnabled(): Boolean {
        val s = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
        return s.contains("$packageName/")
    }

    private fun refresh() {
        fun mark(ok: Boolean) = if (ok) "✅" else "❌"
        statusView.text = buildString {
            append(mark(Settings.canDrawOverlays(this@MainActivity))).append("  Поверх окон\n")
            append(mark(granted(Manifest.permission.RECORD_AUDIO))).append("  Микрофон\n")
            append(mark(a11yEnabled())).append("  Спецвозможности (руки и глаза)\n")
            append(mark(prefs.activeKey.isNotBlank())).append("  Ключ API (").append(if (prefs.useGemini) "Gemini" else "Claude").append(")\n")
            append(if (YukiService.running) "🟣  Юки на экране" else "⚪  Юки спит")
        }
        startBtn.text = if (YukiService.running) "■  Убрать Юки" else "▶  Запустить Юки"
    }

    // ---------- Мини-конструктор интерфейса ----------

    private fun lpTop(top: Int) = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
    ).apply { topMargin = dp(top) }

    private fun text(s: String, size: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        text = s
        textSize = size
        setTextColor(color)
        if (bold) typeface = Typeface.DEFAULT_BOLD
    }

    private fun header(s: String) {
        col.addView(text(s, 18f, accent, bold = true), lpTop(24))
    }

    private fun note(s: String) {
        col.addView(text(s, 13f, muted), lpTop(6))
    }

    private fun button(label: String, primary: Boolean = false, onClick: () -> Unit): Button {
        val b = Button(this).apply {
            text = label
            isAllCaps = false
            textSize = 15f
            setTextColor(if (primary) Color.WHITE else ink)
            background = GradientDrawable().apply {
                setColor(if (primary) accent else Color.parseColor("#ECE6FA"))
                cornerRadius = dp(14).toFloat()
            }
            setPadding(dp(14), dp(12), dp(14), dp(12))
            setOnClickListener { onClick() }
        }
        col.addView(b, lpTop(if (primary) 20 else 8))
        return b
    }

    private fun field(label: String, value: String, type: Int): EditText {
        col.addView(text(label, 13f, muted), lpTop(10))
        val e = EditText(this).apply {
            setText(value)
            inputType = type
            textSize = 15f
            setTextColor(ink)
        }
        col.addView(e, lpTop(2))
        return e
    }

    private fun switch(label: String, checked: Boolean): Switch {
        val sw = Switch(this).apply {
            text = label
            isChecked = checked
            textSize = 14f
            setTextColor(ink)
        }
        col.addView(sw, lpTop(10))
        return sw
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()
}
