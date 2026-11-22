package com.example.chesshint

import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import kotlin.math.abs

/**
 * Находит доску Chess.com (зелёная тема) и распознаёт фигуры.
 * Фон клетки вычитается (по углам), поэтому подсветка хода и цвет клетки не мешают.
 * Шаблоны фигур запоминаются один раз со стартовой позиции и сохраняются на диск.
 */
class BoardRecognizer(private val prefs: SharedPreferences) {
    var board: Rect? = null
    private val N = 24
    private val pieces = "PNBRQKpnbrqk"
    private val tpl = HashMap<Char, MutableList<IntArray>>()
    private val blank = IntArray(N * N) { 128 }

    init { load() }
    val learned get() = pieces.all { tpl[it]?.isNotEmpty() == true }

    private fun save() = prefs.edit().putString("t", tpl.entries.joinToString("\n") { e ->
        e.value.joinToString("\n") { "${e.key}:${it.joinToString(",")}" } }).apply()

    private fun load() {
        prefs.getString("t", null)?.lines()?.forEach { l ->
            if (l.length > 2) tpl.getOrPut(l[0]) { ArrayList() }.add(l.substring(2).split(",").map { it.toInt() }.toIntArray())
        }
    }

    private fun near(c: Int, r: Int, g: Int, b: Int) =
        abs(Color.red(c) - r) < 14 && abs(Color.green(c) - g) < 14 && abs(Color.blue(c) - b) < 14

    fun locate(b: Bitmap): Rect? {
        val w = b.width; val h = b.height; val px = IntArray(w)
        var x0 = w; var y0 = h; var x1 = -1; var y1 = -1
        for (y in 0 until h step 2) {
            b.getPixels(px, 0, w, 0, y, w, 1)
            for (x in 0 until w step 2) {
                val c = px[x]
                if (near(c, 238, 238, 210) || near(c, 118, 150, 86)) {
                    if (x < x0) x0 = x; if (x > x1) x1 = x; if (y < y0) y0 = y; if (y > y1) y1 = y
                }
            }
        }
        val bw = x1 - x0 + 1; val bh = y1 - y0 + 1
        if (bw < w / 2 || abs(bw - bh) > bw / 8) return null
        val s = (bw + bh) / 2
        if (x0 + s > w || y0 + s > h) return null
        return Rect(x0, y0, x0 + s, y0 + s).also { board = it }
    }

    private fun sig(b: Bitmap, r: Rect, row: Int, col: Int): IntArray {
        val s = r.width() / 8
        val cell = Bitmap.createBitmap(b, r.left + col * s, r.top + row * s, s, s)
        val sm = Bitmap.createScaledBitmap(cell, N, N, true)
        fun lum(x: Int, y: Int): Int { val c = sm.getPixel(x, y); return (Color.red(c) * 3 + Color.green(c) * 6 + Color.blue(c)) / 10 }
        val k = intArrayOf(lum(1, 1), lum(N - 2, 1), lum(1, N - 2), lum(N - 2, N - 2)).sorted()
        val bg = (k[1] + k[2]) / 2
        return IntArray(N * N) { val v = lum(it % N, it / N); if (abs(v - bg) < 10) 128 else v }
    }

    private fun dist(a: IntArray, b: IntArray): Int { var d = 0; for (i in a.indices) d += abs(a[i] - b[i]); return d }

    // клетка экрана -> (файл 0..7, горизонталь 0..7)
    private fun fr(row: Int, col: Int, flipped: Boolean) = if (flipped) Pair(7 - col, row) else Pair(col, 7 - row)

    private fun startPiece(f: Int, rk: Int): Char? = when (rk) {
        0 -> "RNBQKBNR"[f]; 1 -> 'P'; 6 -> 'p'; 7 -> "rnbqkbnr"[f]; else -> null
    }

    fun isStart(g: Array<CharArray>) = (0..7).all { rk -> (0..7).all { f -> g[rk][f] == (startPiece(f, rk) ?: '.') } }

    fun learn(b: Bitmap, flipped: Boolean): Boolean {
        val r = locate(b) ?: return false
        tpl.clear()
        for (row in 0..7) for (col in 0..7) {
            val (f, rk) = fr(row, col, flipped)
            val p = startPiece(f, rk) ?: continue
            tpl.getOrPut(p) { ArrayList() }.add(sig(b, r, row, col))
        }
        if (learned) save()
        return learned
    }

    /** g[горизонталь][файл], '.' — пусто. null — доска не найдена / позиция неправдоподобна. */
    fun grid(b: Bitmap, flipped: Boolean): Array<CharArray>? {
        if (!learned) return null
        val r = locate(b) ?: return null
        val g = Array(8) { CharArray(8) { '.' } }
        for (row in 0..7) for (col in 0..7) {
            val (f, rk) = fr(row, col, flipped)
            val s = sig(b, r, row, col)
            var best = dist(s, blank); var ch = '.'
            for (p in pieces) for (t in tpl[p]!!) { val d = dist(s, t); if (d < best) { best = d; ch = p } }
            g[rk][f] = ch
        }
        if (g.sumOf { rr -> rr.count { it == 'K' } } != 1 || g.sumOf { rr -> rr.count { it == 'k' } } != 1) return null
        return g
    }

    fun fen(g: Array<CharArray>, whiteToMove: Boolean): String {
        val place = (7 downTo 0).joinToString("/") { rk ->
            buildString {
                var n = 0
                for (f in 0..7) { if (g[rk][f] == '.') n++ else { if (n > 0) append(n); n = 0; append(g[rk][f]) } }
                if (n > 0) append(n)
            }
        }
        val c = buildString {
            if (g[0][4] == 'K' && g[0][7] == 'R') append('K'); if (g[0][4] == 'K' && g[0][0] == 'R') append('Q')
            if (g[7][4] == 'k' && g[7][7] == 'r') append('k'); if (g[7][4] == 'k' && g[7][0] == 'r') append('q')
        }.ifEmpty { "-" }
        return "$place ${if (whiteToMove) 'w' else 'b'} $c - 0 1"
    }
}
