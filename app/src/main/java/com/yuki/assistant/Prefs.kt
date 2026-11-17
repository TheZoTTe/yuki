package com.yuki.assistant

import android.content.Context

/** Настройки Юки (хранятся только на телефоне). */
class Prefs(ctx: Context) {
    private val p = ctx.getSharedPreferences("yuki", Context.MODE_PRIVATE)

    var apiKey: String
        get() = p.getString("apiKey", "") ?: ""
        set(v) = p.edit().putString("apiKey", v.trim()).apply()

    /** "claude" или "gemini". */
    var provider: String
        get() = p.getString("provider", "claude") ?: "claude"
        set(v) = p.edit().putString("provider", v).apply()

    var geminiKey: String
        get() = p.getString("geminiKey", "") ?: ""
        set(v) = p.edit().putString("geminiKey", v.trim()).apply()

    var geminiModel: String
        get() = p.getString("geminiModel", DEFAULT_GEMINI_MODEL) ?: DEFAULT_GEMINI_MODEL
        set(v) = p.edit().putString("geminiModel", v.trim().ifEmpty { DEFAULT_GEMINI_MODEL }).apply()

    val useGemini: Boolean get() = provider == "gemini"

    /** Ключ выбранного «мозга». */
    val activeKey: String get() = if (useGemini) geminiKey else apiKey

    var model: String
        get() = p.getString("model", DEFAULT_MODEL) ?: DEFAULT_MODEL
        set(v) = p.edit().putString("model", v.trim().ifEmpty { DEFAULT_MODEL }).apply()

    var wakeWords: String
        get() = p.getString("wake", DEFAULT_WAKE) ?: DEFAULT_WAKE
        set(v) = p.edit().putString("wake", v.trim().ifEmpty { DEFAULT_WAKE }).apply()

    var pitch: Float
        get() = p.getFloat("pitch", 1.25f)
        set(v) = p.edit().putFloat("pitch", v.coerceIn(0.5f, 2.0f)).apply()

    var blocked: String
        get() = p.getString("blocked", DEFAULT_BLOCKED) ?: DEFAULT_BLOCKED
        set(v) = p.edit().putString("blocked", v.trim()).apply()

    var wander: Boolean
        get() = p.getBoolean("wander", true)
        set(v) = p.edit().putBoolean("wander", v).apply()

    var muteBeep: Boolean
        get() = p.getBoolean("muteBeep", true)
        set(v) = p.edit().putBoolean("muteBeep", v).apply()

    fun wakeList(): List<String> =
        wakeWords.split(',').map { it.trim().lowercase() }.filter { it.isNotEmpty() }

    fun blockedList(): List<String> =
        blocked.split(',', '\n', ' ', ';').map { it.trim() }.filter { it.isNotEmpty() }

    companion object {
        const val DEFAULT_MODEL = "claude-sonnet-5-5"
        const val DEFAULT_GEMINI_MODEL = "gemini-flash-latest"
        const val DEFAULT_WAKE = "юки,юкки,юка,юкий"
        const val DEFAULT_BLOCKED =
            "ru.sberbankmobile,com.idamob.tinkoff.android,ru.vtb24.mobilebanking.android," +
                "ru.alfabank.mobile.android,ru.raiffeisennews,com.openbank,ru.gazprombank.android.mobilebank.app," +
                "com.google.android.apps.walletnfcrel"
    }
}

enum class Emotion {
    NEUTRAL, HAPPY, SHY, THINKING, SURPRISED, SAD, SLEEPY, ANNOYED, LISTENING;

    companion object {
        fun parse(s: String?): Emotion =
            values().firstOrNull { it.name.equals(s?.trim(), ignoreCase = true) } ?: NEUTRAL
    }
}
