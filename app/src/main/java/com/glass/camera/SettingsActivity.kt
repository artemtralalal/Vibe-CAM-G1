package com.glass.camera

import android.graphics.Color
import android.os.Bundle
import android.os.Environment
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.glass.camera.databinding.ActivitySettingsBinding
import java.io.File

class SettingsActivity : AppCompatActivity() {
    private lateinit var b: ActivitySettingsBinding
    private val prefs by lazy { getSharedPreferences("glass", MODE_PRIVATE) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(b.root)

        b.styleDark.setOnClickListener { prefs.edit().putString("style", "dark").apply(); toast("Тёмный стиль") }
        b.styleLight.setOnClickListener { prefs.edit().putString("style", "light").apply(); toast("Светлый стиль") }
        b.applyColor.setOnClickListener {
            val c = b.customColor.text.toString().trim()
            try { Color.parseColor(c); prefs.edit().putString("customColor", c).putString("style", "custom").apply(); toast("Цвет применён") }
            catch (_: Exception) { toast("Неверный цвет") }
        }
        b.accentBlue.setOnClickListener { setAccent("#7C8CFF") }
        b.accentPink.setOnClickListener { setAccent("#FF7CA8") }
        b.accentGreen.setOnClickListener { setAccent("#4DDC88") }
        b.pickSound.setOnClickListener { showSoundPicker() }
        b.soundStatus.text = prefs.getString("shutterSound", "По умолчанию") ?: "По умолчанию"
    }

    private fun setAccent(c: String) { prefs.edit().putString("accent", c).apply(); toast("Акцент: $c") }

    private fun showSoundPicker() {
        val roots = listOf(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC),
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            Environment.getExternalStorageDirectory()
        )
        val files = mutableListOf<File>()
        roots.filter { it.exists() }.forEach { dir ->
            dir.walkTopDown().maxDepth(3).forEach { f ->
                if (f.isFile && f.extension.lowercase() in listOf("mp3", "wav", "ogg", "m4a")) files.add(f)
            }
        }
        if (files.isEmpty()) { toast("Аудиофайлы не найдены"); return }
        val names = files.map { it.name }.toTypedArray()
        val dlg = AlertDialog.Builder(this)
            .setTitle("Выбери звук затвора")
            .setAdapter(ArrayAdapter(this, android.R.layout.simple_list_item_1, names)) { _, which ->
                val f = files[which]
                prefs.edit().putString("shutterSoundPath", f.absolutePath).putString("shutterSound", f.name).apply()
                b.soundStatus.text = f.name
                toast("Звук выбран: ${f.name}")
            }
            .setNegativeButton("Отмена", null)
            .create()
        dlg.window?.setBackgroundDrawableResource(R.drawable.glass_sheet)
        dlg.show()
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
}
