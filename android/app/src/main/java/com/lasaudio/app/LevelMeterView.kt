package com.lasaudio.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.Choreographer
import android.view.View
import com.lasaudio.core.LevelMeter
import java.util.Locale
import kotlin.math.exp
import kotlin.math.log10
import kotlin.math.max

/**
 * 左右声道响度电平条（L / R）。
 *
 * 数据来自本机解码后的 PCM（[LevelMeter]，完全本地统计）。绘制按屏幕刷新率进行：
 * 每帧取走「自上一帧以来的峰值」，再按快启慢落的弹道平滑，因此高帧率下不卡顿、也不会漏掉瞬态。
 */
class LevelMeterView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0
) : View(context, attrs, defStyle) {

    private companion object {
        const val MIN_DB = -60f
        const val ATTACK_MS = 12f
        const val RELEASE_MS = 320f
        const val PEAK_HOLD_MS = 900L
        const val PEAK_FALL_PER_S = 0.9f
    }

    /** 由界面注入的电平源；为 null 时显示为静音。 */
    var meter: LevelMeter? = null

    private val density = resources.displayMetrics.density

    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#8A97A8")
        textSize = 11f * density
        typeface = Typeface.MONOSPACE
    }
    private val dbPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#B9C4D2")
        textSize = 10f * density
        typeface = Typeface.MONOSPACE
    }
    private val trackPaint = Paint().apply { color = Color.parseColor("#1B212B") }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val peakPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#F5F5F5")
        strokeWidth = 2f * density
    }

    private var barGradient: LinearGradient? = null
    private var gradientRight = Float.NaN

    private var dispL = 0f
    private var dispR = 0f
    private var holdL = 0f
    private var holdR = 0f
    private var holdTimeL = 0L
    private var holdTimeR = 0L
    private var dbTextL = "-inf dB"
    private var dbTextR = "-inf dB"
    private var lastDbL = Float.NaN
    private var lastDbR = Float.NaN
    private var lastNanos = 0L
    private var active = false
    private var attached = false

    private val choreographer = Choreographer.getInstance()

    private val frameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!active || !attached) return
            val dtMs = if (lastNanos == 0L) 16L else (frameTimeNanos - lastNanos) / 1_000_000L
            lastNanos = frameTimeNanos

            val m = meter
            val targetL = if (m == null) 0f else norm(m.consumePeakLeft())
            val targetR = if (m == null) 0f else norm(m.consumePeakRight())
            dispL = ballistics(dispL, targetL, dtMs)
            dispR = ballistics(dispR, targetR, dtMs)
            holdL = advanceHold(dispL, holdL, dtMs, true)
            holdR = advanceHold(dispR, holdR, dtMs, false)

            invalidate()
            if (active && attached) choreographer.postFrameCallback(this)
        }
    }

    fun setActive(value: Boolean) {
        if (active == value) return
        active = value
        lastNanos = 0L
        if (value) {
            if (attached) choreographer.postFrameCallback(frameCallback)
        } else {
            choreographer.removeFrameCallback(frameCallback)
            dispL = 0f
            dispR = 0f
            holdL = 0f
            holdR = 0f
            invalidate()
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        attached = true
        if (active) choreographer.postFrameCallback(frameCallback)
    }

    override fun onDetachedFromWindow() {
        attached = false
        choreographer.removeFrameCallback(frameCallback)
        super.onDetachedFromWindow()
    }

    /** 线性 RMS → 0..1（-60dBFS ~ 0dBFS）。 */
    private fun norm(rms: Float): Float {
        if (rms <= 0f) return 0f
        val db = 20f * log10(max(rms, 1e-6f))
        return ((db - MIN_DB) / -MIN_DB).coerceIn(0f, 1f)
    }

    private fun ballistics(current: Float, target: Float, dtMs: Long): Float {
        val tau = if (target > current) ATTACK_MS else RELEASE_MS
        val k = 1f - exp(-max(1L, dtMs).toFloat() / tau)
        return current + (target - current) * k
    }

    private fun advanceHold(display: Float, hold: Float, dtMs: Long, isLeft: Boolean): Float {
        val now = System.currentTimeMillis()
        if (display >= hold) {
            if (isLeft) holdTimeL = now else holdTimeR = now
            return display
        }
        val holdTime = if (isLeft) holdTimeL else holdTimeR
        if (now - holdTime > PEAK_HOLD_MS) {
            return max(display, hold - PEAK_FALL_PER_S * dtMs / 1000f)
        }
        return hold
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(Color.parseColor("#101419"))
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 8f || h <= 8f) return

        val pad = 8f * density
        val labelW = 16f * density
        val dbW = 58f * density
        val rowH = (h - pad * 3f) / 2f
        if (rowH <= 6f) return
        val left = pad + labelW
        val right = w - pad - dbW
        if (right - left <= 8f) return

        if (barGradient == null || gradientRight != right) {
            barGradient = LinearGradient(
                left, 0f, right, 0f,
                intArrayOf(Color.parseColor("#4CAF50"), Color.parseColor("#FFC107"), Color.parseColor("#F44336")),
                floatArrayOf(0f, 0.72f, 1f),
                Shader.TileMode.CLAMP
            )
            gradientRight = right
        }

        drawRow(canvas, pad, left, right, rowH, dispL, holdL, true)
        drawRow(canvas, pad * 2f + rowH, left, right, rowH, dispR, holdR, false)
    }

    private fun drawRow(canvas: Canvas, top: Float, left: Float, right: Float, rowH: Float,
                        level: Float, hold: Float, isLeft: Boolean) {
        val centerY = top + rowH / 2f
        val barH = rowH * 0.62f
        val barTop = centerY - barH / 2f
        val barBottom = centerY + barH / 2f

        canvas.drawText(if (isLeft) "L" else "R", left - 14f * density, centerY + 4f * density, labelPaint)
        canvas.drawRect(left, barTop, right, barBottom, trackPaint)

        val filled = right - (right - left) * level.coerceIn(0f, 1f)
        if (filled < right - 0.5f) {
            fillPaint.shader = barGradient
            canvas.drawRect(filled, barTop, right, barBottom, fillPaint)
        }

        if (hold > 0.001f) {
            val hx = right - (right - left) * hold.coerceIn(0f, 1f)
            canvas.drawLine(hx, barTop, hx, barBottom, peakPaint)
        }

        val dbValue = if (level <= 0.0001f) -99f else MIN_DB * (1f - level)
        val last = if (isLeft) lastDbL else lastDbR
        if (dbValue != last) {
            val text = if (dbValue <= -90f) "-inf dB" else String.format(Locale.US, "%.1f dB", dbValue)
            if (isLeft) {
                lastDbL = dbValue
                dbTextL = text
            } else {
                lastDbR = dbValue
                dbTextR = text
            }
        }
        canvas.drawText(if (isLeft) dbTextL else dbTextR, right + 6f * density, centerY + 4f * density, dbPaint)
    }
}
