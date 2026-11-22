package com.example.chesshint

import android.content.Context
import android.graphics.*
import android.view.View
import kotlin.math.abs
import kotlin.math.hypot

class Badge(val sym: String, val color: Int, val ru: String)

class ArrowView(c: Context) : View(c) {
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xDD2E7DFF.toInt(); style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }
    private val fill = Paint(line).apply { style = Paint.Style.FILL }
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG)
    private val txt = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; textAlign = Paint.Align.CENTER; typeface = Typeface.DEFAULT_BOLD
    }
    private var rect: Rect? = null
    private var flipped = false
    private var move: String? = null
    private var knight = false
    private var badgeSq: String? = null
    private var badge: Badge? = null

    val hasContent get() = move != null || badge != null

    fun clear() { move = null; badge = null; invalidate() }

    fun update(r: Rect, flipped: Boolean, move: String?, knight: Boolean, badgeSq: String?, badge: Badge?) {
        rect = r; this.flipped = flipped; this.move = move; this.knight = knight
        this.badgeSq = badgeSq; this.badge = badge; invalidate()
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
        move?.let { mv ->
            val a = ctr(mv.substring(0, 2)); val b = ctr(mv.substring(2, 4))
            // конь ходит буквой «Г»: сначала по длинной стороне, потом поворот
            val pts = if (knight) listOf(a, if (abs(b.x - a.x) > abs(b.y - a.y)) PointF(b.x, a.y) else PointF(a.x, b.y), b)
                      else listOf(a, b)
            val last = pts.last(); val prev = pts[pts.size - 2]
            val len = hypot(last.x - prev.x, last.y - prev.y)
            val ux = (last.x - prev.x) / len; val uy = (last.y - prev.y) / len
            val head = cell * 0.45f
            line.strokeWidth = cell * 0.18f
            val path = Path().apply {
                moveTo(a.x, a.y)
                for (i in 1 until pts.size - 1) lineTo(pts[i].x, pts[i].y)
                lineTo(last.x - ux * head * 0.8f, last.y - uy * head * 0.8f)
            }
            c.drawPath(path, line)
            val bx = last.x - ux * head; val by = last.y - uy * head
            c.drawPath(Path().apply {
                moveTo(last.x, last.y)
                lineTo(bx - uy * head * 0.55f, by + ux * head * 0.55f)
                lineTo(bx + uy * head * 0.55f, by - ux * head * 0.55f); close()
            }, fill)
            c.drawCircle(a.x, a.y, cell * 0.18f, fill)
        }
        val b = badge; val s = badgeSq
        if (b != null && s != null) {
            val p = ctr(s); val cx = p.x + cell * 0.33f; val cy = p.y - cell * 0.33f
            dot.color = b.color; c.drawCircle(cx, cy, cell * 0.2f, dot)
            txt.textSize = cell * 0.24f; c.drawText(b.sym, cx, cy + cell * 0.085f, txt)
        }
    }
}
