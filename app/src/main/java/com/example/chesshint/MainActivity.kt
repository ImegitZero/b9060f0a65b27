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
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ScrollView
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
        val prefs = getSharedPreferences("settings", MODE_PRIVATE)
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(48, 96, 48, 48) }
        col.addView(TextView(this).apply {
            textSize = 16f
            text = "1. Разреши показ поверх других окон.\n2. Нажми «Запустить» и разреши запись экрана.\n" +
                "3. Открой Chess.com, начни партию (или бота) и ОДИН РАЗ нажми 📚 на стартовой позиции.\n" +
                "4. Дальше всё автоматически. На панели: ♟ свернуть, ⏸ пауза, 📚 запомнить фигуры, ⇅ перевернуть.\n\n" +
                "Для тренировки, игры с ботами и разбора партий. В рейтинговых партиях подсказки запрещены правилами Chess.com."
        })
        fun cb(label: String, key: String) = CheckBox(this).apply {
            text = label; isChecked = prefs.getBoolean(key, true)
            setOnCheckedChangeListener { _, v -> prefs.edit().putBoolean(key, v).apply() }
        }
        col.addView(TextView(this).apply { text = "\nНастройки (меняются сразу, и на панели тоже):"; textSize = 15f })
        col.addView(cb("Показывать стрелку лучшего хода", "arrow"))
        col.addView(cb("Подсвечивать клетки хода", "squares"))
        col.addView(cb("Полоса оценки над доской", "bar"))
        col.addView(cb("Оценивать ходы соперника", "opp"))
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
        setContentView(ScrollView(this).apply { addView(col) })
    }
}
