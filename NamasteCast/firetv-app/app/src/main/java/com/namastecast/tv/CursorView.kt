package com.namastecast.tv

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.view.View

/** Mouse pointer drawn over the WebView. Position is in this view's pixels. */
class CursorView(context: Context) : View(context) {
    var cx = 0f
        private set
    var cy = 0f
        private set
    private var placed = false
    private val density = resources.displayMetrics.density

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; style = Paint.Style.FILL }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FF7A1A"); style = Paint.Style.STROKE; strokeWidth = 2.5f * density
    }
    private val shadow = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x55000000; style = Paint.Style.FILL }

    private val hideRunnable = Runnable { animate().alpha(0f).setDuration(400).start() }

    init {
        isClickable = false
        isFocusable = false
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        if (!placed && w > 0) { cx = w / 2f; cy = h / 2f; placed = true }
        cx = cx.coerceIn(0f, w.toFloat()); cy = cy.coerceIn(0f, h.toFloat())
    }

    fun moveBy(dx: Float, dy: Float) = moveTo(cx + dx, cy + dy)

    fun moveTo(x: Float, y: Float) {
        cx = x.coerceIn(0f, (width - 1).coerceAtLeast(0).toFloat())
        cy = y.coerceIn(0f, (height - 1).coerceAtLeast(0).toFloat())
        wake()
        invalidate()
    }

    /** Show the pointer, auto-hide after 4s idle so it never sits on the video. */
    fun wake() {
        removeCallbacks(hideRunnable)
        animate().cancel()
        alpha = 1f
        postDelayed(hideRunnable, 4000)
    }

    override fun onDraw(canvas: Canvas) {
        val s = density * 1.6f
        val p = Path().apply {
            moveTo(cx, cy)
            lineTo(cx, cy + 18 * s)
            lineTo(cx + 4.5f * s, cy + 13.5f * s)
            lineTo(cx + 8f * s, cy + 21 * s)
            lineTo(cx + 11f * s, cy + 19.5f * s)
            lineTo(cx + 7.5f * s, cy + 12.5f * s)
            lineTo(cx + 13.5f * s, cy + 12.5f * s)
            close()
        }
        canvas.save(); canvas.translate(2 * density, 2 * density); canvas.drawPath(p, shadow); canvas.restore()
        canvas.drawPath(p, fill)
        canvas.drawPath(p, stroke)
    }
}
