package com.lasaudio.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.Choreographer
import android.view.View
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

/**
 * 轻量多曲线滚动图（无第三方依赖）。
 *
 * 高帧率要点：
 *  - 绘制由 Choreographer 按屏幕刷新率驱动（60/90/120Hz），数据每 500ms 推送一次也能连续平滑滚动；
 *  - 数据用 FloatArray 环形缓冲，push 不装箱、不搬移数组；
 *  - onDraw 内零分配：复用 Path/Paint/FloatArray，文字只在数值变化时重算。
 * 线条平滑：相邻点之间用「中点二次贝塞尔」连接，C1 连续且不会过冲。
 */
class ChartView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0
) : View(context, attrs, defStyle) {

    private companion object {
        const val CAPACITY = 240
        const val PUSH_INTERVAL_MS = 500f
    }

    private class Series(val name: String, val unit: String, val color: Int) {
        val values = FloatArray(CAPACITY)
        var count = 0
        var head = 0
        var scale = 1.0
        var label = ""
        var lastValue = Float.NaN
        var maxLabel = ""
        var lastScale = Double.NaN
        var shader: LinearGradient? = null
        var shaderTop = Float.NaN
        var shaderBottom = Float.NaN

        fun push(v: Float) {
            if (count < CAPACITY) {
                values[(head + count) % CAPACITY] = v
                count++
            } else {
                values[head] = v
                head = (head + 1) % CAPACITY
            }
        }

        fun at(i: Int): Float = values[(head + i) % CAPACITY]

        fun latest(): Float = if (count == 0) 0f else at(count - 1)

        fun clear() {
            count = 0
            head = 0
        }
    }

    private val series = ArrayList<Series>()
    private val density: Float = resources.displayMetrics.density

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.6f * density
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val gridPaint = Paint().apply {
        color = Color.parseColor("#2A303B")
        strokeWidth = density
        style = Paint.Style.STROKE
    }
    private val bandPaint = Paint().apply { color = Color.parseColor("#151A21") }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#C6CFDB")
        textSize = 11f * density
        typeface = Typeface.MONOSPACE
    }
    private val dimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#6C7787")
        textSize = 9f * density
        typeface = Typeface.MONOSPACE
    }
    private val linePath = Path()
    private val fillPath = Path()
    private val xs = FloatArray(CAPACITY)
    private val ys = FloatArray(CAPACITY)

    private val choreographer = Choreographer.getInstance()
    private var active = false
    private var attached = false
    private var lastPushNanos = 0L
    private var frac = 1f
    /** 滚动动画使用的时间跨度：由界面给出的提示值起步，之后按实测推送间隔自适应。 */
    private var animIntervalMs = PUSH_INTERVAL_MS
    private var haveMeasuredInterval = false

    /** 界面刷新率变化时作为起始提示；随后由实际推送间隔自动校正，避免刷新率改变后曲线卡顿。 */
    fun setPushIntervalMs(ms: Float) {
        animIntervalMs = ms.coerceIn(50f, 2000f)
        haveMeasuredInterval = false
    }

    private val frameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!active || !attached) return
            val sincePushMs = if (lastPushNanos == 0L) Float.MAX_VALUE
            else (frameTimeNanos - lastPushNanos) / 1_000_000f
            // 新一轮数据到达后，曲线在「一个推送周期」内从右向左匀速滑入，避免跳格
            frac = if (sincePushMs > animIntervalMs * 1.6f) 1f
            else min(1f, sincePushMs / animIntervalMs)
            if (hasData()) invalidate()
            if (active && attached) choreographer.postFrameCallback(this)
        }
    }

    fun addSeries(name: String, unit: String, color: Int) {
        series.add(Series(name, unit, color))
        invalidate()
    }

    /** 由界面按固定间隔推送新采样点（推入环形缓冲，绘制由刷新率驱动）。 */
    fun push(index: Int, value: Double) {
        if (index < 0 || index >= series.size) return
        val v = if (value.isFinite()) max(0.0, value) else 0.0
        series[index].push(v.toFloat())
        val now = System.nanoTime()
        if (lastPushNanos != 0L) {
            val deltaMs = (now - lastPushNanos) / 1_000_000f
            // 只采信合理区间：忽略同一批推送内的 0 间隔与长时间暂停后的超大间隔
            if (deltaMs >= 40f && deltaMs <= 2500f) {
                animIntervalMs = if (!haveMeasuredInterval) deltaMs
                else animIntervalMs * 0.6f + deltaMs * 0.4f
                haveMeasuredInterval = true
            }
        }
        lastPushNanos = now
        if (!active) invalidate()
    }

    fun clearAll() {
        for (s in series) s.clear()
        invalidate()
    }

    /** 进入/退出前台时开关绘制循环（省电）。 */
    fun setActive(value: Boolean) {
        if (active == value) return
        active = value
        if (value) {
            lastPushNanos = 0L
            if (attached) choreographer.postFrameCallback(frameCallback)
        } else {
            choreographer.removeFrameCallback(frameCallback)
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

    private fun hasData(): Boolean {
        for (s in series) if (s.count >= 2) return true
        return false
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(Color.parseColor("#101419"))
        val n = series.size
        if (n == 0) return

        val pad = 4f * density
        val bandHeight = (height - pad * 2) / n
        val contentW = width - pad * 2
        if (bandHeight <= 6f || contentW <= 8f) return
        val dx = contentW / (CAPACITY - 1).toFloat()

        for (si in 0 until n) {
            val s = series[si]
            val top = pad + si * bandHeight
            val bottom = top + bandHeight - pad
            canvas.drawRect(pad, top, width - pad, bottom, bandPaint)
            canvas.drawRect(pad, top, width - pad, bottom, gridPaint)
            val midY = (top + bottom) / 2f
            canvas.drawLine(pad, midY, width - pad, midY, gridPaint)

            var maxV = 0f
            for (i in 0 until s.count) {
                val v = s.at(i)
                if (v > maxV) maxV = v
            }
            s.scale = max(maxV * 1.15, 0.001).toDouble()

            val cnt = s.count
            if (cnt >= 2) {
                // 时间轴：最新点从最右侧随 frac 向左滑入，形成连续滚动
                val baseSlot = (CAPACITY - cnt).toFloat() - frac
                for (i in 0 until cnt) {
                    xs[i] = pad + (baseSlot + i) * dx
                    val ratio = (s.at(i) / s.scale).coerceIn(0.0, 1.0).toFloat()
                    ys[i] = bottom - ratio * (bottom - top)
                }

                canvas.save()
                canvas.clipRect(pad, top, width - pad, bottom)

                buildSmoothPath(linePath, cnt)
                linePaint.color = s.color
                canvas.drawPath(linePath, linePaint)

                buildSmoothPath(fillPath, cnt)
                fillPath.lineTo(xs[cnt - 1], bottom)
                fillPath.lineTo(xs[0], bottom)
                fillPath.close()
                if (s.shader == null || s.shaderTop != top || s.shaderBottom != bottom) {
                    s.shader = LinearGradient(
                        0f, top, 0f, bottom,
                        Color.argb(96, Color.red(s.color), Color.green(s.color), Color.blue(s.color)),
                        Color.argb(0, Color.red(s.color), Color.green(s.color), Color.blue(s.color)),
                        Shader.TileMode.CLAMP
                    )
                    s.shaderTop = top
                    s.shaderBottom = bottom
                }
                fillPaint.shader = s.shader
                canvas.drawPath(fillPath, fillPaint)
                canvas.restore()
            }

            val current = s.latest()
            if (current != s.lastValue) {
                s.lastValue = current
                s.label = String.format(Locale.US, "%s %.2f %s", s.name, current, s.unit)
            }
            if (s.scale != s.lastScale) {
                s.lastScale = s.scale
                s.maxLabel = String.format(Locale.US, "max %.1f", s.scale)
            }
            textPaint.color = s.color
            canvas.drawText(s.label, pad + 6 * density, top + 13 * density, textPaint)
            canvas.drawText(s.maxLabel, width - 66 * density, top + 13 * density, dimPaint)
        }
    }

    /** 中点二次贝塞尔平滑：曲线连续、不会过冲，且每帧开销很低。 */
    private fun buildSmoothPath(path: Path, n: Int) {
        path.reset()
        path.moveTo(xs[0], ys[0])
        if (n == 2) {
            path.lineTo(xs[1], ys[1])
            return
        }
        for (i in 1 until n - 1) {
            val mx = (xs[i] + xs[i + 1]) * 0.5f
            val my = (ys[i] + ys[i + 1]) * 0.5f
            path.quadTo(xs[i], ys[i], mx, my)
        }
        path.lineTo(xs[n - 1], ys[n - 1])
    }
}
