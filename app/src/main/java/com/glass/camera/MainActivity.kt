package com.glass.camera

import android.Manifest
import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.media.MediaPlayer
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.widget.SeekBar
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.*
import androidx.camera.video.VideoCapture
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.glass.camera.databinding.ActivityMainBinding
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {
    private lateinit var b: ActivityMainBinding
    private var imageCapture: ImageCapture? = null
    private var videoCapture: VideoCapture<Recorder>? = null
    private var recording: Recording? = null
    private var camera: Camera? = null
    private var lensFacing = CameraSelector.LENS_FACING_BACK
    private var cameraProvider: ProcessCameraProvider? = null
    private lateinit var cameraExecutor: ExecutorService
    private var mode = Mode.PHOTO
    private var aspect = Aspect.RATIO_9_16
    private var quality = QPreset.FHD
    private var liveOn = false
    private var cameraLock = false
    private var lockedRotation: Int? = null
    private var shutterPlayer: MediaPlayer? = null
    private var lastPhotoUri: android.net.Uri? = null

    enum class Mode { PHOTO, VIDEO }
    enum class Aspect { RATIO_9_16, RATIO_4_3, RATIO_1_1 }
    enum class QPreset { UHD4K, Q2K, FHD, HD, P480, P360 }

    private val permLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (allGranted()) startCamera() else toast("Нужны разрешения камеры и микрофона")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)
        hideSystemUi()
        cameraExecutor = Executors.newSingleThreadExecutor()
        setupUi()
        if (allGranted()) startCamera() else permLauncher.launch(requiredPermissions())
    }

    private fun setupUi() {
        b.btnArrow.setOnClickListener { toggleDrawer() }
        b.btnGallery.setOnClickListener { openGallery() }
        b.menuSettings.setOnClickListener { startActivity(Intent(this, SettingsActivity::class.java)); hideOverlays() }
        b.menuLive.setOnClickListener { liveOn = !liveOn; toast(if (liveOn) "Live включён" else "Live выключен"); styleTop() }
        b.btnLive.setOnClickListener { b.menuLive.performClick() }
        b.menuFilter.setOnClickListener { toast("Фильтры: скоро") }
        b.btnFilter.setOnClickListener { b.menuFilter.performClick() }
        b.btnHd.setOnClickListener { cycleQuality() }
        b.btnLock.setOnClickListener { toggleCameraLock() }
        b.btnFlip.setOnClickListener { lensFacing = if (lensFacing == CameraSelector.LENS_FACING_BACK) CameraSelector.LENS_FACING_FRONT else CameraSelector.LENS_FACING_BACK; bindCamera() }
        b.shutter.setOnClickListener { if (mode == Mode.PHOTO) takePhoto() else toggleVideo() }
        b.shutter.setOnLongClickListener { if (mode == Mode.PHOTO) { mode = Mode.VIDEO; updateModeUi(); toast("Видео") }; true }
        b.modeLabel.setOnClickListener { mode = if (mode == Mode.PHOTO) Mode.VIDEO else Mode.PHOTO; updateModeUi() }
        b.ratio916.setOnClickListener { setAspect(Aspect.RATIO_9_16) }
        b.ratio43.setOnClickListener { setAspect(Aspect.RATIO_4_3) }
        b.ratio11.setOnClickListener { setAspect(Aspect.RATIO_1_1) }
        b.menuQuality.setOnClickListener { b.drawer.visibility = View.GONE; b.qualitySheet.visibility = View.VISIBLE }
        b.q4k.setOnClickListener { setQuality(QPreset.UHD4K) }
        b.q2k.setOnClickListener { setQuality(QPreset.Q2K) }
        b.qfhd.setOnClickListener { setQuality(QPreset.FHD) }
        b.qhd.setOnClickListener { setQuality(QPreset.HD) }
        b.q480.setOnClickListener { setQuality(QPreset.P480) }
        b.q360.setOnClickListener { setQuality(QPreset.P360) }
        b.zoom1.setOnClickListener { setZoom(1f) }
        b.zoom2.setOnClickListener { setZoom(2f) }
        b.zoom5.setOnClickListener { setZoom(5f) }
        b.zoomSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) { if (fromUser) setZoom(1f + progress / 10f) }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        val scaleDetector = ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                val z = (camera?.cameraInfo?.zoomState?.value?.zoomRatio ?: 1f) * detector.scaleFactor
                setZoom(z)
                b.zoomSeek.visibility = View.VISIBLE
                return true
            }
        })
        val gesture = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapUp(e: MotionEvent): Boolean { focusAt(e.x, e.y); return true }
            override fun onLongPress(e: MotionEvent) { b.zoomSeek.visibility = if (b.zoomSeek.visibility == View.VISIBLE) View.GONE else View.VISIBLE }
        })
        b.previewView.setOnTouchListener { _, ev ->
            scaleDetector.onTouchEvent(ev)
            gesture.onTouchEvent(ev)
            true
        }
        applyStyle()
        updateModeUi()
    }

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        b.startupBlur.animate().alpha(0f).setStartDelay(250).setDuration(700).withEndAction { b.startupBlur.visibility = View.GONE }.start()
        future.addListener({
            cameraProvider = future.get()
            bindCamera()
        }, ContextCompat.getMainExecutor(this))
    }

    private fun bindCamera() {
        val provider = cameraProvider ?: return
        provider.unbindAll()
        val ratio = when (aspect) {
            Aspect.RATIO_9_16 -> AspectRatio.RATIO_16_9
            Aspect.RATIO_4_3 -> AspectRatio.RATIO_4_3
            Aspect.RATIO_1_1 -> AspectRatio.RATIO_4_3
        }
        val rot = b.previewView.display?.rotation ?: @Suppress("DEPRECATION") windowManager.defaultDisplay.rotation
        val preview = Preview.Builder().setTargetAspectRatio(ratio)
            .setTargetRotation(rot).build().also {
            it.setSurfaceProvider(b.previewView.surfaceProvider)
        }
        imageCapture = ImageCapture.Builder()
            .setCaptureMode(if (quality == QPreset.UHD4K || quality == QPreset.Q2K) ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY else ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
            .setJpegQuality(95)
            .setTargetAspectRatio(ratio)
            .setTargetRotation(rot)
            .build()
        if (cameraLock) { lockedRotation?.let { imageCapture?.targetRotation = it } }
        val recorder = Recorder.Builder().setQualitySelector(qualitySelector()).build()
        videoCapture = VideoCapture.withOutput(recorder)
        if (cameraLock) { lockedRotation?.let { videoCapture?.targetRotation = it } }
        val selector = CameraSelector.Builder().requireLensFacing(lensFacing).build()
        try {
            camera = provider.bindToLifecycle(this, selector, preview, imageCapture, videoCapture)
            camera?.cameraInfo?.zoomState?.observe(this) { st ->
                val z = st.zoomRatio
                b.zoomSeek.progress = ((z - 1f) * 10f).toInt().coerceIn(0, 490)
            }
        } catch (e: Exception) {
            toast("Ошибка камеры: ${e.message}")
        }
    }

    private fun qualitySelector(): QualitySelector {
        return when (quality) {
            QPreset.UHD4K -> QualitySelector.from(Quality.UHD)
            QPreset.Q2K, QPreset.FHD -> QualitySelector.from(Quality.FHD)
            QPreset.HD -> QualitySelector.from(Quality.HD)
            QPreset.P480, QPreset.P360 -> QualitySelector.from(Quality.SD)
        }
    }

    private fun takePhoto() {
        val ic = imageCapture ?: return
        ic.targetRotation = b.previewView.display?.rotation ?: @Suppress("DEPRECATION") windowManager.defaultDisplay.rotation
        playShutter()
        animateShutter()
        flashCapture()
        val name = "GLASS_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(System.currentTimeMillis())
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/GlassCamera")
        }
        val out = ImageCapture.OutputFileOptions.Builder(contentResolver, MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values).build()
        ic.takePicture(out, ContextCompat.getMainExecutor(this), object : ImageCapture.OnImageSavedCallback {
            override fun onError(exc: ImageCaptureException) { toast("Ошибка фото: ${exc.message}") }
            override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                val uri = output.savedUri
                if (uri != null) { lastPhotoUri = uri; showGalleryThumb(uri); flyToGallery() }
                toast("Фото сохранено")
            }
        })
    }

    private fun flashCapture() {
        b.flashView.visibility = View.VISIBLE
        b.flashView.alpha = 0f
        b.flashView.animate().alpha(0.85f).setDuration(60).withEndAction {
            b.flashView.animate().alpha(0f).setDuration(180).withEndAction { b.flashView.visibility = View.GONE }.start()
        }.start()
    }

    private fun showGalleryThumb(uri: android.net.Uri) {
        try { b.btnGallery.setPadding(2,2,2,2); b.btnGallery.setImageURI(uri) } catch (_: Exception) {}
    }

    private fun flyToGallery() {
        b.btnGallery.animate().scaleX(0.75f).scaleY(0.75f).setDuration(120).withEndAction {
            b.btnGallery.animate().scaleX(1f).scaleY(1f).setDuration(180).start()
        }.start()
    }
    private fun toggleVideo() {
        val vc = videoCapture ?: return
        val cur = recording
        if (cur != null) { cur.stop(); recording = null; return }
        val name = "GLASS_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(System.currentTimeMillis())
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/GlassCamera")
        }
        val out = MediaStoreOutputOptions.Builder(contentResolver, MediaStore.Video.Media.EXTERNAL_CONTENT_URI).setContentValues(values).build()
        var pending = vc.output.prepareRecording(this, out)
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) pending = pending.withAudioEnabled()
        b.shutterInner.setBackgroundResource(R.drawable.shutter_rec)
        recording = pending.start(ContextCompat.getMainExecutor(this)) { ev ->
            if (ev is VideoRecordEvent.Finalize) {
                b.shutterInner.setBackgroundResource(R.drawable.shutter_inner)
                recording = null
                if (ev.hasError()) toast("Ошибка видео: ${ev.error}") else toast("Видео сохранено")
            }
        }
    }

    private fun focusAt(x: Float, y: Float) {
        val cam = camera ?: return
        val point = b.previewView.meteringPointFactory.createPoint(x, y)
        val action = FocusMeteringAction.Builder(point, FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE)
            .setAutoCancelDuration(3, java.util.concurrent.TimeUnit.SECONDS).build()
        cam.cameraControl.startFocusAndMetering(action)
        showFocusRing(x, y)
    }

    private fun showFocusRing(x: Float, y: Float) {
        val r = b.focusRing
        r.visibility = View.VISIBLE
        r.x = x - r.width / 2f
        r.y = y - r.height / 2f
        r.scaleX = 1.4f; r.scaleY = 1.4f; r.alpha = 0f
        val s1 = ObjectAnimator.ofFloat(r, View.SCALE_X, 1.4f, 1f)
        val s2 = ObjectAnimator.ofFloat(r, View.SCALE_Y, 1.4f, 1f)
        val a1 = ObjectAnimator.ofFloat(r, View.ALPHA, 0f, 1f)
        AnimatorSet().apply { playTogether(s1, s2, a1); duration = 180; start() }
        r.postDelayed({ r.animate().alpha(0f).setDuration(250).withEndAction { r.visibility = View.GONE }.start() }, 700)
    }

    private fun setZoom(z: Float) {
        val cam = camera ?: return
        val st = cam.cameraInfo.zoomState.value ?: return
        cam.cameraControl.setZoomRatio(z.coerceIn(st.minZoomRatio, minOf(st.maxZoomRatio, 50f)))
    }

    private fun toggleDrawer() {
        val show = b.drawer.visibility != View.VISIBLE
        hideOverlays()
        if (show) {
            b.drawer.visibility = View.VISIBLE
            b.drawer.translationY = 300f
            b.drawer.alpha = 0f
            b.drawer.animate().translationY(0f).alpha(1f).setDuration(220).start()
        }
    }

    private fun hideOverlays() {
        b.drawer.visibility = View.GONE
        b.qualitySheet.visibility = View.GONE
    }

    private fun setAspect(a: Aspect) { aspect = a; toast("Соотношение: ${a.name}"); hideOverlays(); bindCamera() }
    private fun setQuality(q: QPreset) { quality = q; b.btnHd.text = when(q){QPreset.UHD4K->"4K";QPreset.Q2K->"2K";QPreset.FHD->"FHD";QPreset.HD->"HD";QPreset.P480->"480";QPreset.P360->"360"}; hideOverlays(); bindCamera(); toast("Качество: ${b.btnHd.text}") }
    private fun cycleQuality() { val vals = QPreset.values(); setQuality(vals[(quality.ordinal + 1) % vals.size]) }

    private fun toggleCameraLock() {
        cameraLock = !cameraLock
        lockedRotation = if (cameraLock) windowManager.defaultDisplay.rotation else null
        val rot = lockedRotation
        if (rot != null) { imageCapture?.targetRotation = rot; videoCapture?.targetRotation = rot }
        b.lockBadge.visibility = if (cameraLock) View.VISIBLE else View.GONE
        b.lockBadge.alpha = if (cameraLock) 1f else 0f
        toast(if (cameraLock) "Camera Lock: ориентация зафиксирована" else "Camera Lock выключен")
        styleTop()
    }

    private fun openGallery() {
        val uri = lastPhotoUri
        if (uri == null) { toast("Сначала сделай фото"); return }
        try {
            val intent = Intent(Intent.ACTION_VIEW).setDataAndType(uri, "image/*").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            startActivity(Intent.createChooser(intent, "Открыть в галерее"))
        } catch (_: Exception) {
            try { startActivity(Intent(this, GalleryActivity::class.java).putExtra("uri", uri.toString())) } catch (_: Exception) { toast("Не удалось открыть фото") }
        }
    }

    private fun updateModeUi() {
        b.modeLabel.text = if (mode == Mode.PHOTO) "PHOTO" else "VIDEO"
        b.shutterInner.setBackgroundResource(if (mode == Mode.PHOTO) R.drawable.shutter_inner else R.drawable.shutter_rec_idle)
    }

    private fun playShutter() {
        try {
            shutterPlayer?.release()
            val prefs = getSharedPreferences("glass", MODE_PRIVATE)
            val builtIn = prefs.getString("shutterBuiltIn", "default") ?: "default"
            val custom = prefs.getString("shutterSoundPath", null)
            val mp = if (builtIn == "custom" && !custom.isNullOrBlank() && java.io.File(custom).exists()) {
                MediaPlayer().apply { setDataSource(custom); setAudioAttributes(android.media.AudioAttributes.Builder().setUsage(android.media.AudioAttributes.USAGE_ASSISTANCE_SONIFICATION).setContentType(android.media.AudioAttributes.CONTENT_TYPE_SONIFICATION).build()); prepare() }
            } else {
                val res = when (builtIn) { "soft" -> R.raw.shutter_soft; "loud" -> R.raw.shutter_loud; else -> R.raw.shutter }
                MediaPlayer.create(this, res)
            }
            if (mp != null) { mp.setVolume(1f,1f); mp.setOnCompletionListener { it.release(); if (shutterPlayer == it) shutterPlayer = null }; shutterPlayer = mp; mp.start() }
        } catch (_: Exception) {}
    }

    private fun animateShutter() {
        b.shutterInner.animate().scaleX(0.85f).scaleY(0.85f).setDuration(80).withEndAction {
            b.shutterInner.animate().scaleX(1f).scaleY(1f).setDuration(120).start()
        }.start()
    }

    private fun applyBlur(v: View, radius: Float = 20f) { if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) try { v.setRenderEffect(android.graphics.RenderEffect.createBlurEffect(radius, radius, android.graphics.Shader.TileMode.CLAMP)) } catch (_: Exception) {} }
    private fun applyStyle() {
        val prefs = getSharedPreferences("glass", MODE_PRIVATE)
        val light = prefs.getString("style", "dark") == "light"
        val text = if (light) "#111111" else "#FFFFFF"
        b.modeLabel.setTextColor(Color.parseColor(text))
        b.btnArrow.setTextColor(Color.parseColor(text))
        b.lockBadge.setTextColor(Color.parseColor(text))
        b.drawer.setBackgroundColor(Color.parseColor(if (light) "#EEFFFFFF" else "#99111111"))
        b.qualitySheet.setBackgroundColor(Color.parseColor(if (light) "#EEFFFFFF" else "#99111111"))
        b.drawerTitle.setTextColor(Color.parseColor(text))
        b.root.setBackgroundColor(Color.BLACK)
        try { b.shutterInner.background.setTint(Color.parseColor(prefs.getString("accent", "#7C8CFF") ?: "#7C8CFF")) } catch (_: Exception) {}
        styleTop()
    }

    private fun styleTop() {
        val prefs = getSharedPreferences("glass", MODE_PRIVATE)
        val accent = try { Color.parseColor(prefs.getString("accent", "#7C8CFF")) } catch (_: Exception) { Color.parseColor("#7C8CFF") }
        b.btnLive.setTextColor(if (liveOn) accent else Color.WHITE)
        b.btnLock.setTextColor(if (cameraLock) accent else Color.WHITE)
    }

    private fun hideSystemUi() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val ctrl = WindowInsetsControllerCompat(window, b.root)
        ctrl.hide(WindowInsetsCompat.Type.systemBars())
        ctrl.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }

    private fun allGranted() = requiredPermissions().all { ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED }
    private fun requiredPermissions() = mutableListOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO).apply {
        if (Build.VERSION.SDK_INT <= 28) add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
    }.toTypedArray()
    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    override fun onResume() { super.onResume(); applyStyle(); hideSystemUi() }
    override fun onDestroy() { super.onDestroy(); cameraExecutor.shutdown(); shutterPlayer?.release() }
}
