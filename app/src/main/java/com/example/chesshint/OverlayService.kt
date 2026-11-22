package com.example.chesshint

import android.app.*
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import java.util.concurrent.Executors
import kotlin.math.abs

class OverlayService : Service() {
    private lateinit var wm: WindowManager
    private var projection: MediaProjection? = null
    private var reader: ImageReader? = null
    private var display: VirtualDisplay? = null
    private var held: Image? = null
    private var frameSeq = 0
    private lateinit var arrow: ArrowView
    private lateinit var panel: LinearLayout
    private lateinit var label: TextView
    private lateinit var rec: BoardRecognizer
    private val engine by lazy { Stockfish(this) }
    private val bg = Executors.newSingleThreadExecutor()
    private val ui = Handler(Looper.getMainLooper())

    @Volatile private var running = true
    @Volatile private var busy = false
    @Volatile private var flipped = false     // true — чёрные внизу
    @Volatile private var showBoth = false    // подсказка и на ходу соперника
    @Volatile private var manualWhite = true  // чей ход, если вошли в партию с середины
    private var errShown = false

    // состояние анализа трогаем только из потока bg
    private var lastGrid: Array<CharArray>? = null
    private var pending: Array<CharArray>? = null
    private var lastAnalysis: List<Line>? = null
    private var lastTurnWhite = true
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
        ui.postDelayed(tick, 1000)
        return START_NOT_STICKY
    }

    // ---------- захват экрана ----------
    private fun setupCapture() {
        val dm = DisplayMetrics()
        @Suppress("DEPRECATION") wm.defaultDisplay.getRealMetrics(dm)
        reader = ImageReader.newInstance(dm.widthPixels, dm.heightPixels, PixelFormat.RGBA_8888, 3)
        reader!!.setOnImageAvailableListener({ r ->
            val im = r.acquireLatestImage() ?: return@setOnImageAvailableListener
            held?.close(); held = im; frameSeq++
        }, ui)
        display = projection!!.createVirtualDisplay("chesshint", dm.widthPixels, dm.heightPixels, dm.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, reader!!.surface, null, null)
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

    /** Кадр без нашей стрелки (и без панели, если hidePanel), чтобы они не мешали распознаванию. */
    private fun capture(hidePanel: Boolean, cb: (Bitmap?) -> Unit) {
        val hideArrow = arrow.hasContent
        val hide = hideArrow || hidePanel
        val seq0 = frameSeq; val t0 = SystemClock.uptimeMillis(); var settled = false
        if (hideArrow) arrow.visibility = View.INVISIBLE
        if (hidePanel) panel.visibility = View.INVISIBLE
        val step = object : Runnable {
            override fun run() {
                if (hide && frameSeq == seq0 && SystemClock.uptimeMillis() - t0 < 300) { ui.postDelayed(this, 30); return }
                if (hide && !settled) { settled = true; ui.postDelayed(this, 60); return }
                val bmp = grab()
                if (hideArrow) arrow.visibility = View.VISIBLE
                if (hidePanel) panel.visibility = View.VISIBLE
                cb(bmp)
            }
        }
        ui.post(step)
    }

    // ---------- основной цикл ----------
    private val tick = object : Runnable {
        override fun run() { if (running && !busy) poll(); ui.postDelayed(this, 500) }
    }

    private fun poll() {
        busy = true
        capture(false) { bmp ->
            if (bmp == null) { busy = false; return@capture }
            bg.execute {
                try { process(bmp) }
                catch (e: Exception) { if (!errShown) { errShown = true; say("Ошибка: ${e.message} (есть ли libstockfish.so?)") } }
                finally { bmp.recycle(); busy = false }
            }
        }
    }

    private fun same(a: Array<CharArray>, b: Array<CharArray>) = a.contentDeepEquals(b)

    private fun fmt(cp: Int) = if (abs(cp) >= 9000) (if (cp > 0) "+" else "-") + "M" + (10000 - abs(cp))
                               else String.format("%+.1f", cp / 100.0)

    private fun process(bmp: Bitmap) {
        if (!rec.learned) { ui.post { label.text = "Нажми 📚 на стартовой позиции" }; return }
        val g = rec.grid(bmp, flipped)
        if (g == null) {
            if (++misses == 4) { lastGrid = null; lastAnalysis = null; ui.post { arrow.clear(); label.text = "Доска не найдена" } }
            return
        }
        misses = 0
        val prev = lastGrid
        if (prev != null && same(prev, g)) { pending = null; return }
        val pd = pending
        if (pd == null || !same(pd, g)) { pending = g; return }   // ждём, пока позиция устоится (анимация хода)
        pending = null

        val played = if (prev != null) MoveClassifier.diff(prev, g) else null
        val turnWhite = when {
            prev == null -> if (rec.isStart(g)) true else manualWhite
            played != null -> !played.white
            else -> !lastTurnWhite
        }
        val lines = engine.analyze(rec.fen(g, turnWhite), 700)
        val board = rec.board ?: return
        val before = lastAnalysis
        lastGrid = g; lastAnalysis = lines; lastTurnWhite = turnWhite
        if (lines.isEmpty()) { ui.post { arrow.clear(); label.text = "Партия окончена" }; return }

        val badge = if (played != null && !before.isNullOrEmpty()) MoveClassifier.classify(played, g, before, lines) else null
        val mv = lines[0].pv.firstOrNull()
        val show = if (turnWhite == !flipped || showBoth) mv else null
        val knight = show != null && g[show[1] - '1'][show[0] - 'a'].uppercaseChar() == 'N'
        val who = if (played == null) "" else if (played.white == !flipped) "Твой ход: " else "Соперник: "
        val text = fmt(if (turnWhite) lines[0].cp else -lines[0].cp) + (badge?.let { "  •  $who${it.ru}" } ?: "")
        ui.post { arrow.update(board, flipped, show, knight, played?.to, badge); label.text = text }
    }

    private fun reset() = bg.execute { lastGrid = null; pending = null; lastAnalysis = null }

    private fun learn() = capture(true) { bmp ->
        if (bmp == null) say("Нет кадра") else bg.execute {
            say(if (rec.learn(bmp, flipped)) "Фигуры запомнены ✔" else "Не нашёл доску: нужна стартовая позиция и зелёная тема")
            lastGrid = null; pending = null; lastAnalysis = null; bmp.recycle()
        }
    }

    // ---------- интерфейс ----------
    private fun say(s: String) = ui.post { Toast.makeText(this, s, Toast.LENGTH_LONG).show() }

    private fun btn(t: String, a: (Button) -> Unit): Button {
        val d = resources.displayMetrics.density
        return Button(this).apply {
            text = t; textSize = 12f; minWidth = 0; minimumWidth = 0; setPadding(0, 0, 0, 0)
            layoutParams = LinearLayout.LayoutParams((46 * d).toInt(), (46 * d).toInt())
            setOnClickListener { a(this) }
        }
    }

    private fun setupOverlay() {
        arrow = ArrowView(this)
        wm.addView(arrow, WindowManager.LayoutParams(-1, -1, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT))

        val row = LinearLayout(this)
        row.addView(btn("⏸") { b -> running = !running; b.text = if (running) "⏸" else "▶"; if (!running) arrow.clear() })
        row.addView(btn("📚") { learn() })
        row.addView(btn("⚪") { b -> manualWhite = !manualWhite; b.text = if (manualWhite) "⚪" else "⚫"; reset() })
        row.addView(btn("W↓") { b -> flipped = !flipped; b.text = if (flipped) "B↓" else "W↓"; arrow.clear(); reset() })
        row.addView(btn("👁") { b -> showBoth = !showBoth; b.text = if (showBoth) "👁2" else "👁"; reset() })
        row.addView(btn("✕") { stopSelf() })
        label = TextView(this).apply {
            setTextColor(Color.WHITE); setBackgroundColor(0xAA000000.toInt()); textSize = 13f
            setPadding(16, 6, 16, 6); text = "ChessHint"
        }
        panel = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; alpha = 0.9f; addView(row); addView(label) }
        wm.addView(panel, WindowManager.LayoutParams(-2, -2, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT)
            .apply { gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL; y = 30 })
    }

    override fun onDestroy() {
        ui.removeCallbacks(tick)
        runCatching { wm.removeView(arrow); wm.removeView(panel) }
        held?.close(); display?.release(); reader?.close(); projection?.stop()
        runCatching { engine.close() }
        super.onDestroy()
    }
}
