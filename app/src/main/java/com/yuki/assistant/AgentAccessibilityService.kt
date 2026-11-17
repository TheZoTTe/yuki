package com.yuki.assistant

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.annotation.TargetApi
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** «Руки и глаза» Юки: читает экран, нажимает, листает, вводит текст, делает скриншоты. */
class AgentAccessibilityService : AccessibilityService() {

    companion object {
        @Volatile var instance: AgentAccessibilityService? = null
    }

    private var lastNodes: List<AccessibilityNodeInfo> = emptyList()

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    fun currentPackage(): String = rootInActiveWindow?.packageName?.toString() ?: ""

    /** Множитель: координаты «как на скриншоте шириной 720» → реальные пиксели. */
    fun scale(): Float = resources.displayMetrics.widthPixels / PhoneTools.SHOT_W.toFloat()

    fun dumpScreen(): String {
        val root = rootInActiveWindow ?: return "Экран сейчас недоступен (попробуй ещё раз через секунду)."
        val out = ArrayList<AccessibilityNodeInfo>()
        collect(root, out, 0)
        lastNodes = out
        val k = scale()
        val sb = StringBuilder("Приложение: ${root.packageName}. Элементы (номер, тип, текст, свойства, центр xy):\n")
        val r = Rect()
        out.forEachIndexed { i, n ->
            n.getBoundsInScreen(r)
            val label = listOfNotNull(n.text?.toString(), n.contentDescription?.toString(), n.hintText?.toString())
                .map { it.replace('\n', ' ').trim() }
                .filter { it.isNotEmpty() }
                .distinct()
                .joinToString(" | ")
                .take(90)
            val cls = n.className?.toString()?.substringAfterLast('.') ?: ""
            val flags = ArrayList<String>()
            if (n.isClickable) flags.add("нажимается")
            if (n.isEditable) flags.add("поле ввода")
            if (n.isScrollable) flags.add("листается")
            if (n.isCheckable) flags.add(if (n.isChecked) "вкл" else "выкл")
            if (n.isFocused) flags.add("в фокусе")
            sb.append("[").append(i).append("] ").append(cls)
            if (label.isNotEmpty()) sb.append(" «").append(label).append("»")
            if (flags.isNotEmpty()) sb.append(" (").append(flags.joinToString(", ")).append(")")
            sb.append(" xy=").append((r.centerX() / k).toInt()).append(",").append((r.centerY() / k).toInt())
            sb.append('\n')
        }
        if (out.isEmpty()) sb.append("(текстовых элементов нет — используй look_at_screen)")
        return sb.toString().take(14000)
    }

    private fun collect(n: AccessibilityNodeInfo?, out: MutableList<AccessibilityNodeInfo>, depth: Int) {
        if (n == null || out.size >= 180 || depth > 45) return
        if (!n.isVisibleToUser) return
        val hasText = !n.text.isNullOrBlank() || !n.contentDescription.isNullOrBlank()
        if (hasText || n.isClickable || n.isEditable || n.isScrollable) out.add(n)
        for (i in 0 until n.childCount) collect(n.getChild(i), out, depth + 1)
    }

    fun clickNode(id: Int): Boolean {
        val node = lastNodes.getOrNull(id) ?: return false
        var c: AccessibilityNodeInfo? = node
        var depth = 0
        while (c != null && !c.isClickable && depth < 6) { c = c.parent; depth++ }
        if (c != null && c.isClickable && c.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
        val r = Rect()
        node.getBoundsInScreen(r)
        return tap(r.exactCenterX(), r.exactCenterY())
    }

    fun typeText(id: Int, text: String): Boolean {
        var n: AccessibilityNodeInfo? = if (id >= 0) lastNodes.getOrNull(id)
        else rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: findFirstEditable(rootInActiveWindow)
        if (n == null) return false
        if (!n.isEditable) {
            n.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            Thread.sleep(400)
            n = rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: n
        }
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        return n.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    private fun findFirstEditable(n: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (n == null) return null
        if (n.isEditable && n.isVisibleToUser) return n
        for (i in 0 until n.childCount) {
            val f = findFirstEditable(n.getChild(i))
            if (f != null) return f
        }
        return null
    }

    fun tap(x: Float, y: Float): Boolean = gesture(Path().apply { moveTo(x, y) }, 60)

    fun longTap(x: Float, y: Float): Boolean = gesture(Path().apply { moveTo(x, y) }, 900)

    fun swipe(x1: Float, y1: Float, x2: Float, y2: Float, dur: Long = 380): Boolean =
        gesture(Path().apply { moveTo(x1, y1); lineTo(x2, y2) }, dur)

    fun scroll(direction: String): Boolean {
        val w = resources.displayMetrics.widthPixels.toFloat()
        val h = resources.displayMetrics.heightPixels.toFloat()
        return when (direction) {
            "up" -> swipe(w / 2, h * 0.3f, w / 2, h * 0.72f)
            "left" -> swipe(w * 0.2f, h / 2, w * 0.85f, h / 2)
            "right" -> swipe(w * 0.85f, h / 2, w * 0.15f, h / 2)
            else -> swipe(w / 2, h * 0.72f, w / 2, h * 0.3f)
        }
    }

    fun press(key: String): Boolean {
        val action = when (key) {
            "back" -> AccessibilityService.GLOBAL_ACTION_BACK
            "home" -> AccessibilityService.GLOBAL_ACTION_HOME
            "recents" -> AccessibilityService.GLOBAL_ACTION_RECENTS
            "notifications" -> AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS
            "quick_settings" -> AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS
            "lock" -> if (Build.VERSION.SDK_INT >= 28) AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN else return false
            else -> return false
        }
        return performGlobalAction(action)
    }

    private fun gesture(p: Path, dur: Long): Boolean {
        val latch = CountDownLatch(1)
        var ok = false
        val g = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(p, 0, dur))
            .build()
        val sent = dispatchGesture(g, object : AccessibilityService.GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) { ok = true; latch.countDown() }
            override fun onCancelled(gestureDescription: GestureDescription?) { latch.countDown() }
        }, null)
        if (!sent) return false
        latch.await(dur + 1500, TimeUnit.MILLISECONDS)
        return ok
    }

    @TargetApi(30)
    fun screenshot(): Bitmap? {
        val latch = CountDownLatch(1)
        var out: Bitmap? = null
        takeScreenshot(Display.DEFAULT_DISPLAY, mainExecutor, object : AccessibilityService.TakeScreenshotCallback {
            override fun onSuccess(screenshot: AccessibilityService.ScreenshotResult) {
                try {
                    val hb = screenshot.hardwareBuffer
                    val b = Bitmap.wrapHardwareBuffer(hb, screenshot.colorSpace)
                    out = b?.copy(Bitmap.Config.ARGB_8888, false)
                    hb.close()
                } catch (_: Exception) {}
                latch.countDown()
            }
            override fun onFailure(errorCode: Int) { latch.countDown() }
        })
        latch.await(4, TimeUnit.SECONDS)
        return out
    }
}
