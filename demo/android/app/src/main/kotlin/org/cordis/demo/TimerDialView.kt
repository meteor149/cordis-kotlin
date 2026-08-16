package org.cordis.demo

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.View
import kotlin.math.min

class TimerDialView(context: Context) : View(context) {
    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val progressPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val bounds = RectF()

    var progress: Float = 1f
        set(value) {
            field = value.coerceIn(0f, 1f)
            invalidate()
        }

    var trackColor: Int = 0x22FFFFFF
        set(value) { field = value; invalidate() }

    var progressColor: Int = 0xFFFFFFFF.toInt()
        set(value) { field = value; invalidate() }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val size = min(width, height).toFloat()
        val stroke = size * 0.035f
        val inset = stroke * 2.2f
        bounds.set(
            (width - size) / 2f + inset,
            (height - size) / 2f + inset,
            (width + size) / 2f - inset,
            (height + size) / 2f - inset,
        )

        trackPaint.strokeWidth = stroke
        trackPaint.color = trackColor
        canvas.drawArc(bounds, -90f, 360f, false, trackPaint)

        glowPaint.strokeWidth = stroke * 2.3f
        glowPaint.color = progressColor and 0x33FFFFFF
        canvas.drawArc(bounds, -90f, 360f * progress, false, glowPaint)

        progressPaint.strokeWidth = stroke
        progressPaint.color = progressColor
        canvas.drawArc(bounds, -90f, 360f * progress, false, progressPaint)
    }
}
