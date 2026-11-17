package com.yuki.assistant

import android.Manifest
import android.app.SearchManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.provider.AlarmClock
import android.provider.ContactsContract
import android.util.Base64
import android.view.KeyEvent
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ToolResult(val text: String, val imageB64: String? = null)

/** Всё, что Юки умеет делать с телефоном. Вызывается из фонового потока. */
class PhoneTools(
    private val ctx: Context,
    private val prefs: Prefs,
    private val setOverlayVisible: (Boolean) -> Unit,
) {
    companion object {
        const val SHOT_W = 720
    }

    // ---------- Описание инструментов для Claude ----------

    val spec: JSONArray by lazy { buildSpec() }

    private fun prop(type: String, desc: String) = JSONObject().put("type", type).put("description", desc)

    private fun tool(name: String, desc: String, props: JSONObject = JSONObject(), required: List<String> = emptyList()) =
        JSONObject()
            .put("name", name)
            .put("description", desc)
            .put("input_schema", JSONObject()
                .put("type", "object")
                .put("properties", props)
                .put("required", JSONArray(required)))

    private fun enumProp(desc: String, vararg values: String) =
        JSONObject().put("type", "string").put("description", desc).put("enum", JSONArray(values.toList()))

    private fun buildSpec(): JSONArray = JSONArray()
        .put(tool("open_app", "Открыть установленное приложение по названию (например «Telegram», «YouTube», «Камера»).",
            JSONObject().put("name", prop("string", "Название приложения")), listOf("name")))
        .put(tool("list_apps", "Список установленных приложений (названия). Используй, если open_app не нашёл."))
        .put(tool("read_screen", "Прочитать, что сейчас на экране: пронумерованный список элементов с текстом и координатами. Номера нужны для tap и type_text."))
        .put(tool("look_at_screen", "Сделать скриншот и посмотреть на экран глазами. Используй, когда read_screen мало что показал (игры, картинки, видео). Координаты на снимке совпадают с xy из read_screen и подходят для tap_xy."))
        .put(tool("tap", "Нажать на элемент по номеру из последнего read_screen.",
            JSONObject().put("id", prop("integer", "Номер элемента")), listOf("id")))
        .put(tool("tap_xy", "Нажать в точку экрана (координаты как в read_screen / на скриншоте, ширина экрана = 720). long=true — долгое нажатие.",
            JSONObject().put("x", prop("number", "x")).put("y", prop("number", "y")).put("long", prop("boolean", "Долгое нажатие")),
            listOf("x", "y")))
        .put(tool("type_text", "Ввести текст в поле ввода. id — номер поля из read_screen; без id — в поле, где сейчас курсор.",
            JSONObject().put("text", prop("string", "Текст")).put("id", prop("integer", "Номер поля (необязательно)")),
            listOf("text")))
        .put(tool("scroll", "Пролистать экран.",
            JSONObject().put("direction", enumProp("down — вниз по ленте, up — вверх, left/right — вбок", "down", "up", "left", "right")),
            listOf("direction")))
        .put(tool("press", "Системная кнопка.",
            JSONObject().put("key", enumProp("Кнопка", "back", "home", "recents", "notifications", "quick_settings", "lock")),
            listOf("key")))
        .put(tool("wait", "Подождать, пока что-то загрузится (секунды, до 8).",
            JSONObject().put("seconds", prop("number", "Сколько секунд")), listOf("seconds")))
        .put(tool("set_alarm", "Поставить будильник.",
            JSONObject().put("hour", prop("integer", "Часы 0-23")).put("minute", prop("integer", "Минуты"))
                .put("label", prop("string", "Подпись (необязательно)")), listOf("hour", "minute")))
        .put(tool("set_timer", "Запустить таймер.",
            JSONObject().put("seconds", prop("integer", "Длительность в секундах")).put("label", prop("string", "Подпись")),
            listOf("seconds")))
        .put(tool("call", "Позвонить. who — имя контакта в именительном падеже («Мама», «Саня») или номер телефона.",
            JSONObject().put("who", prop("string", "Кому")), listOf("who")))
        .put(tool("sms", "Открыть SMS кому-то с готовым текстом (отправку потом подтверждают кнопкой в приложении).",
            JSONObject().put("who", prop("string", "Имя контакта или номер")).put("text", prop("string", "Текст")),
            listOf("who", "text")))
        .put(tool("open_url", "Открыть ссылку в браузере.",
            JSONObject().put("url", prop("string", "Адрес")), listOf("url")))
        .put(tool("web_search", "Открыть поиск в интернете по запросу.",
            JSONObject().put("query", prop("string", "Запрос")), listOf("query")))
        .put(tool("media", "Управление музыкой/видео и громкостью.",
            JSONObject().put("action", enumProp("Действие", "play_pause", "next", "previous", "volume_up", "volume_down", "mute", "unmute")),
            listOf("action")))
        .put(tool("flashlight", "Включить или выключить фонарик.",
            JSONObject().put("on", prop("boolean", "true — включить")), listOf("on")))

    // ---------- Выполнение ----------

    fun statusFor(name: String): String = when (name) {
        "open_app" -> "…открываю"
        "read_screen" -> "…читаю экран"
        "look_at_screen" -> "…смотрю"
        "tap", "tap_xy" -> "…нажимаю"
        "type_text" -> "…печатаю"
        "scroll" -> "…листаю"
        "call" -> "…набираю"
        "wait" -> "…жду"
        else -> "…делаю"
    }

    fun execute(name: String, input: JSONObject): ToolResult = try {
        when (name) {
            "open_app" -> openApp(input.optString("name"))
            "list_apps" -> ToolResult(apps().joinToString(", ") { it.first })
            "read_screen" -> withA11y(true) { ToolResult(it.dumpScreen()) }
            "look_at_screen" -> withA11y(true) { lookAtScreen(it) }
            "tap" -> withA11y(true) { a ->
                val ok = a.clickNode(input.optInt("id", -1))
                settle()
                ToolResult(if (ok) "Нажала. Проверь результат через read_screen." else "Нет элемента с таким номером — сначала read_screen.")
            }
            "tap_xy" -> withA11y(true) { a ->
                val k = a.scale()
                val x = input.optDouble("x", 0.0).toFloat() * k
                val y = input.optDouble("y", 0.0).toFloat() * k
                val ok = if (input.optBoolean("long", false)) a.longTap(x, y) else a.tap(x, y)
                settle()
                ToolResult(if (ok) "Нажала." else "Нажатие не прошло.")
            }
            "type_text" -> withA11y(true) { a ->
                val id = if (input.has("id")) input.optInt("id", -1) else -1
                ToolResult(if (a.typeText(id, input.optString("text"))) "Текст введён." else "Не нашла поле ввода.")
            }
            "scroll" -> withA11y(false) { a ->
                a.scroll(input.optString("direction", "down"))
                settle()
                ToolResult("Пролистала.")
            }
            "press" -> withA11y(false) { a ->
                if (a.press(input.optString("key"))) { settle(); ToolResult("Готово.") } else ToolResult("Не получилось.")
            }
            "wait" -> {
                Thread.sleep((input.optDouble("seconds", 1.0).coerceIn(0.2, 8.0) * 1000).toLong())
                ToolResult("Подождала.")
            }
            "set_alarm" -> {
                val i = Intent(AlarmClock.ACTION_SET_ALARM)
                    .putExtra(AlarmClock.EXTRA_HOUR, input.optInt("hour"))
                    .putExtra(AlarmClock.EXTRA_MINUTES, input.optInt("minute"))
                    .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
                input.optString("label").takeIf { it.isNotBlank() }?.let { i.putExtra(AlarmClock.EXTRA_MESSAGE, it) }
                launch(i)
                ToolResult("Будильник на %02d:%02d поставлен.".format(input.optInt("hour"), input.optInt("minute")))
            }
            "set_timer" -> {
                val i = Intent(AlarmClock.ACTION_SET_TIMER)
                    .putExtra(AlarmClock.EXTRA_LENGTH, input.optInt("seconds"))
                    .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
                input.optString("label").takeIf { it.isNotBlank() }?.let { i.putExtra(AlarmClock.EXTRA_MESSAGE, it) }
                launch(i)
                ToolResult("Таймер запущен.")
            }
            "call" -> call(input.optString("who"))
            "sms" -> sms(input.optString("who"), input.optString("text"))
            "open_url" -> {
                var u = input.optString("url").trim()
                if (!u.startsWith("http")) u = "https://$u"
                launch(Intent(Intent.ACTION_VIEW, Uri.parse(u)))
                settle()
                ToolResult("Открыла ссылку.")
            }
            "web_search" -> {
                val q = input.optString("query")
                val ok = launch(Intent(Intent.ACTION_WEB_SEARCH).putExtra(SearchManager.QUERY, q))
                if (!ok) launch(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/search?q=" + Uri.encode(q))))
                settle()
                ToolResult("Открыла поиск: $q")
            }
            "media" -> media(input.optString("action"))
            "flashlight" -> flashlight(input.optBoolean("on", true))
            else -> ToolResult("Неизвестный инструмент: $name")
        }
    } catch (e: Exception) {
        ToolResult("Ошибка: ${e.javaClass.simpleName}: ${e.message}")
    }

    private inline fun withA11y(checkPrivate: Boolean, block: (AgentAccessibilityService) -> ToolResult): ToolResult {
        val a = AgentAccessibilityService.instance
            ?: return ToolResult("Служба спецвозможностей Юки выключена — без неё я не вижу экран и не могу нажимать. Попроси включить её в настройках приложения Юки.")
        if (checkPrivate) {
            val pkg = a.currentPackage()
            if (pkg.isNotEmpty() && prefs.blockedList().contains(pkg)) {
                return ToolResult("Сейчас открыто приватное приложение ($pkg). Смотреть и нажимать в нём запрещено настройками.")
            }
        }
        return block(a)
    }

    private fun settle() = Thread.sleep(900)

    private fun launch(i: Intent): Boolean = try {
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ctx.startActivity(i)
        true
    } catch (e: Exception) {
        false
    }

    // ---------- Приложения ----------

    private fun apps(): List<Pair<String, String>> {
        val pm = ctx.packageManager
        val i = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return pm.queryIntentActivities(i, 0)
            .map { it.loadLabel(pm).toString() to it.activityInfo.packageName }
            .distinctBy { it.second }
            .sortedBy { it.first.lowercase() }
    }

    private fun openApp(name: String): ToolResult {
        val q = norm(name)
        if (q.isEmpty()) return ToolResult("Не указано приложение.")
        val list = apps()
        val hit = list.firstOrNull { norm(it.first) == q }
            ?: list.firstOrNull { norm(it.first).startsWith(q) || q.startsWith(norm(it.first)) && norm(it.first).length >= 3 }
            ?: list.firstOrNull { norm(it.first).contains(q) }
            ?: list.firstOrNull { it.second.lowercase().contains(q) }
            ?: return ToolResult("Не нашла «$name». Установлены: " + list.joinToString(", ") { it.first })
        val intent = ctx.packageManager.getLaunchIntentForPackage(hit.second)
            ?: return ToolResult("Не получается открыть ${hit.first}.")
        launch(intent)
        Thread.sleep(1500)
        return ToolResult("Открыла ${hit.first}.")
    }

    private val translit = mapOf(
        'а' to "a", 'б' to "b", 'в' to "v", 'г' to "g", 'д' to "d", 'е' to "e", 'ё' to "e", 'ж' to "zh",
        'з' to "z", 'и' to "i", 'й' to "i", 'к' to "k", 'л' to "l", 'м' to "m", 'н' to "n", 'о' to "o",
        'п' to "p", 'р' to "r", 'с' to "s", 'т' to "t", 'у' to "u", 'ф' to "f", 'х' to "h", 'ц' to "c",
        'ч' to "ch", 'ш' to "sh", 'щ' to "sch", 'ъ' to "", 'ы' to "y", 'ь' to "", 'э' to "e", 'ю' to "yu", 'я' to "ya"
    )

    private fun norm(s: String): String {
        val sb = StringBuilder()
        for (ch in s.lowercase()) sb.append(translit[ch] ?: ch.toString())
        return sb.toString().replace(Regex("[^a-z0-9]"), "")
    }

    // ---------- Экран ----------

    private fun lookAtScreen(a: AgentAccessibilityService): ToolResult {
        if (Build.VERSION.SDK_INT < 30) return ToolResult("Скриншоты работают с Android 11. Используй read_screen.")
        setOverlayVisible(false)
        Thread.sleep(180)
        val bmp = (try { a.screenshot() } finally { setOverlayVisible(true) })
            ?: return ToolResult("Не получилось сделать снимок (их можно делать не чаще раза в секунду). Попробуй ещё раз или read_screen.")
        val w = SHOT_W
        val h = (bmp.height * w / bmp.width.toFloat()).toInt()
        val small = Bitmap.createScaledBitmap(bmp, w, h, true)
        val bos = ByteArrayOutputStream()
        small.compress(Bitmap.CompressFormat.JPEG, 70, bos)
        return ToolResult(
            "Снимок экрана ${w}x$h. Координаты на нём подходят для tap_xy.",
            Base64.encodeToString(bos.toByteArray(), Base64.NO_WRAP)
        )
    }

    // ---------- Связь ----------

    private fun granted(p: String) = ctx.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED

    private fun findNumber(who: String): Pair<String, String>? {
        val w = who.trim()
        if (Regex("^[+\\d\\s()\\-]{3,}$").matches(w)) return w to w.filter { it.isDigit() || it == '+' }
        if (!granted(Manifest.permission.READ_CONTACTS)) return null
        val tries = linkedSetOf(w, w.take(maxOf(3, w.length - 1)), w.take(maxOf(3, w.length - 2)))
        for (q in tries) {
            ctx.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                arrayOf(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME, ContactsContract.CommonDataKinds.Phone.NUMBER),
                "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?",
                arrayOf("%$q%"),
                null
            )?.use { c ->
                if (c.moveToFirst()) return (c.getString(0) ?: q) to (c.getString(1) ?: "")
            }
        }
        return null
    }

    private fun call(who: String): ToolResult {
        val c = findNumber(who)
            ?: return ToolResult("Не нашла контакт «$who»" + if (!granted(Manifest.permission.READ_CONTACTS)) " (нет доступа к контактам)." else ".")
        val direct = granted(Manifest.permission.CALL_PHONE)
        launch(Intent(if (direct) Intent.ACTION_CALL else Intent.ACTION_DIAL, Uri.parse("tel:" + Uri.encode(c.second))))
        return ToolResult(if (direct) "Звоню: ${c.first} (${c.second})." else "Открыла набор номера ${c.first}, нужно нажать вызов.")
    }

    private fun sms(who: String, text: String): ToolResult {
        val c = findNumber(who) ?: return ToolResult("Не нашла контакт «$who».")
        launch(Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:" + Uri.encode(c.second))).putExtra("sms_body", text))
        settle()
        return ToolResult("Открыла сообщение для ${c.first} с текстом. Чтобы отправить — read_screen и tap по кнопке отправки (только если пользователь подтвердил).")
    }

    // ---------- Звук и прочее ----------

    private fun media(action: String): ToolResult {
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        fun key(code: Int) {
            am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
            am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
        }
        when (action) {
            "play_pause" -> key(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
            "next" -> key(KeyEvent.KEYCODE_MEDIA_NEXT)
            "previous" -> key(KeyEvent.KEYCODE_MEDIA_PREVIOUS)
            "volume_up" -> repeat(2) { am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_RAISE, AudioManager.FLAG_SHOW_UI) }
            "volume_down" -> repeat(2) { am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI) }
            "mute" -> am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_MUTE, AudioManager.FLAG_SHOW_UI)
            "unmute" -> am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_UNMUTE, AudioManager.FLAG_SHOW_UI)
            else -> return ToolResult("Не знаю такое действие.")
        }
        return ToolResult("Готово.")
    }

    private fun flashlight(on: Boolean): ToolResult {
        val cm = ctx.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val id = cm.cameraIdList.firstOrNull {
            cm.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
        } ?: return ToolResult("На этом телефоне нет вспышки.")
        cm.setTorchMode(id, on)
        return ToolResult(if (on) "Фонарик включён." else "Фонарик выключен.")
    }

    /** Короткая сводка о телефоне — подклеивается к каждой просьбе. */
    fun deviceContext(): String {
        val time = SimpleDateFormat("EEEE, d MMMM yyyy, HH:mm", Locale("ru")).format(Date())
        val bm = ctx.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val battery = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        val app = AgentAccessibilityService.instance?.currentPackage()?.ifEmpty { null } ?: "неизвестно"
        val a11y = if (AgentAccessibilityService.instance != null) "включены" else "ВЫКЛЮЧЕНЫ"
        return "[Сейчас $time. Заряд $battery%. Открыто приложение: $app. Спецвозможности: $a11y.]"
    }
}
