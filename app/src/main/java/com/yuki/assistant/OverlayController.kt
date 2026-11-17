package com.yuki.assistant

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.PointF
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sign
import kotlin.math.sin
import kotlin.random.Random

/** Окно поверх всех приложений: Юки + облачко с репликой. Её можно таскать, она сама гуляет. */
class OverlayController(
    private val ctx: Context,
    private val prefs: Prefs,
    private val onTap: () -> Unit,
    private val onLongPress: () -> Unit,
) {
    private val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val h = Handler(Looper.getMainLooper())
    private val dm = ctx.resources.displayMetrics
    private fun dp(v: Float) = (v * dm.density).toInt()

    val avatar = AvatarView(ctx)

    private val bubble = TextView(ctx).apply {
        setTextColor(Color.parseColor("#3B3050"))
        textSize = 14f
        maxWidth = dp(230f)
        setPadding(dp(12f), dp(8f), dp(12f), dp(8f))
        background = GradientDrawable().apply {
            setColor(Color.parseColor("#F2FFFFFF"))
            cornerRadius = dp(16f).toFloat()
            setStroke(dp(1.5f), Color.parseColor("#B9AEE0"))
        }
        visibility = View.GONE
    }

    private val root = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        addView(bubble, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dp(2f) })
        addView(avatar, LinearLayout.LayoutParams(dp(120f), dp(156f)))
    }

    private val lp = WindowManager.LayoutParams(
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
        PixelFormat.TRANSLUCENT
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        x = dm.widthPixels - dp(140f)
        y = (dm.heightPixels * 0.55f).toInt()
    }

    /** Пока занята (слушает/думает/говорит) — не гуляет. */
    var busy = false
    private var attached = false
    private var dragging = false
    private var lastTouch = 0L
    private var anim: ValueAnimator? = null

    private val hideBubble = Runnable { bubble.visibility = View.GONE }

    private val wander = object : Runnable {
        override fun run() {
            if (prefs.wander && !busy && !dragging && SystemClock.uptimeMillis() - lastTouch > 8000) randomWalk()
            h.postDelayed(this, 9000L + Random.nextLong(9000L))
        }
    }

    fun show() {
        if (attached) return
        wm.addView(root, lp)
        attached = true
        setupTouch()
        // Облачко растёт вверх, а Юки остаётся на месте.
        root.addOnLayoutChangeListener { _, l, t, r, b, ol, ot, oRight, ob ->
            if (oRight - ol == 0) return@addOnLayoutChangeListener
            val dw = (r - l) - (oRight - ol)
            val dh = (b - t) - (ob - ot)
            if (dw != 0 || dh != 0) {
                lp.x -= dw / 2
                lp.y -= dh
                h.post { clamp() }
            }
        }
        h.postDelayed(wander, 6000)
    }

    fun remove() {
        h.removeCallbacksAndMessages(null)
        anim?.cancel()
        if (attached) {
            try { wm.removeView(root) } catch (_: Exception) {}
            attached = false
        }
    }

    /** Прячется на время скриншота, чтобы не загораживать экран. */
    fun setShotHidden(hidden: Boolean) {
        root.visibility = if (hidden) View.INVISIBLE else View.VISIBLE
    }

    fun say(text: String?, holdMs: Long = 7000) {
        h.removeCallbacks(hideBubble)
        if (text.isNullOrBlank()) {
            bubble.visibility = View.GONE
            return
        }
        bubble.text = text
        bubble.visibility = View.VISIBLE
        if (holdMs > 0) h.postDelayed(hideBubble, holdMs)
    }

    private fun clamp() {
        if (!attached) return
        lp.x = lp.x.coerceIn(0, max(0, dm.widthPixels - root.width))
        lp.y = lp.y.coerceIn(0, max(0, dm.heightPixels - root.height))
        update()
    }

    private fun update() {
        if (!attached) return
        try { wm.updateViewLayout(root, lp) } catch (_: Exception) {}
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupTouch() {
        var downX = 0f
        var downY = 0f
        var startX = 0
        var startY = 0
        var moved = false
        var longFired = false
        val longPress = Runnable {
            if (!moved) {
                longFired = true
                onLongPress()
            }
        }
        root.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX; downY = e.rawY
                    startX = lp.x; startY = lp.y
                    moved = false; longFired = false
                    anim?.cancel()
                    dragging = true
                    lastTouch = SystemClock.uptimeMillis()
                    h.postDelayed(longPress, 650)
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX
                    val dy = e.rawY - downY
                    if (!moved && hypot(dx, dy) > dp(8f)) {
                        moved = true
                        h.removeCallbacks(longPress)
                        avatar.held = true
                    }
                    if (moved) {
                        lp.x = startX + dx.toInt()
                        lp.y = startY + dy.toInt()
                        update()
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    h.removeCallbacks(longPress)
                    dragging = false
                    avatar.held = false
                    lastTouch = SystemClock.uptimeMillis()
                    if (moved) clamp()
                    else if (!longFired && e.actionMasked == MotionEvent.ACTION_UP) onTap()
                    true
                }
                else -> false
            }
        }
    }

    private fun randomWalk() {
        if (!attached) return
        val w = max(root.width, dp(120f))
        val hh = max(root.height, dp(156f))
        val minY = (dm.heightPixels * 0.12f).toInt()
        val maxY = max(minY + 1, dm.heightPixels - hh - dp(40f))
        val tx = Random.nextInt(0, max(1, dm.widthPixels - w))
        val ty = Random.nextInt(minY, maxY)
        val sx = lp.x
        val sy = lp.y
        val dist = hypot((tx - sx).toFloat(), (ty - sy).toFloat())
        val hops = max(1, (dist / dp(60f)).toInt())
        avatar.lookOverride = PointF(sign((tx - sx).toFloat()) * 0.9f, 0.1f)
        anim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = (dist / dm.density * 6f).toLong().coerceIn(1200L, 4000L)
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener {
                val f = it.animatedValue as Float
                val hop = abs(sin(f * PI * hops)).toFloat() * dp(10f)
                lp.x = (sx + (tx - sx) * f).toInt()
                lp.y = (sy + (ty - sy) * f - hop).toInt()
                update()
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    avatar.lookOverride = null
                }
            })
            start()
        }
    }
}
