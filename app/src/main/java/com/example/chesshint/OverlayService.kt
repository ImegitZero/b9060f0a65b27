package com.example.chesshint

import android.app.*
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import java.util.concurrent.Executors
import kotlin.math.abs

/**
 * Работает по событиям: пока на доске ничего не меняется — почти ничего не делает.
 * Подсказка показывается только на твоём ходу. Состояние самовосстанавливается:
 * если для текущей позиции нет результата — расчёт повторяется сам.
 */
class OverlayService : Service() {
    private lateinit var wm: WindowManager
    private var projection: MediaProjection? = null
    private var reader: ImageReader? = null
    private var display: VirtualDisplay? = null
    private var held: Image? = null
    private lateinit var arrow: ArrowView
    private lateinit var panel: LinearLayout
    private lateinit var menu: LinearLayout
    private lateinit var evalChip: TextView
    private lateinit var qualityView: TextView
    private lateinit var rec: BoardRecognizer
    @Volatile private var eng: Stockfish? = null
    private fun engine() = eng ?: Stockfish(this).also { eng = it }
    private val bg = Executors.newSingleThreadExecutor()
    private val ui = Handler(Looper.getMainLooper())

    @Volatile private var running = true
    @Volatile private var flipped = false       // true — чёрные внизу (определяется автоматически)
    @Volatile private var orientLocked = false  // true — ориентацию задал вручную кнопкой ⇅
    private var errShown = false

    // только поток UI
    private var busy = false
    private var dirty = false
    private var prevSample: IntArray? = null
    private var sampleRect: Rect? = null

    // только поток bg
    private var curGrid: Array<CharArray>? = null
    private var curTurn = true                  // чей ход в curGrid
    private var curPlayed: Played? = null       // ход, приведший к curGrid
    private var curBefore: List<Line>? = null   // оценка позиции до этого хода
    private var curAnalysis: List<Line>? = null
    private var analyzed = false                // есть ли уже результат для curGrid
    private var unplausible: Array<CharArray>? = null
    private val hist = ArrayList<Pair<Array<CharArray>, Boolean>>()   // для «Отменить ход»
    private var misses = 0

    override fun onBind(i: Intent?): IBinder? = null

    override fun onStartCommand(i: Intent?, f: Int, id: Int): Int {
        if (i == null) { stopSelf(); return START_NOT_STICKY }
        val ch = "chesshint"
        getSystemService(NotificationManager::class.java)
            .createNotificationChannel(NotificationChannel(ch, "ChessHint", NotificationManager.IMPORTANCE_LOW))
        startForeground(1, Notification.Builder(this, ch).setContentTitle("ChessHint работает")
            .setSmallIcon(android.R.drawable.ic_menu_view).build(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        @Suppress("DEPRECATION")
        val data = i.getParcelableExtra<Intent>("data")!!
        projection = getSystemService(MediaProjectionManager::class.java).getMediaProjection(i.getIntExtra("code", 0), data)
        projection!!.registerCallback(object : MediaProjection.Callback() { override fun onStop() { stopSelf() } }, ui)
        wm = getSystemService(WindowManager::class.java)
        rec = BoardRecognizer(getSharedPreferences("tpl", MODE_PRIVATE))
        setupCapture(); setupOverlay()
        bg.execute { runCatching { engine() } }   // прогреваем движок заранее
        ui.postDelayed(tick, 800)
        return START_NOT_STICKY
    }

    // ---------- захват экрана и детектор изменений ----------
    private fun setupCapture() {
        val dm = DisplayMetrics()
        @Suppress("DEPRECATION") wm.defaultDisplay.getRealMetrics(dm)
        reader = ImageReader.newInstance(dm.widthPixels, dm.heightPixels, PixelFormat.RGBA_8888, 3)
        reader!!.setOnImageAvailableListener({ r ->
            val im = r.acquireLatestImage() ?: return@setOnImageAvailableListener
            held?.close(); held = im
            val b = rec.board
            if (running && b != null) {
                if (sampleRect != b) { sampleRect = Rect(b); prevSample = null }
                val cur = sample(im, b); val prev = prevSample; prevSample = cur
                if (prev != null) {
                    var n = 0
                    for (k in cur.indices) if (abs(cur[k] - prev[k]) > 24) n++
                    if (n >= 3) kick()
                }
            }
        }, ui)
        display = projection!!.createVirtualDisplay("chesshint", dm.widthPixels, dm.heightPixels, dm.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, reader!!.surface, null, null)
    }

    /** Грубый «отпечаток» области доски: 48x48 точек яркости, читаем прямо из буфера кадра. */
    private fun sample(im: Image, r: Rect): IntArray {
        val pl = im.planes[0]; val buf = pl.buffer; val rs = pl.rowStride; val ps = pl.pixelStride
        val k = 48; val out = IntArray(k * k)
        for (j in 0 until k) {
            val y = minOf(r.top + ((j + 0.5f) * r.height() / k).toInt(), im.height - 1)
            for (i in 0 until k) {
                val x = minOf(r.left + ((i + 0.5f) * r.width() / k).toInt(), im.width - 1)
                val o = y * rs + x * ps
                out[j * k + i] = ((buf.get(o).toInt() and 255) + 2 * (buf.get(o + 1).toInt() and 255) + (buf.get(o + 2).toInt() and 255)) / 4
            }
        }
        return out
    }

    private fun grab(): Bitmap? {
        val im = held ?: return null
        val pl = im.planes[0]; pl.buffer.rewind()
        val full = Bitmap.createBitmap(pl.rowStride / pl.pixelStride, im.height, Bitmap.Config.ARGB_8888)
        full.copyPixelsFromBuffer(pl.buffer)
        val out = Bitmap.createBitmap(full, 0, 0, im.width, im.height)
        if (out !== full) full.recycle()
        return out
    }

    // ---------- запуск распознавания ----------
    private val go = Runnable { recognize() }
    private fun kick() { ui.removeCallbacks(go); ui.postDelayed(go, 80) }   // ждём конца анимации хода

    private val tick = object : Runnable {   // страховка: раз в секунду проверяем, даже если событий не было
        override fun run() { if (running && !busy) recognize(); ui.postDelayed(this, 1000) }
    }

    private fun recognize() {
        if (!running) return
        if (busy) { dirty = true; return }
        val bmp = grab() ?: return
        busy = true
        bg.execute {
            try { process(bmp) }
            catch (e: Exception) { if (!errShown) { errShown = true; say("Ошибка: ${e.message}") } }
            finally { bmp.recycle(); ui.post { busy = false; if (dirty) { dirty = false; kick() } } }
        }
    }

    // ---------- логика ----------
    private fun same(a: Array<CharArray>, b: Array<CharArray>) = a.contentDeepEquals(b)

    /** Если белые фигуры «в среднем выше» чёрных — доска прочитана перевёрнутой. */
    private fun whiteIsHigh(g: Array<CharArray>): Boolean {
        var ws = 0; var wn = 0; var bs = 0; var bn = 0
        for (rk in 0..7) for (f in 0..7) {
            val c = g[rk][f]; if (c == '.') continue
            if (c.isUpperCase()) { ws += rk; wn++ } else { bs += rk; bn++ }
        }
        return wn > 0 && bn > 0 && ws.toDouble() / wn > bs.toDouble() / bn + 1.0
    }
    private fun rotate(g: Array<CharArray>) = Array(8) { rk -> CharArray(8) { f -> g[7 - rk][7 - f] } }
    private fun fixOrientation(g: Array<CharArray>): Array<CharArray> =
        if (!orientLocked && whiteIsHigh(g)) { flipped = !flipped; rotate(g) } else g

    private fun fmt(cp: Int) = if (abs(cp) >= 9000) (if (cp > 0) "+" else "-") + "M" + (10000 - abs(cp))
                               else String.format("%+.1f", cp / 100.0)

    private fun process(bmp: Bitmap) {
        if (!rec.learned) { setMsg("Нажми 📚 на стартовой позиции"); return }
        val raw = rec.grid(bmp, flipped)
        if (raw == null) {
            if (++misses == 3) { curGrid = null; analyzed = false; hist.clear(); ui.post { arrow.clear() }; setMsg("Доска не найдена") }
            return
        }
        misses = 0
        var g: Array<CharArray> = raw
        val cur = curGrid
        if (cur == null) g = fixOrientation(g)

        if (cur == null || !same(cur, g)) {
            val start = rec.isStart(g)
            var played: Played? = null
            var before: List<Line>? = null
            var turn: Boolean
            val take = if (cur != null && !start) hist.indexOfLast { same(it.first, g) } else -1
            if (take >= 0) {                                   // «Отменить ход» — возвращаемся к прошлой позиции
                turn = hist[take].second
                hist.subList(take + 1, hist.size).clear()
            } else {
                played = if (cur != null && !start) MoveClassifier.diff(cur, g) else null
                if (cur != null && !start && played == null) { // не один ход — подтверждаем повторным кадром
                    val u = unplausible
                    if (u == null || !same(u, g)) { unplausible = g; ui.postDelayed(go, 150); return }
                }
                if (played != null) {
                    turn = !played.white
                    if (played.white == curTurn) before = curAnalysis
                } else {                                        // новая партия, вход с середины или «пересинхронизация»
                    g = fixOrientation(g)
                    turn = if (rec.isStart(g)) true else !flipped   // неизвестно чей ход — считаем, что твой
                    hist.clear()
                }
                hist.add(Pair(g, turn)); if (hist.size > 12) hist.removeAt(0)
            }
            if (!Legality.valid(g, turn)) {
                if (Legality.valid(g, !turn)) turn = !turn
                else { ui.post { arrow.clear() }; setMsg("Позиция не распознана"); return }
            }
            unplausible = null
            curGrid = g; curTurn = turn; curPlayed = played; curBefore = before; curAnalysis = null; analyzed = false
            if (hist.isNotEmpty()) hist[hist.size - 1] = Pair(g, turn)
        } else {
            unplausible = null
            if (analyzed) return        // всё актуально
        }                               // иначе: позиция та же, но результата нет — считаем ещё раз (самовосстановление)

        val grid = curGrid ?: return
        val turnW = curTurn; val pl = curPlayed; val bef = curBefore
        val board = rec.board ?: return

        fun render(lines: List<Line>) {
            val badge = if (pl != null && !bef.isNullOrEmpty()) MoveClassifier.classify(pl, grid, bef, lines) else null
            val mv = lines[0].pv.firstOrNull()
            val fl = flipped
            val show = if (turnW == !fl) mv else null           // подсказка — только на твоём ходу
            val knight = show != null && grid[show[1] - '1'][show[0] - 'a'].uppercaseChar() == 'N'
            val cpW = if (turnW) lines[0].cp else -lines[0].cp
            val who = if (pl == null) "" else if (pl.white == !fl) "Ты: " else "Соперник: "
            val win = MoveClassifier.win(cpW).toFloat()
            ui.post {
                arrow.update(board, fl, show, knight, pl?.to, badge, win)
                setStatus(fmt(cpW), cpW >= 0, badge?.let { who + it.ru }, badge?.color)
            }
        }

        val lines = analyzeSafe(rec.fen(grid, turnW)) { early -> curAnalysis = early; analyzed = true; render(early) }
        if (lines == null) {                                    // движок не поднялся — сбросим и попробуем снова
            curGrid = null; hist.clear(); setMsg("Движок перезапускается…"); ui.postDelayed(go, 700); return
        }
        if (lines.isEmpty()) { analyzed = true; ui.post { arrow.clear() }; setMsg("Партия окончена"); return }
        curAnalysis = lines; analyzed = true
        render(lines)
    }

    /** Если Stockfish умер — пересоздаём процесс и повторяем один раз. null — не получилось. */
    private fun analyzeSafe(fen: String, early: (List<Line>) -> Unit): List<Line>? {
        for (attempt in 0..1) {
            try { val l = engine().analyze(fen, 400, early); errShown = false; return l }
            catch (e: Exception) { runCatching { eng?.close() }; eng = null }
        }
        return null
    }

    private fun reset() = bg.execute {
        curGrid = null; hist.clear(); unplausible = null; analyzed = false
        ui.post { kick() }
    }

    private fun learn() {
        val bmp = grab() ?: run { say("Нет кадра"); return }
        bg.execute {
            rec.whiteAtBottom(bmp)?.let { flipped = !it }
            orientLocked = false
            say(if (rec.learn(bmp, flipped)) "Фигуры запомнены ✔" else "Не нашёл доску: нужна стартовая позиция и зелёная тема")
            bmp.recycle()
            curGrid = null; hist.clear(); unplausible = null; analyzed = false
            ui.post { kick() }
        }
    }

    // ---------- интерфейс ----------
    private fun say(s: String) { ui.post { Toast.makeText(this, s, Toast.LENGTH_LONG).show() } }
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun setMsg(t: String) {
        ui.post { evalChip.visibility = View.GONE; qualityView.text = t; qualityView.setTextColor(Color.WHITE) }
    }

    private fun setStatus(eval: String, whiteAhead: Boolean, quality: String?, color: Int?) {
        evalChip.visibility = View.VISIBLE; evalChip.text = eval
        evalChip.setTextColor(if (whiteAhead) Color.BLACK else Color.WHITE)
        (evalChip.background as GradientDrawable).setColor(if (whiteAhead) 0xFFF0F0F0.toInt() else 0xFF2B2B2B.toInt())
        qualityView.text = quality ?: ""
        qualityView.setTextColor(color ?: Color.WHITE)
    }

    private fun icon(t: String, a: (TextView) -> Unit) = TextView(this).apply {
        text = t; textSize = 16f; gravity = Gravity.CENTER; setTextColor(Color.WHITE)
        background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0x33FFFFFF) }
        layoutParams = LinearLayout.LayoutParams(dp(38), dp(38)).apply { setMargins(dp(3), dp(3), dp(3), dp(3)) }
        setOnClickListener { a(this) }
    }

    private fun setupOverlay() {
        arrow = ArrowView(this)
        wm.addView(arrow, WindowManager.LayoutParams(-1, -1, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT))

        evalChip = TextView(this).apply {
            textSize = 14f; typeface = android.graphics.Typeface.DEFAULT_BOLD; visibility = View.GONE
            setPadding(dp(9), dp(3), dp(9), dp(3))
            background = GradientDrawable().apply { cornerRadius = dp(10).toFloat() }
            layoutParams = LinearLayout.LayoutParams(-2, -2).apply { setMargins(dp(4), 0, dp(6), 0) }
        }
        qualityView = TextView(this).apply {
            textSize = 13f; typeface = android.graphics.Typeface.DEFAULT_BOLD; setTextColor(Color.WHITE)
            text = "ChessHint"; setPadding(dp(4), 0, dp(8), 0)
        }
        menu = LinearLayout(this).apply {
            addView(icon("⏸") { t -> running = !running; t.text = if (running) "⏸" else "▶"; if (running) kick() else arrow.clear() })
            addView(icon("📚") { learn() })
            addView(icon("⇅") { flipped = !flipped; orientLocked = true; arrow.clear(); reset() })
            addView(icon("✕") { stopSelf() })
        }
        val head = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(icon("♟") { menu.visibility = if (menu.visibility == View.VISIBLE) View.GONE else View.VISIBLE })
            addView(evalChip); addView(qualityView)
        }
        panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(4), dp(2), dp(4), dp(2))
            background = GradientDrawable().apply { setColor(0xD9141618.toInt()); cornerRadius = dp(22).toFloat() }
            addView(head); addView(menu)
        }
        wm.addView(panel, WindowManager.LayoutParams(-2, -2, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT)
            .apply { gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL; y = dp(8) })
    }

    override fun onDestroy() {
        ui.removeCallbacks(tick); ui.removeCallbacks(go)
        runCatching { wm.removeView(arrow); wm.removeView(panel) }
        held?.close(); display?.release(); reader?.close(); projection?.stop()
        runCatching { eng?.close() }
        super.onDestroy()
    }
}
