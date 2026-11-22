package com.example.chesshint

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts

class MainActivity : ComponentActivity() {
    private val projection = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        if (r.resultCode == Activity.RESULT_OK && r.data != null) {
            startForegroundService(Intent(this, OverlayService::class.java)
                .putExtra("code", r.resultCode).putExtra("data", r.data))
            moveTaskToBack(true) // уходим в фон: открывай Chess.com
        }
    }

    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        if (Build.VERSION.SDK_INT >= 33) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(48, 96, 48, 48) }
        col.addView(TextView(this).apply {
            textSize = 16f
            text = "1. Разреши показ поверх других окон.\n2. Нажми «Запустить» и разреши запись экрана.\n" +
                "3. Открой Chess.com, начни партию (или бота) и ОДИН РАЗ нажми 📚 на стартовой позиции — фигуры запомнятся навсегда.\n" +
                "4. Дальше всё автоматически: стрелка показывает лучший ход, а после каждого хода появляется оценка (!!, !, ★, ?!, ?, ??).\n\n" +
                "⏸ пауза, ⚪/⚫ чей ход (если вошёл в партию с середины), W↓/B↓ кто внизу доски, 👁 подсказки и на ходу соперника.\n\n" +
                "Для тренировки, игры с ботами и разбора партий. В рейтинговых партиях подсказки запрещены правилами Chess.com."
        })
        col.addView(Button(this).apply {
            text = "Запустить"
            setOnClickListener {
                if (!Settings.canDrawOverlays(this@MainActivity))
                    startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
                else projection.launch(getSystemService(MediaProjectionManager::class.java).createScreenCaptureIntent())
            }
        })
        col.addView(Button(this).apply {
            text = "Остановить"
            setOnClickListener { stopService(Intent(this@MainActivity, OverlayService::class.java)) }
        })
        setContentView(col)
    }
}
