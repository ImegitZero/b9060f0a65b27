package com.example.chesshint

/** Проверки, чтобы не отдавать Stockfish заведомо нелегальную позицию (он на них может падать). */
object Legality {
    /** Стоит ли король цвета white под шахом (или рядом с чужим королём). */
    fun inCheck(g: Array<CharArray>, white: Boolean): Boolean {
        val k = if (white) 'K' else 'k'
        var kf = -1; var kr = -1
        for (r in 0..7) for (f in 0..7) if (g[r][f] == k) { kf = f; kr = r }
        if (kf < 0) return false
        fun at(f: Int, r: Int): Char = if (f in 0..7 && r in 0..7) g[r][f] else '#'
        fun enemy(c: Char, t: Char) = c == (if (white) t.lowercaseChar() else t.uppercaseChar())
        for ((df, dr) in listOf(1 to 2, 2 to 1, -1 to 2, -2 to 1, 1 to -2, 2 to -1, -1 to -2, -2 to -1))
            if (enemy(at(kf + df, kr + dr), 'N')) return true
        for (df in -1..1) for (dr in -1..1) if ((df != 0 || dr != 0) && enemy(at(kf + df, kr + dr), 'K')) return true
        val pr = if (white) kr + 1 else kr - 1
        if (enemy(at(kf - 1, pr), 'P') || enemy(at(kf + 1, pr), 'P')) return true
        for ((df, dr) in listOf(1 to 0, -1 to 0, 0 to 1, 0 to -1)) {
            var f = kf + df; var r = kr + dr
            while (at(f, r) == '.') { f += df; r += dr }
            val c = at(f, r); if (enemy(c, 'R') || enemy(c, 'Q')) return true
        }
        for ((df, dr) in listOf(1 to 1, 1 to -1, -1 to 1, -1 to -1)) {
            var f = kf + df; var r = kr + dr
            while (at(f, r) == '.') { f += df; r += dr }
            val c = at(f, r); if (enemy(c, 'B') || enemy(c, 'Q')) return true
        }
        return false
    }

    /** Позиция правдоподобна при данном ходе: пешки не на крайних рядах, не слишком много фигур, не-ходящий не под шахом. */
    fun valid(g: Array<CharArray>, turnWhite: Boolean): Boolean {
        for (f in 0..7) if (g[0][f].uppercaseChar() == 'P' || g[7][f].uppercaseChar() == 'P') return false
        for (white in listOf(true, false)) {
            var pawns = 0; var all = 0
            for (r in 0..7) for (f in 0..7) { val c = g[r][f]; if (c != '.' && c.isUpperCase() == white) { all++; if (c.uppercaseChar() == 'P') pawns++ } }
            if (pawns > 8 || all > 16) return false
        }
        return !inCheck(g, !turnWhite)
    }
}
