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
        send("setoption name MultiPV value 2"); send("setoption name Threads value 2"); send("setoption name Hash value 64")
        send("isready"); waitFor("readyok")
    }

    private fun send(s: String) { w.write(s + "\n"); w.flush() }
    private fun waitFor(t: String) {
        while (true) { val l = r.readLine() ?: error("движок остановился"); if (l.startsWith(t)) return }
    }

    /** Две лучшие линии. Пустой список — ходов нет (мат/пат). */
    @Synchronized
    fun analyze(fen: String, ms: Int): List<Line> {
        send("position fen $fen"); send("go movetime $ms")
        val res = HashMap<Int, Line>()
        while (true) {
            val l = r.readLine() ?: break
            if (l.startsWith("bestmove")) break
            if (!l.startsWith("info") || " pv " !in l || " score " !in l) continue
            val t = l.split(" ")
            val mi = t.indexOf("multipv").let { if (it >= 0) t[it + 1].toInt() else 1 }
            val si = t.indexOf("score"); val v = t[si + 2].toInt()
            val cp = if (t[si + 1] == "mate") (if (v > 0) 10000 - v else -10000 - v) else v
            res[mi] = Line(cp, t.subList(t.indexOf("pv") + 1, t.size))
        }
        return res.toSortedMap().values.toList()
    }
    fun close() = proc.destroy()
}
