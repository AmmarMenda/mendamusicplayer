package com.ammar.mendamusicplayer

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.util.AttributeSet
import androidx.appcompat.widget.AppCompatSeekBar
import kotlin.math.sin

class BloodTubeSeekBar @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = androidx.appcompat.R.attr.seekBarStyle
) : AppCompatSeekBar(context, attrs, defStyleAttr) {

    private val density = resources.displayMetrics.density

    private val glassPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.5f * density
        color = Color.argb(90, 255, 255, 255)
    }
    private val glossPath = Path()
    private val glossPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(28, 255, 255, 255)
    }
    private val bloodPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val edgeGlowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 5f * density
        color = Color.argb(60, 255, 82, 82)
    }
    private val edgeCorePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f * density
        color = Color.argb(230, 255, 138, 128)
    }
    private val bubblePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(55, 255, 205, 210)
    }

    private val tubeRect = RectF()
    private val fillRect = RectF()
    private val clipPath = Path()

    private var bloodShader: Shader? = null

    private class Bubble(val xFrac: Float, val radiusPx: Float, val riseSpeed: Float, val swayPhase: Float) {
        var yFrac = (xFrac * 7f) % 1f
    }

    private val bubbles = listOf(
        Bubble(0.30f, 1.8f * density, 0.055f, 0.0f),
        Bubble(0.62f, 1.2f * density, 0.040f, 2.1f),
        Bubble(0.80f, 2.4f * density, 0.070f, 4.2f)
    )

    private var playing = false
    private var wavePhase = 0f
    private var waveAmplitude = 0f
    private var lastFrameNanos = 0L

    init {
        thumb = null
        splitTrack = false
    }

    fun setPlaying(value: Boolean) {
        if (playing != value) {
            playing = value
            lastFrameNanos = 0L
            invalidate()
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val margin = 2.5f * density
        tubeRect.set(margin, margin, w - margin, h - margin)
        bloodShader = LinearGradient(
            0f, tubeRect.top, 0f, tubeRect.bottom,
            intArrayOf(
                Color.argb(255, 239, 83, 80),
                Color.argb(255, 183, 28, 28),
                Color.argb(255, 110, 4, 10)
            ),
            floatArrayOf(0f, 0.42f, 1f),
            Shader.TileMode.CLAMP
        )
        bloodPaint.shader = bloodShader
    }

    override fun onDraw(canvas: Canvas) {
        stepAnimation()

        val radius = tubeRect.height() / 2f
        val innerSpan = tubeRect.width() - 2f * radius

        glassPaint.color = Color.argb(24, 255, 255, 255)
        canvas.drawRoundRect(tubeRect, radius, radius, glassPaint)

        val fraction = if (max <= 0) 0f else (progress.toFloat() / max).coerceIn(0f, 1f)
        val leadingX = tubeRect.left + radius + fraction * innerSpan

        if (fraction > 0f) {
            clipPath.reset()
            clipPath.addRoundRect(tubeRect, radius, radius, Path.Direction.CW)
            canvas.save()
            canvas.clipPath(clipPath)

            fillRect.set(tubeRect.left, tubeRect.top, leadingX, tubeRect.bottom)
            canvas.drawRoundRect(fillRect, radius, radius, bloodPaint)

            val centerY = tubeRect.centerY()
            val sway = sin(wavePhase) * waveAmplitude * radius * 0.22f
            val bulgeTopY = tubeRect.top + sin(wavePhase * 0.9f + 1.3f) * waveAmplitude * radius * 0.35f
            val bulgeBottomY = tubeRect.bottom - sin(wavePhase * 1.15f + 2.6f) * waveAmplitude * radius * 0.30f

            edgeCorePaint.color = Color.argb(235, 255, 120, 110)
            canvas.drawLine(
                leadingX + sway,
                bulgeTopY + radius * 0.25f,
                leadingX + sway,
                bulgeBottomY - radius * 0.25f,
                edgeGlowPaint
            )
            canvas.drawLine(
                leadingX + sway,
                bulgeTopY + radius * 0.25f,
                leadingX + sway,
                bulgeBottomY - radius * 0.25f,
                edgeCorePaint
            )

            val fillWidth = (leadingX - tubeRect.left).coerceAtLeast(1f)
            val sheenCx = tubeRect.left + ((wavePhase * 40f) % (fillWidth + 200f * density)) - 100f * density
            glossPaint.color = Color.argb(26, 255, 255, 255)
            canvas.drawOval(
                sheenCx - radius * 1.4f,
                tubeRect.top + tubeRect.height() * 0.12f,
                sheenCx + radius * 1.4f,
                tubeRect.top + tubeRect.height() * 0.34f,
                glossPaint
            )

            for (bubble in bubbles) {
                val bx = tubeRect.left +
                        bubble.xFrac * innerSpan +
                        sin(wavePhase * 0.6f + bubble.swayPhase) * 3f * density
                if (bx < leadingX - radius * 0.4f) {
                    val by = tubeRect.bottom - bubble.yFrac * tubeRect.height() * 0.85f - radius * 0.1f
                    canvas.drawCircle(bx, by, bubble.radiusPx, bubblePaint)
                }
            }

            canvas.restore()
        }

        glassPaint.color = Color.argb(20, 255, 255, 255)
        glossPath.reset()
        glossPath.moveTo(tubeRect.left + radius * 0.55f, tubeRect.top)
        glossPath.lineTo(tubeRect.left + radius * 1.45f, tubeRect.top)
        glossPath.lineTo(tubeRect.left + radius * 0.75f, tubeRect.bottom)
        glossPath.lineTo(tubeRect.left + radius * 0.05f, tubeRect.bottom)
        glossPath.close()
        canvas.save()
        clipPath.reset()
        clipPath.addRoundRect(tubeRect, radius, radius, Path.Direction.CW)
        canvas.clipPath(clipPath)
        canvas.drawPath(glossPath, glossPaint)
        canvas.restore()

        canvas.drawRoundRect(tubeRect, radius, radius, rimPaint)
        glossPaint.color = Color.argb(70, 255, 255, 255)
        canvas.drawLine(
            tubeRect.left + radius,
            tubeRect.top + 1.2f * density,
            tubeRect.right - radius,
            tubeRect.top + 1.2f * density,
            glossPaint
        )

        if (playing || waveAmplitude > 0.02f) {
            postInvalidateOnAnimation()
        } else {
            lastFrameNanos = 0L
        }
    }

    private fun stepAnimation() {
        val now = System.nanoTime()
        if (lastFrameNanos != 0L) {
            val dt = ((now - lastFrameNanos) / 1_000_000_000f).coerceIn(0f, 0.05f)
            if (playing) {
                wavePhase += dt * 5.2f
                waveAmplitude += (1f - waveAmplitude) * dt * 6f
                for (bubble in bubbles) {
                    bubble.yFrac -= bubble.riseSpeed * dt * 10f
                    if (bubble.yFrac < 0.08f) {
                        bubble.yFrac = 0.95f
                    }
                }
            } else {
                waveAmplitude -= waveAmplitude * dt * 5f
                if (waveAmplitude < 0.01f) waveAmplitude = 0f
            }
        }
        lastFrameNanos = now
    }
}
