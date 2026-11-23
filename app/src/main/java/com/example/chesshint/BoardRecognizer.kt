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
    @Volatile var board: Rect? = null
    private val N = 24
    private val pieces = "PNBRQKpnbrqk"
    private val tpl = HashMap<Char, MutableList<IntArray>>()
    private val blank = IntArray(N * N) { 128 }

    init { load() }
    val learned get() = pieces.all { tpl[it]?.isNotEmpty() == true }

    private fun save() = prefs.edit().putString("t2", tpl.entries.joinToString("\n") { e ->
        e.value.joinToString("\n") { "${e.key}:${it.joinToString(",")}" } }).apply()

    private fun load() {
        prefs.getString("t2", null)?.lines()?.forEach { l ->
            if (l.length > 2) tpl.getOrPut(l[0]) { ArrayList() }.add(l.substring(2).split(",").map { it.toInt() }.toIntArray())
        }
    }

    private fun isBoardColor(c: Int): Boolean {
        val r = Color.red(c); val g = Color.green(c); val b = Color.blue(c)
        return (abs(r - 238) < 6 && abs(g - 238) < 6 && abs(b - 210) < 6) ||
               (abs(r - 118) < 6 && abs(g - 150) < 6 && abs(b - 86) < 6)
    }

    /** Самая длинная полоса true, допускающая разрывы до gap (швы между клетками). */
    private fun longest(ok: BooleanArray, gap: Int): IntArray? {
        var bs = -1; var be = -1; var bl = -1; var s = -1; var p = -1
        for (i in ok.indices) if (ok[i]) {
            if (s < 0) s = i
            else if (i - p > gap + 1) { if (p - s > bl) { bs = s; be = p; bl = p - s }; s = i }
            p = i
        }
        if (s >= 0 && p - s > bl) { bs = s; be = p }
        return if (bs < 0) null else intArrayOf(bs, be)
    }

    /** Быстрый путь: если доска на прежнем месте — не сканируем весь экран. */
    fun boardFor(b: Bitmap): Rect? {
        val r = board
        if (r != null && stillThere(b, r)) return r
        return locate(b)
    }

    private fun stillThere(b: Bitmap, r: Rect): Boolean {
        if (r.right > b.width || r.bottom > b.height || r.top < 5 || r.bottom + 5 >= b.height) return false
        val pts = listOf(r.left + 4 to r.top + 4, r.right - 5 to r.top + 4, r.left + 4 to r.bottom - 5, r.right - 5 to r.bottom - 5)
        if (pts.count { isBoardColor(b.getPixel(it.first, it.second)) } < 2) return false
        return !isBoardColor(b.getPixel(r.left + 4, r.top - 4)) && !isBoardColor(b.getPixel(r.left + 4, r.bottom + 3))
    }

    /** Ищем доску по плотности «шахматных» цветов, поэтому лицо бота, логотип и т.п. не мешают. */
    fun locate(b: Bitmap): Rect? {
        val w = b.width; val h = b.height; val px = IntArray(w)
        val rc = IntArray(h); var mx = 0
        for (y in 0 until h) {
            b.getPixels(px, 0, w, 0, y, w, 1)
            var n = 0; var x = 0
            while (x < w) { if (isBoardColor(px[x])) n++; x += 2 }
            rc[y] = n; if (n > mx) mx = n
        }
        if (mx < w / 8) return null
        val ys = longest(BooleanArray(h) { rc[it] >= mx / 4 }, 4) ?: return null
        val cc = IntArray(w)
        for (y in ys[0]..ys[1]) {
            b.getPixels(px, 0, w, 0, y, w, 1)
            for (x in 0 until w) if (isBoardColor(px[x])) cc[x]++
        }
        val th = (ys[1] - ys[0] + 1) / 4
        val xs = longest(BooleanArray(w) { cc[it] >= th }, 4) ?: return null
        val bw = xs[1] - xs[0] + 1; val bh = ys[1] - ys[0] + 1
        if (bw < w / 3 || abs(bw - bh) > bw / 16) return null
        val s = minOf((bw + bh) / 2, w - xs[0], h - ys[0])
        return Rect(xs[0], ys[0], xs[0] + s, ys[0] + s).also { board = it }
    }

    /** Сигнатура клетки: края обрезаны (устойчиво к сдвигу сетки на пару пикселей), фон вычтен. */
    private fun sig(b: Bitmap, r: Rect, row: Int, col: Int): IntArray {
        val s = r.width() / 8f
        val ins = (s * 0.075f).toInt()
        val x = (r.left + col * s).toInt() + ins; val y = (r.top + row * s).toInt() + ins
        val sz = minOf(s.toInt() - 2 * ins, b.width - x, b.height - y)
        if (sz < 8) return blank
        val sm = Bitmap.createScaledBitmap(Bitmap.createBitmap(b, x, y, sz, sz), N, N, true)
        fun lum(px: Int, py: Int): Int { val c = sm.getPixel(px, py); return (Color.red(c) * 3 + Color.green(c) * 6 + Color.blue(c)) / 10 }
        val k = intArrayOf(lum(1, 1), lum(N - 2, 1), lum(1, N - 2), lum(N - 2, N - 2),
            lum(1, N / 2), lum(N / 2, 1), lum(N - 2, N / 2)).sorted()
        val bg = k[3]
        return IntArray(N * N) { val v = lum(it % N, it / N); if (abs(v - bg) < 10) 128 else v }
    }

    private fun dist(a: IntArray, b: IntArray): Int { var d = 0; for (i in a.indices) d += abs(a[i] - b[i]); return d }

    // клетка экрана -> (файл 0..7, горизонталь 0..7)
    private fun fr(row: Int, col: Int, flipped: Boolean) = if (flipped) Pair(7 - col, row) else Pair(col, 7 - row)

    private fun startPiece(f: Int, rk: Int): Char? = when (rk) {
        0 -> "RNBQKBNR"[f]; 1 -> 'P'; 6 -> 'p'; 7 -> "rnbqkbnr"[f]; else -> null
    }

    fun isStart(g: Array<CharArray>) = (0..7).all { rk -> (0..7).all { f -> g[rk][f] == (startPiece(f, rk) ?: '.') } }

    /** На стартовой позиции: белые фигуры внизу? (по яркости фигур в нижних и верхних рядах) */
    fun whiteAtBottom(b: Bitmap): Boolean? {
        val r = locate(b) ?: return null
        fun avg(rows: IntRange): Double {
            var sum = 0L; var n = 0
            for (row in rows) for (col in 0..7) for (v in sig(b, r, row, col)) if (v != 128) { sum += v; n++ }
            return if (n == 0) 0.0 else sum.toDouble() / n
        }
        return avg(6..7) > avg(0..1)
    }

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
        val r = boardFor(b) ?: return null
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
