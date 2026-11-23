package com.example.chesshint

import android.content.Context
import android.graphics.*
import android.view.View
import kotlin.math.abs
import kotlin.math.hypot

class Badge(val sym: String, val color: Int, val ru: String)

class ArrowView(c: Context) : View(c) {
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xE02E7DFF.toInt(); style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }
    private val fill = Paint(line).apply { style = Paint.Style.FILL }
    private val sq = Paint(Paint.ANTI_ALIAS_FLAG)
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bar = Paint(Paint.ANTI_ALIAS_FLAG)
    private val txt = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; textAlign = Paint.Align.CENTER; typeface = Typeface.DEFAULT_BOLD
    }
    private var rect: Rect? = null
    private var flipped = false
    private var move: String? = null
    private var knight = false
    private var badgeSq: String? = null
    private var badge: Badge? = null
    private var win = -1f   // доля белых в оценке, 0..100; <0 — не показывать

    val hasContent get() = move != null || badge != null

    fun clear() { move = null; badge = null; win = -1f; invalidate() }

    fun update(r: Rect, flipped: Boolean, move: String?, knight: Boolean, badgeSq: String?, badge: Badge?, winPct: Float) {
        val changed = move != this.move || badgeSq != this.badgeSq
        rect = r; this.flipped = flipped; this.move = move; this.knight = knight
        this.badgeSq = badgeSq; this.badge = badge; this.win = winPct
        animate().cancel()
        if (changed && move != null) { alpha = 0f; animate().alpha(1f).setDuration(160).start() } else alpha = 1f
        invalidate()
    }

    private fun ctr(sq: String): PointF {
        val r = rect!!; val cell = r.width() / 8f
        val file = sq[0] - 'a'; val rk = sq[1] - '1'
        val col = if (flipped) 7 - file else file
        val row = if (flipped) rk else 7 - rk
        return PointF(r.left + (col + .5f) * cell, r.top + (row + .5f) * cell)
    }

    override fun onDraw(c: Canvas) {
        val r = rect ?: return; val cell = r.width() / 8f
        // окно оверлея может начинаться ниже верха экрана (строка состояния) — компенсируем сдвиг
        val loc = IntArray(2); getLocationOnScreen(loc)
        c.translate(-loc[0].toFloat(), -loc[1].toFloat())

        // полоса оценки над доской: белая часть — преимущество белых
        if (win >= 0f) {
            val h = cell * 0.075f; val top = r.top - h - cell * 0.07f
            val l = r.left + cell * 0.12f; val w = r.width() - cell * 0.24f
            bar.color = 0xFF111111.toInt(); c.drawRoundRect(RectF(l - 3, top - 3, l + w + 3, top + h + 3), h, h, bar)
            bar.color = 0xFFEFEFEF.toInt(); c.drawRoundRect(RectF(l, top, l + w * win / 100f, top + h), h / 2, h / 2, bar)
        }

        move?.let { mv ->
            val a = ctr(mv.substring(0, 2)); val b = ctr(mv.substring(2, 4))
            val h = cell / 2
            sq.color = 0x552E7DFF.toInt(); c.drawRect(a.x - h, a.y - h, a.x + h, a.y + h, sq)
            sq.color = 0x882E7DFF.toInt(); c.drawRect(b.x - h, b.y - h, b.x + h, b.y + h, sq)
            // конь ходит буквой «Г»: сначала по длинной стороне, потом поворот
            val pts = if (knight) listOf(a, if (abs(b.x - a.x) > abs(b.y - a.y)) PointF(b.x, a.y) else PointF(a.x, b.y), b)
                      else listOf(a, b)
            val last = pts.last(); val prev = pts[pts.size - 2]
            val len = hypot(last.x - prev.x, last.y - prev.y)
            val ux = (last.x - prev.x) / len; val uy = (last.y - prev.y) / len
            val head = cell * 0.46f
            line.strokeWidth = cell * 0.17f
            c.drawPath(Path().apply {
                moveTo(a.x, a.y)
                for (i in 1 until pts.size - 1) lineTo(pts[i].x, pts[i].y)
                lineTo(last.x - ux * head * 0.8f, last.y - uy * head * 0.8f)
            }, line)
            val bx = last.x - ux * head; val by = last.y - uy * head
            c.drawPath(Path().apply {
                moveTo(last.x, last.y)
                lineTo(bx - uy * head * 0.55f, by + ux * head * 0.55f)
                lineTo(bx + uy * head * 0.55f, by - ux * head * 0.55f); close()
            }, fill)
            c.drawCircle(a.x, a.y, cell * 0.16f, fill)
        }

        val bd = badge; val s = badgeSq
        if (bd != null && s != null) {
            val p = ctr(s); val cx = p.x + cell * 0.33f; val cy = p.y - cell * 0.33f
            dot.color = Color.WHITE; c.drawCircle(cx, cy, cell * 0.235f, dot)
            dot.color = bd.color; c.drawCircle(cx, cy, cell * 0.2f, dot)
            txt.textSize = cell * 0.25f; c.drawText(bd.sym, cx, cy + cell * 0.09f, txt)
        }
    }
}
