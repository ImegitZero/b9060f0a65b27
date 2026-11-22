package com.example.chesshint

import kotlin.math.exp

class Played(val from: String, val to: String, val white: Boolean, val piece: Char, val captured: Char) {
    val uci get() = from + to
}

/** Оценка хода по потере вероятности выигрыша (как в разборе партий). Эвристика, не точная копия Chess.com. */
object MoveClassifier {
    fun win(cp: Int) = 50.0 + 50.0 * (2.0 / (1.0 + exp(-0.00368208 * cp)) - 1.0)
    fun value(c: Char) = when (c.uppercaseChar()) { 'P' -> 1; 'N', 'B' -> 3; 'R' -> 5; 'Q' -> 9; else -> 0 }
    private fun sq(f: Int, r: Int) = "${'a' + f}${r + 1}"

    /** Какой ход превратил позицию a в b (null — не похоже на один ход). */
    fun diff(a: Array<CharArray>, b: Array<CharArray>): Played? {
        val ch = ArrayList<Pair<Int, Int>>()
        for (r in 0..7) for (f in 0..7) if (a[r][f] != b[r][f]) ch.add(Pair(f, r))
        if (ch.size !in 2..4) return null
        val dests = ch.filter { b[it.second][it.first] != '.' }
        val origins = ch.filter { a[it.second][it.first] != '.' && b[it.second][it.first] == '.' }
        if (dests.isEmpty() || origins.isEmpty()) return null
        val d = dests.firstOrNull { b[it.second][it.first].uppercaseChar() == 'K' } ?: dests[0]
        val dp = b[d.second][d.first]; val white = dp.isUpperCase()
        val o = origins.firstOrNull {
            val p = a[it.second][it.first]
            p.isUpperCase() == white && (p.uppercaseChar() == dp.uppercaseChar() || p.uppercaseChar() == 'P')
        } ?: return null
        val cap = a[d.second][d.first]
        if (cap != '.' && cap.isUpperCase() == white) return null
        return Played(sq(o.first, o.second), sq(d.first, d.second), white, dp, cap)
    }

    fun classify(p: Played, g: Array<CharArray>, before: List<Line>, after: List<Line>): Badge {
        val best = before[0]; val cpAfter = -after[0].cp
        val wpBest = win(best.cp); val loss = maxOf(0.0, wpBest - win(cpAfter))
        val isBest = best.pv.firstOrNull()?.take(4) == p.uci

        // Блестящий: почти лучший ход, жертва фигуры, позиция не проиграна и не была уже выиграна
        val pv0 = after[0].pv.getOrNull(0); val pv1 = after[0].pv.getOrNull(1)
        val pieceVal = value(p.piece)
        if (loss <= 2.0 && pieceVal >= 3 && pv0 != null && pv0.substring(2, 4) == p.to) {
            val attacker = value(g[pv0[1] - '1'][pv0[0] - 'a'])
            val net = value(p.captured) + (if (pv1?.substring(2, 4) == p.to) attacker else 0) - pieceVal
            if (net <= -2 && cpAfter > -50 && wpBest < 90) return Badge("!!", 0xFF1BACA6.toInt(), "Блестящий ход")
        }
        // Сильный: единственный хороший ход
        if (isBest && before.size > 1 && wpBest - win(before[1].cp) >= 12 && wpBest < 95)
            return Badge("!", 0xFF5B8BB0.toInt(), "Сильный ход")
        return when {
            isBest || loss <= 0.5 -> Badge("★", 0xFF7FA650.toInt(), "Лучший ход")
            loss <= 2 -> Badge("✓", 0xFF96BC4B.toInt(), "Отличный ход")
            loss <= 5 -> Badge("✓", 0xFF97AF8B.toInt(), "Хороший ход")
            loss <= 10 -> Badge("?!", 0xFFF7C631.toInt(), "Неточность")
            loss <= 20 -> Badge("?", 0xFFE58F2A.toInt(), "Ошибка")
            else -> Badge("??", 0xFFCA3431.toInt(), "Грубая ошибка")
        }
    }
}
