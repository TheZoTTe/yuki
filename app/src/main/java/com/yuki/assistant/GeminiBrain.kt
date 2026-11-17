package com.yuki.assistant

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/** «Мозг» на Google Gemini — бесплатный тариф AI Studio, с лимитом запросов. */
class GeminiBrain(private val prefs: Prefs, private val tools: PhoneTools) : Mind {

    class ApiError(val code: Int, msg: String, val details: String = "") : Exception(msg)

    companion object {
        const val FALLBACK_MODEL = "gemini-flash-lite-latest"
    }

    private val exec = Executors.newSingleThreadExecutor()
    private val history = ArrayList<JSONObject>()
    @Volatile private var generation = 0

    /** Инструменты в формате Gemini (functionDeclarations), собранные из описаний для Claude. */
    private val declarations: JSONArray by lazy {
        val out = JSONArray()
        val spec = tools.spec
        for (i in 0 until spec.length()) {
            val t = spec.getJSONObject(i)
            val d = JSONObject().put("name", t.getString("name")).put("description", t.getString("description"))
            val schema = t.getJSONObject("input_schema")
            val props = schema.optJSONObject("properties") ?: JSONObject()
            if (props.length() > 0) {
                val params = JSONObject().put("type", "object").put("properties", props)
                val req = schema.optJSONArray("required")
                if (req != null && req.length() > 0) params.put("required", req)
                d.put("parameters", params)
            }
            out.put(d)
        }
        out
    }

    override fun ask(userText: String, cb: Brain.Callback) {
        val gen = ++generation
        exec.execute {
            trimHistory()
            val mark = history.size
            try {
                run(userText, cb, gen, mark)
            } catch (e: Exception) {
                rollback(mark)
                if (gen == generation) cb.onReply(Emotion.SAD, explain(e))
            }
        }
    }

    override fun cancel() { generation++ }

    override fun shutdown() { generation++; exec.shutdownNow() }

    private fun rollback(mark: Int) {
        while (history.size > mark) history.removeAt(history.size - 1)
    }

    private fun userMsg(text: String) =
        JSONObject().put("role", "user").put("parts", JSONArray().put(JSONObject().put("text", text)))

    private fun run(userText: String, cb: Brain.Callback, gen: Int, mark: Int) {
        minuteWaits = 0
        waitHook = { if (gen == generation) cb.onStatus(Emotion.SLEEPY, "…Google просит подождать минутку. Жду.") }
        history.add(userMsg(tools.deviceContext() + "\n" + userText))

        for (step in 0 until 20) {
            if (gen != generation) { rollback(mark); return }
            val resp = call()
            if (gen != generation) { rollback(mark); return }

            val cand = resp.optJSONArray("candidates")?.optJSONObject(0)
            val content = cand?.optJSONObject("content")
            if (content == null) {
                rollback(mark)
                cb.onReply(Emotion.SHY, "…Мне не дали ответить на это. Попробуй сказать по-другому.")
                return
            }
            if (!content.has("role")) content.put("role", "model")
            if (!content.has("parts")) content.put("parts", JSONArray())
            history.add(content)

            val parts = content.getJSONArray("parts")
            val calls = ArrayList<JSONObject>()
            val texts = StringBuilder()
            for (i in 0 until parts.length()) {
                val p = parts.getJSONObject(i)
                if (p.optBoolean("thought", false)) continue
                p.optJSONObject("functionCall")?.let { calls.add(it) }
                if (p.has("text")) texts.append(p.optString("text")).append(' ')
            }

            if (calls.isEmpty()) {
                stripImages()
                val (emo, say) = Brain.parse(texts.toString())
                cb.onReply(emo, say.ifBlank { "…Готово." })
                return
            }

            val (emo, note) = Brain.parse(texts.toString())
            if (note.isNotBlank()) cb.onStatus(if (emo == Emotion.NEUTRAL) Emotion.THINKING else emo, note)

            val answer = JSONArray()
            val images = ArrayList<String>()
            for (fc in calls) {
                if (gen != generation) { rollback(mark); return }
                val name = fc.optString("name")
                cb.onStatus(Emotion.THINKING, tools.statusFor(name))
                val r = tools.execute(name, fc.optJSONObject("args") ?: JSONObject())
                val fr = JSONObject().put("name", name).put("response", JSONObject().put("result", r.text))
                if (fc.has("id")) fr.put("id", fc.optString("id"))
                answer.put(JSONObject().put("functionResponse", fr))
                r.imageB64?.let { images.add(it) }
            }
            for (img in images) {
                answer.put(JSONObject().put("inlineData", JSONObject().put("mimeType", "image/jpeg").put("data", img)))
            }
            history.add(JSONObject().put("role", "user").put("parts", answer))
        }
        rollback(mark)
        cb.onReply(Emotion.SAD, "…Я запуталась, слишком много шагов. Давай попробуем по частям?")
    }

    private fun isUserText(o: JSONObject): Boolean {
        if (o.optString("role") != "user") return false
        val p = o.optJSONArray("parts")?.optJSONObject(0) ?: return false
        return p.has("text")
    }

    /** Храним последние ~8 просьб: на бесплатном тарифе важна экономия. */
    private fun trimHistory() {
        var turns = history.count { isUserText(it) }
        while (turns >= 8 && history.isNotEmpty()) {
            history.removeAt(0)
            while (history.isNotEmpty() && !isUserText(history[0])) history.removeAt(0)
            turns--
        }
    }

    private fun stripImages() {
        for (msg in history) {
            val parts = msg.optJSONArray("parts") ?: continue
            for (i in 0 until parts.length()) {
                val p = parts.optJSONObject(i) ?: continue
                if (p.has("inlineData")) parts.put(i, JSONObject().put("text", "[тут был скриншот]"))
            }
        }
    }

    /** Повторяет запрос при перегрузке Google и при необходимости берёт лёгкую запасную модель. */
    private fun call(): JSONObject {
        val main = prefs.geminiModel.ifBlank { Prefs.DEFAULT_GEMINI_MODEL }
        val models = listOf(main, main, main, FALLBACK_MODEL).let { if (main == FALLBACK_MODEL) it.dropLast(1) else it }
        var last: Exception? = null
        for ((i, m) in models.withIndex()) {
            try {
                return callOnce(m)
            } catch (e: ApiError) {
                last = e
                if (e.code == 429) {
                    // Дневной лимит — ждать бессмысленно. Минутный — подождём, сколько просит Google.
                    if (isDaily(e)) throw e
                    if (minuteWaits >= 2) throw e
                    minuteWaits++
                    waitHook?.invoke()
                    Thread.sleep(retryDelayMs(e))
                    continue
                }
                if (e.code != 500 && e.code != 503) throw e
                Thread.sleep(1500L * (i + 1))
            }
        }
        throw last ?: IOException("нет ответа")
    }

    private var minuteWaits = 0
    /** Показать «жду лимит» (ставится на время хода). */
    @Volatile private var waitHook: (() -> Unit)? = null

    private fun isDaily(e: ApiError): Boolean {
        val m = (e.message ?: "") + " " + e.details
        return m.contains("PerDay", true) || m.contains("per day", true) || m.contains("daily", true)
    }

    private fun retryDelayMs(e: ApiError): Long {
        val m = (e.message ?: "") + " " + e.details
        val sec = Regex("retry in ([0-9.]+)s", RegexOption.IGNORE_CASE).find(m)?.groupValues?.get(1)?.toDoubleOrNull()
            ?: Regex("\"retryDelay\"\\s*:\\s*\"([0-9.]+)s\"").find(m)?.groupValues?.get(1)?.toDoubleOrNull()
            ?: 20.0
        return ((sec + 1.0) * 1000).toLong().coerceIn(3000L, 60000L)
    }

    private fun callOnce(model: String): JSONObject {
        val body = JSONObject()
            .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", Brain.SYSTEM_PROMPT))))
            .put("contents", JSONArray(history))
            .put("tools", JSONArray().put(JSONObject().put("functionDeclarations", declarations)))
            .put("generationConfig", JSONObject().put("maxOutputTokens", 4096))

        val url = URL("https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent")
        val conn = url.openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.connectTimeout = 20_000
            conn.readTimeout = 120_000
            conn.setRequestProperty("content-type", "application/json")
            conn.setRequestProperty("x-goog-api-key", prefs.geminiKey)
            conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val txt = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
            if (code !in 200..299) {
                val msg = try { JSONObject(txt).getJSONObject("error").optString("message") } catch (_: Exception) { txt.take(200) }
                throw ApiError(code, msg, txt.take(4000))
            }
            return JSONObject(txt)
        } finally {
            conn.disconnect()
        }
    }

    private fun explain(e: Exception): String {
        if (e is IOException) return "…Нет связи с сервером. Проверь интернет."
        if (e !is ApiError) return "…Что-то сломалось: ${e.message?.take(120)}"
        val m = e.message ?: ""
        return when {
            m.contains("API key", true) -> "…Ключ Gemini не подходит. Проверь его в настройках."
            m.contains("location", true) || m.contains("region", true) -> "…Google говорит, что Gemini недоступен в твоём регионе."
            e.code == 429 && isDaily(e) -> "…Бесплатные запросы Gemini на сегодня закончились. Завтра снова смогу, или переключи меня на Claude."
            e.code == 429 -> "…Google не даёт столько запросов подряд. Подожди минутку и попроси ещё раз."
            e.code == 404 -> "…Не знаю такую модель: «${prefs.geminiModel}». Поправь название в настройках."
            e.code == 500 || e.code == 503 -> "…Сервер Google перегружен (${e.code}): ${m.take(100)}"
            else -> "…Ошибка ${e.code}: ${m.take(120)}"
        }
    }
}
