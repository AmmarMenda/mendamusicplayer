package com.ammar.mendamusicplayer

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import android.util.AttributeSet
import androidx.appcompat.widget.AppCompatSeekBar

/**
 * A SeekBar drawn as a static glass tube that fills with blood as the
 * progress increases. No animations: every draw reflects the current
 * [progress] / [max] values exactly.
 */
class BloodTubeSeekBar @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = android.R.attr.seekBarStyle
) : AppCompatSeekBar(context, attrs, defStyleAttr) {

    private var gradientsBuiltHeight = -1
    private var gradientsBuiltWidth = -1

    private val emptyPaint          = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bloodPaint          = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bloodSheenPaint     = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bloodShadowPaint    = Paint(Paint.ANTI_ALIAS_FLAG)
    private val meniscusPaint       = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glassTopPaint       = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glassBottomPaint    = Paint(Paint.ANTI_ALIAS_FLAG)
    private val outlinePaint        = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rimPaint            = Paint(Paint.ANTI_ALIAS_FLAG)

    private val tubePath      = Path()
    private val meniscusPath  = Path()

    init {
        // Required for smooth gradients + clipPath quality
        setLayerType(LAYER_TYPE_SOFTWARE, null)
        thumb = null
        splitTrack = false
        isFocusable = false
    }

    /**
     * Called from MainActivity.syncPlaybackUi(). Intentionally a no-op:
     * the tube is static — no pulse, no wave, no tick animation.
     */
    fun setPlaying(playing: Boolean) { /* static appearance */ }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        rebuildGradients(h.toFloat())
    }

    private fun rebuildGradients(h: Float) {
        if (h <= 0f) return
        if (h.toInt() == gradientsBuiltHeight && width == gradientsBuiltWidth) return
        gradientsBuiltHeight = h.toInt()
        gradientsBuiltWidth = width

        // ---- Empty glass tube: pale bluish-white fading down ----
        emptyPaint.shader = LinearGradient(
            0f, 0f, 0f, h,
            intArrayOf(
                0xFFF6FBFF.toInt(),
                0xFFDAE7F2.toInt(),
                0xFFB8CAD9.toInt(),
                0xFF92A5B7.toInt(),
                0xFF6E8093.toInt()
            ),
            floatArrayOf(0f, 0.25f, 0.55f, 0.85f, 1f),
            Shader.TileMode.CLAMP
        )

        // ---- Blood: deep crimson, brightest just above center ----
        bloodPaint.shader = LinearGradient(
            0f, 0f, 0f, h,
            intArrayOf(
                0xFF3A0202.toInt(),
                0xFF680606.toInt(),
                0xFFA50D0E.toInt(),
                0xFFC61F20.toInt(),
                0xFF9A1313.toInt(),
                0xFF5C0707.toInt()
            ),
            floatArrayOf(0f, 0.15f, 0.38f, 0.55f, 0.8f, 1f),
            Shader.TileMode.CLAMP
        )

        // Wet highlight across the top of the blood
        bloodSheenPaint.shader = LinearGradient(
            0f, 0f, 0f, h * 0.42f,
            intArrayOf(0x66FF8C8C.toInt(), 0x00FF8C8C),
            floatArrayOf(0f, 1f),
            Shader.TileMode.CLAMP
        )

        // Sediment shadow at bottom of blood
        bloodShadowPaint.shader = LinearGradient(
            0f, h * 0.72f, 0f, h,
            intArrayOf(0x00000000, 0x8C000000.toInt()),
            floatArrayOf(0f, 1f),
            Shader.TileMode.CLAMP
        )

        // Glass top reflection band
        glassTopPaint.shader = LinearGradient(
            0f, 0f, 0f, h * 0.52f,
            intArrayOf(0xAAFFFFFF.toInt(), 0x40FFFFFF, 0x00FFFFFF),
            floatArrayOf(0f, 0.35f, 1f),
            Shader.TileMode.CLAMP
        )

        // Glass inner bottom shadow (tube thickness)
        glassBottomPaint.shader = LinearGradient(
            0f, h * 0.7f, 0f, h,
            intArrayOf(0x00000000, 0x5C000000),
            floatArrayOf(0f, 1f),
            Shader.TileMode.CLAMP
        )
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f || max <= 0) return
        rebuildGradients(h)

        val left   = paddingLeft.toFloat()
        val right  = w - paddingRight.toFloat()
        val top    = paddingTop.toFloat()
        val bottom = h - paddingBottom.toFloat()
        val tubeH  = bottom - top
        if (right <= left || tubeH <= 0f) return

        val radius    = tubeH / 2f
        val cy        = top + radius
        val pct       = (progress.toFloat() / max.toFloat()).coerceIn(0f, 1f)
        val fillRight = left + (right - left) * pct

        tubePath.reset()
        tubePath.addRoundRect(left, top, right, bottom, radius, radius, Path.Direction.CW)

        // 1. Empty glass tube
        canvas.drawPath(tubePath, emptyPaint)

        // 2. Blood fill
        if (pct > 0.0005f) {
            canvas.save()
            canvas.clipPath(tubePath)
            canvas.drawRect(left, top, fillRight, bottom, bloodPaint)
            canvas.drawRect(left, top, fillRight, top + tubeH * 0.42f, bloodSheenPaint)
            canvas.drawRect(left, top + tubeH * 0.72f, fillRight, bottom, bloodShadowPaint)
            canvas.restore()

            // Meniscus — curved blood/air interface
            if (pct < 0.999f) {
                meniscusPath.reset()
                meniscusPath.moveTo(fillRight + 4f, top + 2f)
                meniscusPath.quadTo(fillRight - 4f, cy, fillRight + 4f, bottom - 2f)
                meniscusPaint.style = Paint.Style.STROKE
                meniscusPaint.strokeWidth = 1.5f
                meniscusPaint.strokeCap = Paint.Cap.ROUND
                meniscusPaint.color = 0xFF2C0202.toInt()
                canvas.save()
                canvas.clipPath(tubePath)
                canvas.drawPath(meniscusPath, meniscusPaint)
                canvas.restore()
            }
        }

        // 3. Glass top reflection
        canvas.save()
        canvas.clipPath(tubePath)
        canvas.drawRect(left, top, right, top + tubeH * 0.52f, glassTopPaint)
        canvas.restore()

        // 4. Glass inner bottom shadow
        canvas.save()
        canvas.clipPath(tubePath)
        canvas.drawRect(left, top + tubeH * 0.7f, right, bottom, glassBottomPaint)
        canvas.restore()

        // 5. Tube outline (glass wall)
        outlinePaint.style = Paint.Style.STROKE
        outlinePaint.strokeWidth = 1.5f
        outlinePaint.shader = null
        outlinePaint.color = 0xAAFFFFFF.toInt()
        canvas.drawPath(tubePath, outlinePaint)

        // 6. End rims
        rimPaint.style = Paint.Style.STROKE
        rimPaint.strokeWidth = 2f
        rimPaint.color = 0x88000000.toInt()
        canvas.drawCircle(left + radius, cy, radius - 1f, rimPaint)
        canvas.drawCircle(right - radius, cy, radius - 1f, rimPaint)
    }
}
