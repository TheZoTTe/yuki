package com.yuki.assistant

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.os.SystemClock
import android.view.View
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.sin

/**
 * Юки: беловолосая тихоня-дандэрэ, нарисованная векторами прямо в коде.
 * Моргает, дышит, качает головой, водит глазами, шевелит губами при речи
 * и меняет выражение лица под эмоцию.
 * Внутренняя система координат — 200 x 260.
 */
class AvatarView(context: Context) : View(context) {

    var emotion: Emotion = Emotion.NEUTRAL
    var speaking = false
    /** Пока её тащат пальцем — удивляется. */
    var held = false
    /** Внешнее управление взглядом (например, пока идёт). null — смотрит сама. */
    var lookOverride: PointF? = null

    private val t0 = SystemClock.uptimeMillis()
    private var lookX = 0f
    private var lookY = 0f
    private var glanceX = 0f
    private var glanceY = 0f
    private var nextGlance = 0L
    private var nextBlink = 0L
    private var blinkEnd = 0L

    // Палитра
    private val hair = Color.parseColor("#F7F6FC")
    private val hairBack = Color.parseColor("#DDD7F0")
    private val hairShade = Color.parseColor("#CFC7E8")
    private val hairLine = Color.parseColor("#A99FCB")
    private val skin = Color.parseColor("#FFEEE6")
    private val skinShade = Color.parseColor("#F5D3C7")
    private val sweater = Color.parseColor("#7E95BF")
    private val sweaterShade = Color.parseColor("#6880AA")
    private val eyeLine = Color.parseColor("#3B3050")
    private val irisTop = Color.parseColor("#6D4FC0")
    private val irisBottom = Color.parseColor("#EA8FBE")
    private val pupil = Color.parseColor("#2A1E3D")
    private val lidShadow = Color.parseColor("#557C6AA8")
    private val brow = Color.parseColor("#B3AACB")
    private val blush = Color.parseColor("#FF8DA6")
    private val mouthLine = Color.parseColor("#B9576A")
    private val mouthFill = Color.parseColor("#C9606F")
    private val tongue = Color.parseColor("#F29AA5")
    private val pin = Color.parseColor("#79C2F2")
    private val fxColor = Color.parseColor("#8A7FB5")

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.DEFAULT_BOLD
    }
    private val path = Path()
    private val path2 = Path()
    private val rect = RectF()

    private fun f(c: Int): Paint { fill.shader = null; fill.color = c; return fill }
    private fun s(c: Int, w: Float): Paint { line.color = c; line.strokeWidth = w; return line }

    private val shown: Emotion get() = if (held) Emotion.SURPRISED else emotion

    override fun onDraw(c: Canvas) {
        val now = SystemClock.uptimeMillis()
        val t = (now - t0) / 1000f
        updateEyes(now)

        val sc = min(width / 200f, height / 260f)
        c.save()
        c.translate((width - 200f * sc) / 2f, height - 260f * sc)
        c.scale(sc, sc)

        val breathe = sin(t * 2.1f)
        val tilt = sin(t * 0.6f) * 2f + when (shown) {
            Emotion.THINKING -> 7f
            Emotion.SHY -> -5f
            Emotion.SAD -> -3f
            Emotion.LISTENING -> 4f
            Emotion.SLEEPY -> 6f
            else -> 0f
        }
        val sway = sin(t * 1.3f) * 2.5f

        c.save(); head(c, breathe, tilt); drawBackHair(c, sway); c.restore()
        drawBody(c, breathe)
        c.save(); head(c, breathe, tilt)
        drawFace(c)
        drawEyes(c, now)
        drawBlush(c)
        drawMouth(c, t)
        drawBangs(c, t)
        drawBrows(c)
        drawLocks(c, sway)
        drawPin(c, t)
        c.restore()
        drawEffects(c, t)

        c.restore()
        postInvalidateDelayed(33)
    }

    private fun head(c: Canvas, breathe: Float, tilt: Float) {
        c.translate(0f, breathe * 1.2f)
        c.rotate(tilt, 100f, 190f)
    }

    private fun updateEyes(now: Long) {
        if (now > nextBlink) {
            blinkEnd = now + 130
            nextBlink = now + 2500 + (Math.random() * 3500).toLong()
        }
        if (now > nextGlance) {
            glanceX = ((Math.random() * 2 - 1) * 0.7).toFloat()
            glanceY = ((Math.random() * 2 - 1) * 0.4).toFloat()
            nextGlance = now + 1500 + (Math.random() * 3000).toLong()
        }
        val o = lookOverride
        val (tx, ty) = if (o != null) o.x to o.y else when (shown) {
            Emotion.SHY -> -0.7f to 0.7f
            Emotion.THINKING -> -0.6f to -0.8f
            Emotion.SAD -> 0f to 0.8f
            Emotion.LISTENING, Emotion.SURPRISED -> 0f to 0f
            Emotion.SLEEPY -> 0f to 0.5f
            else -> glanceX to glanceY
        }
        lookX += (tx - lookX) * 0.15f
        lookY += (ty - lookY) * 0.15f
    }

    private fun drawBackHair(c: Canvas, sway: Float) {
        path.reset()
        path.moveTo(100f, 22f)
        path.cubicTo(28f, 22f, 20f, 110f, 28f + sway, 242f)
        path.quadTo(100f, 252f, 172f + sway, 242f)
        path.cubicTo(180f, 110f, 172f, 22f, 100f, 22f)
        path.close()
        c.drawPath(path, f(hairBack))
        c.drawPath(path, s(hairLine, 1.4f))
    }

    private fun drawBody(c: Canvas, breathe: Float) {
        c.save()
        c.scale(1f, 1f + breathe * 0.008f, 100f, 260f)
        // шея
        c.drawRect(90f, 158f, 110f, 206f, f(skinShade))
        // свитер
        path.reset()
        path.moveTo(24f, 262f)
        path.cubicTo(30f, 214f, 62f, 200f, 100f, 200f)
        path.cubicTo(138f, 200f, 170f, 214f, 176f, 262f)
        path.close()
        c.drawPath(path, f(sweater))
        // вырез
        path.reset()
        path.moveTo(86f, 200f); path.lineTo(100f, 218f); path.lineTo(114f, 200f); path.close()
        c.drawPath(path, f(skinShade))
        c.drawLine(86f, 200f, 100f, 219f, s(Color.WHITE, 3f))
        c.drawLine(114f, 200f, 100f, 219f, s(Color.WHITE, 3f))
        // шнурки худи
        c.drawLine(94f, 220f, 92f, 246f, s(Color.WHITE, 2f))
        c.drawLine(106f, 220f, 108f, 246f, s(Color.WHITE, 2f))
        c.drawCircle(92f, 248f, 2.2f, f(Color.WHITE))
        c.drawCircle(108f, 248f, 2.2f, f(Color.WHITE))
        // складки
        c.drawLine(60f, 236f, 64f, 262f, s(sweaterShade, 1.6f))
        c.drawLine(140f, 236f, 136f, 262f, s(sweaterShade, 1.6f))
        c.restore()
    }

    private fun drawFace(c: Canvas) {
        path.reset()
        path.moveTo(56f, 92f)
        path.cubicTo(56f, 142f, 80f, 176f, 100f, 179f)
        path.cubicTo(120f, 176f, 144f, 142f, 144f, 92f)
        path.cubicTo(144f, 52f, 56f, 52f, 56f, 92f)
        path.close()
        c.drawPath(path, f(skin))
        // тень от чёлки
        rect.set(60f, 102f, 140f, 116f)
        fill.color = skinShade; fill.alpha = 110
        c.drawOval(rect, fill)
        // носик
        c.drawLine(99.5f, 140f, 101f, 141.5f, s(skinShade, 1.8f))
    }

    private fun eyeOpen(): Float = when (shown) {
        Emotion.LISTENING -> 1.0f
        Emotion.SURPRISED -> 1.1f
        Emotion.SLEEPY -> 0.38f
        Emotion.SHY, Emotion.SAD -> 0.74f
        Emotion.ANNOYED -> 0.58f
        else -> 0.84f   // дандэрэ: чуть сонный, спокойный взгляд
    }

    private fun drawEyes(c: Canvas, now: Long) {
        val blinking = now < blinkEnd
        for (side in 0..1) {
            val cx = if (side == 0) 79f else 121f
            val cy = 122f
            val out = if (side == 0) -1f else 1f   // направление к внешнему уголку

            if (shown == Emotion.HAPPY) {
                // глазки-дуги ^ ^
                path.reset()
                path.moveTo(cx - 11f, cy + 4f)
                path.quadTo(cx, cy - 9f, cx + 11f, cy + 4f)
                c.drawPath(path, s(eyeLine, 3f))
                continue
            }
            if (blinking) {
                path.reset()
                path.moveTo(cx - 12f, cy + 6f)
                path.quadTo(cx, cy + 11f, cx + 12f, cy + 6f)
                c.drawPath(path, s(eyeLine, 2.8f))
                continue
            }

            val bottom = cy + 15f
            val top = bottom - 31f * eyeOpen()
            rect.set(cx - 13f, top, cx + 13f, bottom)

            c.save()
            path.reset(); path.addOval(rect, Path.Direction.CW)
            c.clipPath(path)
            c.drawRect(rect, f(Color.WHITE))
            val ix = cx + lookX * 4f
            val iy = cy + 2f + lookY * 3f
            fill.color = Color.WHITE
            fill.shader = LinearGradient(0f, iy - 12.5f, 0f, iy + 12.5f, irisTop, irisBottom, Shader.TileMode.CLAMP)
            c.drawOval(ix - 10.5f, iy - 12.5f, ix + 10.5f, iy + 12.5f, fill)
            fill.shader = null
            c.drawOval(ix - 5f, iy - 6f, ix + 5f, iy + 6f, f(pupil))
            c.drawRect(cx - 13f, top, cx + 13f, top + 4.5f, f(lidShadow))
            c.drawCircle(ix - 3.5f, iy - 5f, 3f, f(Color.WHITE))
            c.drawCircle(ix + 3.5f, iy + 4f, 1.4f, f(Color.WHITE))
            c.restore()

            // ресницы
            path.reset()
            path.moveTo(cx - out * 13f, top + 3.5f)
            path.quadTo(cx, top - 3f, cx + out * 14f, top + 1.5f)
            path.lineTo(cx + out * 17.5f, top - 2f)
            c.drawPath(path, s(eyeLine, 3.2f))
            c.drawLine(cx + out * 7f, bottom + 0.5f, cx + out * 11.5f, bottom - 1.5f, s(eyeLine, 1.3f))
        }
    }

    private fun drawBrows(c: Canvas) {
        val raise = when (shown) {
            Emotion.SURPRISED -> -5f
            Emotion.LISTENING -> -2f
            Emotion.ANNOYED -> 2f
            else -> 0f
        }
        val inner = when (shown) {
            Emotion.SAD, Emotion.SHY -> -4f
            Emotion.ANNOYED -> 4f
            Emotion.THINKING -> -2f
            else -> 0f
        }
        for (side in 0..1) {
            val cx = if (side == 0) 79f else 121f
            val out = if (side == 0) -1f else 1f
            line.alpha = 255
            c.drawLine(cx + out * 10f, 101f + raise, cx - out * 8f, 101f + raise + inner, s(brow, 2.2f))
        }
    }

    private fun drawBlush(c: Canvas) {
        val b = when (shown) {
            Emotion.SHY -> 1f
            Emotion.HAPPY -> 0.6f
            Emotion.SURPRISED -> 0.4f
            else -> 0.25f
        }
        fill.shader = null
        fill.color = blush
        fill.alpha = (b * 150).toInt()
        c.drawOval(61f, 136f, 85f, 146f, fill)
        c.drawOval(115f, 136f, 139f, 146f, fill)
        if (shown == Emotion.SHY) {
            for (k in 0..2) {
                c.drawLine(66f + k * 5f, 144f, 69f + k * 5f, 137f, s(mouthLine, 1.1f))
                c.drawLine(120f + k * 5f, 144f, 123f + k * 5f, 137f, s(mouthLine, 1.1f))
            }
        }
    }

    private fun drawMouth(c: Canvas, t: Float) {
        val m = s(mouthLine, 2f)
        if (speaking) {
            val o = (0.35f + 0.65f * abs(sin(t * 11f))) * (0.6f + 0.4f * abs(sin(t * 3.7f)))
            val h = 2f + 7f * o
            c.drawOval(95.5f, 153f, 104.5f, 153f + h, f(mouthFill))
            if (h > 5f) c.drawOval(97f, 153f + h * 0.55f, 103f, 153f + h, f(tongue))
            return
        }
        path.reset()
        when (shown) {
            Emotion.HAPPY -> {
                path.moveTo(93f, 153f); path.quadTo(100f, 162f, 107f, 153f); path.close()
                c.drawPath(path, f(mouthFill))
                return
            }
            Emotion.SHY -> {
                path.moveTo(95f, 156f)
                path.quadTo(97.5f, 154f, 100f, 156f)
                path.quadTo(102.5f, 158f, 105f, 156f)
            }
            Emotion.SURPRISED -> {
                c.drawOval(97f, 152f, 103f, 160f, f(mouthFill)); return
            }
            Emotion.SAD -> { path.moveTo(95f, 158f); path.quadTo(100f, 153f, 105f, 158f) }
            Emotion.ANNOYED -> { path.moveTo(95f, 157f); path.lineTo(105f, 155f) }
            Emotion.THINKING -> { path.moveTo(99f, 156f); path.quadTo(103f, 157.5f, 107f, 155f) }
            Emotion.SLEEPY -> { c.drawOval(98.5f, 155f, 101.5f, 158f, f(mouthFill)); return }
            else -> { path.moveTo(96f, 156f); path.quadTo(100f, 158f, 104f, 156f) }
        }
        c.drawPath(path, m)
    }

    private fun drawBangs(c: Canvas, t: Float) {
        // хохолок-ахоге
        c.save()
        c.rotate(sin(t * 2.5f) * 8f, 100f, 34f)
        path.reset()
        path.moveTo(98f, 36f)
        path.cubicTo(92f, 14f, 112f, 6f, 118f, 16f)
        path.cubicTo(110f, 12f, 104f, 20f, 103f, 36f)
        path.close()
        c.drawPath(path, f(hair))
        c.drawPath(path, s(hairLine, 1.3f))
        c.restore()

        path.reset()
        path.moveTo(50f, 112f)
        path.cubicTo(38f, 30f, 162f, 30f, 150f, 112f)
        path.lineTo(144f, 95f)
        path.lineTo(138f, 110f)
        path.lineTo(126f, 91f)
        path.lineTo(115f, 108f)
        path.lineTo(101f, 89f)
        path.lineTo(89f, 107f)
        path.lineTo(77f, 92f)
        path.lineTo(65f, 109f)
        path.lineTo(59f, 96f)
        path.close()
        c.drawPath(path, f(hair))
        c.drawPath(path, s(hairLine, 1.4f))

        // пряди-штрихи
        path2.reset()
        path2.moveTo(100f, 42f); path2.quadTo(97f, 62f, 101f, 80f)
        path2.moveTo(80f, 48f); path2.quadTo(74f, 66f, 77f, 84f)
        path2.moveTo(122f, 48f); path2.quadTo(127f, 66f, 125f, 82f)
        path2.moveTo(64f, 62f); path2.quadTo(60f, 78f, 61f, 89f)
        path2.moveTo(138f, 62f); path2.quadTo(142f, 78f, 142f, 88f)
        c.drawPath(path2, s(hairShade, 1.8f))
        // блик «ангельское кольцо»
        path2.reset()
        path2.moveTo(68f, 58f); path2.quadTo(100f, 44f, 132f, 58f)
        c.drawPath(path2, s(Color.WHITE, 3.5f))
    }

    private fun drawLocks(c: Canvas, sway: Float) {
        for (m in listOf(false, true)) {
            fun x(v: Float) = if (m) 200f - v else v
            path.reset()
            path.moveTo(x(56f), 84f)
            path.cubicTo(x(42f), 120f, x(40f + sway), 170f, x(48f + sway), 216f)
            path.lineTo(x(56f + sway), 206f)
            path.lineTo(x(61f + sway), 214f)
            path.cubicTo(x(64f), 170f, x(60f), 128f, x(66f), 98f)
            path.close()
            c.drawPath(path, f(hair))
            c.drawPath(path, s(hairLine, 1.3f))
            c.drawLine(x(54f), 120f, x(51f + sway * 0.5f), 180f, s(hairShade, 1.5f))
        }
    }

    private fun drawPin(c: Canvas, t: Float) {
        // заколка-снежинка — фишка Юки
        c.save()
        c.rotate(t * 20f, 136f, 70f)
        val p = s(pin, 2.2f)
        c.drawLine(136f, 63f, 136f, 77f, p)
        c.drawLine(130f, 66.5f, 142f, 73.5f, p)
        c.drawLine(130f, 73.5f, 142f, 66.5f, p)
        c.restore()
        c.drawCircle(136f, 70f, 2.2f, f(Color.WHITE))
    }

    private fun drawEffects(c: Canvas, t: Float) {
        when (shown) {
            Emotion.THINKING -> {
                for (k in 0..2) {
                    val a = ((sin(t * 4f - k * 0.9f) + 1f) / 2f * 200f + 55f).toInt()
                    fill.shader = null; fill.color = fxColor; fill.alpha = a
                    c.drawCircle(150f + k * 10f, 50f - k * 6f, 3.2f, fill)
                }
            }
            Emotion.SURPRISED -> {
                text.color = Color.parseColor("#E56A8A"); text.textSize = 28f
                c.drawText("!", 160f, 62f, text)
            }
            Emotion.SLEEPY -> {
                text.color = fxColor
                val k = (t * 0.6f) % 1f
                text.textSize = 14f; text.alpha = ((1f - k) * 255).toInt()
                c.drawText("z", 148f + k * 8f, 64f - k * 20f, text)
                text.textSize = 19f; text.alpha = 255
                c.drawText("Z", 162f, 44f + sin(t * 2f) * 2f, text)
            }
            Emotion.LISTENING -> {
                for (k in 1..3) {
                    val a = ((sin(t * 5f - k) + 1f) / 2f * 180f + 40f).toInt()
                    line.color = Color.parseColor("#9C86E0"); line.strokeWidth = 2f; line.alpha = a
                    rect.set(150f - k * 7f, 116f - k * 9f, 150f + k * 7f, 116f + k * 9f)
                    c.drawArc(rect, -45f, 90f, false, line)
                }
                line.alpha = 255
            }
            Emotion.ANNOYED, Emotion.SAD -> {
                path.reset()
                path.moveTo(148f, 86f)
                path.quadTo(141f, 98f, 148f, 101f)
                path.quadTo(155f, 98f, 148f, 86f)
                c.drawPath(path, f(Color.parseColor("#A9D8F5")))
            }
            Emotion.SHY -> {
                text.color = fxColor; text.textSize = 18f; text.alpha = 200
                c.drawText("…", 158f, 64f, text)
                text.alpha = 255
            }
            Emotion.HAPPY -> {
                val a = ((sin(t * 3f) + 1f) / 2f)
                star(c, 40f, 58f, 5f + a * 2f)
                star(c, 164f, 46f, 7f - a * 2f)
            }
            else -> {}
        }
    }

    private fun star(c: Canvas, x: Float, y: Float, r: Float) {
        path.reset()
        path.moveTo(x, y - r)
        path.quadTo(x, y, x + r, y)
        path.quadTo(x, y, x, y + r)
        path.quadTo(x, y, x - r, y)
        path.quadTo(x, y, x, y - r)
        path.close()
        c.drawPath(path, f(Color.parseColor("#FFD36E")))
    }
}
