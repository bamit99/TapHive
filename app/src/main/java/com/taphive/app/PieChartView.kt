package com.taphive.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View

/**
 * A dependency-free donut pie chart in the app's neon style.
 *
 * Slices are drawn as pie arcs; a hole of the surface color is drawn over the
 * center, and `centerText` sits inside the hole.
 */
class PieChartView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {
    data class Slice(
        val label: String,
        val fraction: Float,
        val color: Int
    )

    var slices: List<Slice> = emptyList()
        set(value) {
            field = value
            invalidate()
        }

    var centerText: String = ""
        set(value) {
            field = value
            invalidate()
        }

    private val piePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeWidth = 3f }
    private val holePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 24f }

    override fun onSizeChanged(width: Int, height: Int, oldWidth: Int, oldHeight: Int) {
        super.onSizeChanged(width, height, oldWidth, oldHeight)
        textPaint.textSize = Math.min(width, height) * 0.09f
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val size = Math.min(width, height) * 0.88f
        val rect = RectF()
        rect.set((width - size) / 2f, (height - size) / 2f, (width + size) / 2f, (height + size) / 2f)
        val cx = width / 2f
        val cy = height / 2f

        if (slices.isEmpty()) {
            holePaint.color = context.getColor(R.color.neon_dim)
            canvas.drawCircle(cx, cy, size * 0.45f, holePaint)
            return
        }

        var start = -90f
        for (slice in slices) {
            val sweep = slice.fraction * 360f
            piePaint.color = slice.color
            canvas.drawArc(rect, start, sweep, true, piePaint)
            strokePaint.color = slice.color
            canvas.drawArc(rect, start, sweep, true, strokePaint)
            start += sweep
        }

        holePaint.color = context.getColor(R.color.neon_surface)
        canvas.drawCircle(cx, cy, size * 0.45f, holePaint)

        textPaint.color = context.getColor(R.color.neon_text)
        textPaint.textAlign = Paint.Align.CENTER
        canvas.drawText(centerText, cx, cy + textPaint.textSize * 0.35f, textPaint)
    }
}
