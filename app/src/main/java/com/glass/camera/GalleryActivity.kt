package com.glass.camera

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.glass.camera.databinding.ActivityGalleryBinding

class GalleryActivity : AppCompatActivity() {
    private lateinit var b: ActivityGalleryBinding
    private var uri: Uri? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityGalleryBinding.inflate(layoutInflater)
        setContentView(b.root)
        uri = intent.getStringExtra("uri")?.let { Uri.parse(it) }
        uri?.let { b.photo.setImageURI(it) }
        b.btnClose.setOnClickListener { finish() }
        b.btnEdit.setOnClickListener {
            b.aiSheet.visibility = View.VISIBLE
            b.aiSheet.alpha = 0f
            b.aiSheet.translationY = 200f
            b.aiSheet.animate().alpha(1f).translationY(0f).setDuration(220).start()
        }
        b.aiChatGPT.setOnClickListener { openAi("com.openai.chatgpt", "https://chatgpt.com/") }
        b.aiGemini.setOnClickListener { openAi("com.google.android.apps.bard", "https://gemini.google.com/") }
        b.aiDeepSeek.setOnClickListener { openAi("com.deepseek.chat", "https://chat.deepseek.com/") }
        b.aiQwen.setOnClickListener { openAi("com.alibaba.android.rimet", "https://chat.qwen.ai/") }
    }

    private fun openAi(pkg: String, web: String) {
        val u = uri
        try {
            val pm = packageManager.getLaunchIntentForPackage(pkg)
            if (pm != null) {
                if (u != null) {
                    val share = Intent(Intent.ACTION_SEND).setType("image/*").putExtra(Intent.EXTRA_STREAM, u).setPackage(pkg).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    startActivity(share)
                } else startActivity(pm)
            } else {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(web)))
            }
        } catch (_: Exception) {
            try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(web))) } catch (_: Exception) { Toast.makeText(this, "Не удалось открыть", Toast.LENGTH_SHORT).show() }
        }
    }
}
