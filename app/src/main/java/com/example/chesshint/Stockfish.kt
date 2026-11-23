package com.example.chesshint

import android.content.Context
import java.io.File

/** cp — оценка в сантипешках с точки зрения того, чей ход; мат кодируется как ±(10000 - N). */
class Line(val cp: Int, val pv: List<String>)

class Stockfish(ctx: Context) {
    private val proc = ProcessBuilder(File(ctx.applicationInfo.nativeLibraryDir, "libstockfish.so").path)
        .redirectErrorStream(true).start()
    private val w = proc.outputStream.bufferedWriter()
    private val r = proc.inputStream.bufferedReader()

    init {
        send("uci"); waitFor("uciok")
        send("setoption name MultiPV value 2"); send("setoption name Threads value 2"); send("setoption name Hash value 32")
        send("isready"); waitFor("readyok")
    }

    private fun send(s: String) = synchronized(w) {
        if (!proc.isAlive) throw java.io.IOException("движок не запущен")
        w.write(s + "\n"); w.flush()
    }
    private fun waitFor(t: String) {
        while (true) { val l = r.readLine() ?: error("движок остановился"); if (l.startsWith(t)) return }
    }

    /** Прервать текущий расчёт (вернёт то, что успел). */
    fun stop() = send("stop")

    /**
     * Две лучшие линии. early вызывается один раз, как только достигнута глубина 10 — это почти мгновенно.
     * Пустой список — ходов нет (мат/пат).
     */
    @Synchronized
    fun analyze(fen: String, ms: Int, early: ((List<Line>) -> Unit)? = null): List<Line> {
        send("position fen $fen"); send("go movetime $ms")
        val res = HashMap<Int, Line>(); var fired = false; var best: String? = null
        while (true) {
            val l = r.readLine() ?: throw java.io.IOException("движок остановился")
            if (l.startsWith("bestmove")) { best = l.split(" ").getOrNull(1); break }
            if (!l.startsWith("info") || " pv " !in l || " score " !in l) continue
            if (" lowerbound" in l || " upperbound" in l) continue          // неточные промежуточные оценки пропускаем
            val t = l.split(" ")
            val mi = t.indexOf("multipv").let { if (it >= 0) t[it + 1].toInt() else 1 }
            val depth = t.indexOf("depth").let { if (it >= 0) t[it + 1].toInt() else 0 }
            val si = t.indexOf("score"); val v = t[si + 2].toInt()
            val cp = if (t[si + 1] == "mate") (if (v > 0) 10000 - v else -10000 - v) else v
            res[mi] = Line(cp, t.subList(t.indexOf("pv") + 1, t.size))
            if (!fired && early != null && mi == 2 && depth >= 14) { fired = true; early(res.toSortedMap().values.toList()) }
        }
        val out = res.toSortedMap().values.toMutableList()
        // главный ход — всегда тот, что вернул движок в bestmove
        if (out.isNotEmpty() && best != null && best != "(none)" && out[0].pv.firstOrNull() != best) {
            val i = out.indexOfFirst { it.pv.firstOrNull() == best }
            if (i > 0) { val x = out.removeAt(i); out.add(0, x) } else out[0] = Line(out[0].cp, listOf(best))
        }
        return out
    }
    fun close() = proc.destroy()
}
