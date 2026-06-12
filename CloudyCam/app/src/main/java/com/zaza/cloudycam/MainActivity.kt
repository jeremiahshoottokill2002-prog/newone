package com.zaza.cloudycam

import android.Manifest
import android.content.ContentValues
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Color
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.net.Uri
import android.opengl.GLSurfaceView
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.view.Gravity
import android.view.Surface
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale

class MainActivity : ComponentActivity() {

    private lateinit var glView: GLSurfaceView
    private lateinit var renderer: CameraRenderer
    private val effectButtons = mutableListOf<Button>()
    private lateinit var recordButton: Button
    private lateinit var zoomButton: Button
    private lateinit var flipButton: Button
    private lateinit var qualityButton: Button
    private lateinit var torchButton: Button
    private lateinit var musicButton: Button
    private lateinit var micButton: Button

    private var camera: Camera? = null
    private var mediaRecorder: MediaRecorder? = null
    private var outputFile: File? = null
    private var isRecording = false

    private var torchOn = false
    private var micEnabled = true
    private var musicUri: Uri? = null
    private var musicEnabled = false
    private var musicPlayer: MediaPlayer? = null

    private var surfaceReady = false
    private var permissionsGranted = false
    private var lensFacing = CameraSelector.LENS_FACING_BACK
    private var zoomIndex = 0

    private val zoomLevels = floatArrayOf(1.0f, 1.5f, 2.0f, 3.0f)
    private val effectNames = arrayOf(
        "Normal", "Fisheye", "Concave", "Wide",
        "Mirror", "B&W", "Crisp", "Smooth",
        "Glitch", "VHS", "Ghost", "Kaleido",
        "Invert", "Pulse", "Flat", "Cine", "Film"
    )
    private val effectDescriptions = arrayOf(
        "Clean camera, no effect",
        "Bulging wide lens look",
        "Pinched inward lens",
        "Mild wide-angle stretch",
        "Left half mirrored to right",
        "Black & white with contrast",
        "Sharpened — great for faces & talking",
        "Softens grain on weak cameras",
        "Jittery slices + RGB split — hip hop energy",
        "Retro tape: scanlines, grain, color bleed",
        "Drifting double image 👻",
        "4-way mirror kaleidoscope",
        "Negative colors",
        "Brightness beats with rhythm",
        "Low contrast — record now, color grade later",
        "Teal & orange cinema grade",
        "Film grain + warmth + vignette"
    )
    private val qualityNames = arrayOf("HD", "FHD", "SD")
    private var qualityIndex = 0

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        permissionsGranted = results[Manifest.permission.CAMERA] == true
        if (permissionsGranted) {
            maybeStartCamera()
        } else {
            Toast.makeText(this, "Camera permission is required", Toast.LENGTH_LONG).show()
        }
    }

    private val musicPicker = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            try {
                contentResolver.takePersistableUriPermission(
                    uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (_: Exception) {
            }
            musicUri = uri
            musicEnabled = true
            musicButton.setTextColor(Color.GREEN)
            Toast.makeText(this, "Track loaded 🎵 Plays when you hit REC", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        renderer = CameraRenderer(
            onSurfaceReady = {
                surfaceReady = true
                runOnUiThread { maybeStartCamera() }
            },
            requestRender = { if (::glView.isInitialized) glView.requestRender() }
        )

        glView = GLSurfaceView(this).apply {
            setEGLContextClientVersion(2)
            setEGLConfigChooser(8, 8, 8, 8, 0, 0)
            setRenderer(renderer)
            renderMode = GLSurfaceView.RENDERMODE_WHEN_DIRTY
        }

        // --- Effect rail: every effect visible, tap to select ---
        val effectRail = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        effectNames.forEachIndexed { index, name ->
            val b = makeButton(name) { selectEffect(index) }
            effectButtons.add(b)
            effectRail.addView(b, lpWrap())
        }
        val railScroll = android.widget.HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(effectRail)
        }

        zoomButton = makeButton("🔍 1.0x") {
            zoomIndex = (zoomIndex + 1) % zoomLevels.size
            camera?.cameraControl?.setZoomRatio(zoomLevels[zoomIndex])
            zoomButton.text = "🔍 " + zoomLevels[zoomIndex] + "x"
        }

        flipButton = makeButton("🔄") {
            if (isRecording) {
                Toast.makeText(this, "Stop recording first", Toast.LENGTH_SHORT).show()
                return@makeButton
            }
            lensFacing = if (lensFacing == CameraSelector.LENS_FACING_BACK)
                CameraSelector.LENS_FACING_FRONT else CameraSelector.LENS_FACING_BACK
            zoomIndex = 0
            zoomButton.text = "🔍 1.0x"
            torchOn = false
            torchButton.setTextColor(Color.WHITE)
            maybeStartCamera()
        }

        qualityButton = makeButton("🎞 HD") {
            if (isRecording) {
                Toast.makeText(this, "Stop recording first", Toast.LENGTH_SHORT).show()
                return@makeButton
            }
            qualityIndex = (qualityIndex + 1) % qualityNames.size
            qualityButton.text = "🎞 " + qualityNames[qualityIndex]
        }

        torchButton = makeButton("🔦") {
            torchOn = !torchOn
            camera?.cameraControl?.enableTorch(torchOn)
            torchButton.setTextColor(if (torchOn) Color.YELLOW else Color.WHITE)
        }

        musicButton = makeButton("🎵") {
            if (isRecording) {
                Toast.makeText(this, "Stop recording first", Toast.LENGTH_SHORT).show()
                return@makeButton
            }
            if (musicUri == null || !musicEnabled) {
                if (musicUri == null) {
                    musicPicker.launch(arrayOf("audio/*"))
                } else {
                    musicEnabled = true
                    musicButton.setTextColor(Color.GREEN)
                    Toast.makeText(this, "Music ON 🎵", Toast.LENGTH_SHORT).show()
                }
            } else {
                musicEnabled = false
                musicButton.setTextColor(Color.WHITE)
                Toast.makeText(this, "Music off (tap again for ON, hold to change track)", Toast.LENGTH_SHORT).show()
            }
        }
        musicButton.setOnLongClickListener {
            if (!isRecording) musicPicker.launch(arrayOf("audio/*"))
            true
        }

        micButton = makeButton("🎤 ON") {
            if (isRecording) {
                Toast.makeText(this, "Stop recording first", Toast.LENGTH_SHORT).show()
                return@makeButton
            }
            micEnabled = !micEnabled
            micButton.text = if (micEnabled) "🎤 ON" else "🎤 OFF"
            micButton.setTextColor(if (micEnabled) Color.WHITE else Color.GRAY)
            if (!micEnabled) {
                Toast.makeText(
                    this,
                    "Silent video — add your instrumental in the editor",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }

        recordButton = makeButton("● REC") { toggleRecording() }
        recordButton.setTextColor(Color.RED)

        val row2 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            addView(zoomButton, lpWrap())
            addView(qualityButton, lpWrap())
            addView(torchButton, lpWrap())
            addView(musicButton, lpWrap())
            addView(micButton, lpWrap())
            addView(flipButton, lpWrap())
        }

        val buttonColumn = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            addView(
                railScroll,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            )
            addView(row2, lpWrapV().apply { topMargin = dp(4) })
            addView(
                recordButton,
                LinearLayout.LayoutParams(
                    dp(200),
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(8) }
            )
        }
        selectEffect(0)

        val previewFrame = FrameLayout(this).apply {
            background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = dp(24).toFloat()
                setColor(Color.BLACK)
            }
            clipToOutline = true
            outlineProvider = object : android.view.ViewOutlineProvider() {
                override fun getOutline(view: android.view.View, outline: android.graphics.Outline) {
                    outline.setRoundRect(0, 0, view.width, view.height, dp(24).toFloat())
                }
            }
            addView(glView)
        }

        val root = FrameLayout(this).apply {
            setBackgroundColor(Color.rgb(12, 12, 16))
            addView(
                previewFrame,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                ).apply {
                    setMargins(dp(10), dp(28), dp(10), dp(10))
                }
            )
            addView(
                buttonColumn,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    Gravity.BOTTOM
                ).apply { bottomMargin = dp(24) }
            )
        }

        setContentView(root)

        permissionLauncher.launch(
            arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
        )
    }

    /** Selects an effect directly from the rail, highlights it, explains it. */
    private fun selectEffect(index: Int) {
        renderer.effectMode = index
        effectButtons.forEachIndexed { i, b ->
            b.setTextColor(if (i == index) Color.YELLOW else Color.WHITE)
        }
        Toast.makeText(
            this,
            effectNames[index] + " — " + effectDescriptions[index],
            Toast.LENGTH_SHORT
        ).show()
    }

    private fun maybeStartCamera() {
        if (!surfaceReady || !permissionsGranted) return

        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            val provider = providerFuture.get()

            val preview = Preview.Builder().build()
            preview.setSurfaceProvider { request ->
                val st = renderer.surfaceTexture ?: return@setSurfaceProvider
                st.setDefaultBufferSize(
                    request.resolution.width,
                    request.resolution.height
                )
                val surface = Surface(st)
                request.provideSurface(
                    surface,
                    ContextCompat.getMainExecutor(this)
                ) { surface.release() }
            }

            val selector = CameraSelector.Builder()
                .requireLensFacing(lensFacing)
                .build()

            try {
                provider.unbindAll()
                camera = provider.bindToLifecycle(this, selector, preview)
            } catch (e: Exception) {
                Toast.makeText(this, "Camera error: " + e.message, Toast.LENGTH_LONG).show()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    // ---------- Recording ----------

    private fun videoSize(): Pair<Int, Int> {
        val landscape =
            resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        val (w, h) = when (qualityNames[qualityIndex]) {
            "FHD" -> 1920 to 1080
            "SD" -> 854 to 480
            else -> 1280 to 720
        }
        return if (landscape) w to h else h to w
    }

    private fun videoBitrate(): Int = when (qualityNames[qualityIndex]) {
        "FHD" -> 16_000_000
        "SD" -> 4_000_000
        else -> 8_000_000
    }

    private fun toggleRecording() {
        if (isRecording) {
            stopRecording()
        } else {
            startRecording()
        }
    }

    private fun startRecording() {
        val (w, h) = videoSize()
        val recordMic = micEnabled && ContextCompat.checkSelfPermission(
            this, Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED

        val name = "CloudyCam_" + SimpleDateFormat(
            "yyyyMMdd_HHmmss", Locale.US
        ).format(System.currentTimeMillis()) + ".mp4"

        val dir = getExternalFilesDir(Environment.DIRECTORY_MOVIES) ?: filesDir
        val file = File(dir, name)

        try {
            @Suppress("DEPRECATION")
            val recorder = MediaRecorder()
            if (recordMic) recorder.setAudioSource(MediaRecorder.AudioSource.MIC)
            recorder.setVideoSource(MediaRecorder.VideoSource.SURFACE)
            recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            recorder.setOutputFile(file.absolutePath)
            recorder.setVideoEncodingBitRate(videoBitrate())
            recorder.setVideoFrameRate(30)
            recorder.setVideoSize(w, h)
            recorder.setVideoEncoder(MediaRecorder.VideoEncoder.H264)
            if (recordMic) {
                recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                recorder.setAudioSamplingRate(44100)
                recorder.setAudioEncodingBitRate(128000)
            }
            recorder.prepare()

            renderer.startRecording(recorder.surface, w, h)
            recorder.start()

            // Lock orientation while recording
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LOCKED

            // Start the instrumental so you can perform in sync
            startMusicIfEnabled()

            mediaRecorder = recorder
            outputFile = file
            isRecording = true
            recordButton.text = "■ STOP"
        } catch (e: Exception) {
            renderer.stopRecording()
            mediaRecorder?.release()
            mediaRecorder = null
            Toast.makeText(this, "Recording failed: " + e.message, Toast.LENGTH_LONG).show()
        }
    }

    private fun stopRecording() {
        val recorder = mediaRecorder ?: return
        isRecording = false
        recordButton.text = "● REC"
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR

        stopMusic()
        renderer.stopRecording()

        try {
            recorder.stop()
        } catch (e: Exception) {
            Toast.makeText(this, "Recording too short", Toast.LENGTH_SHORT).show()
            recorder.release()
            mediaRecorder = null
            outputFile?.delete()
            outputFile = null
            return
        }
        recorder.release()
        mediaRecorder = null

        val file = outputFile
        outputFile = null
        if (file != null && file.exists()) {
            saveToGallery(file)
        }
    }

    // ---------- Music playback (perform to your instrumental) ----------

    private fun startMusicIfEnabled() {
        if (!musicEnabled) return
        val uri = musicUri ?: return
        try {
            val player = MediaPlayer()
            player.setDataSource(this, uri)
            player.prepare()
            player.start()
            musicPlayer = player
        } catch (e: Exception) {
            Toast.makeText(this, "Couldn't play track: " + e.message, Toast.LENGTH_SHORT).show()
        }
    }

    private fun stopMusic() {
        musicPlayer?.let {
            try {
                it.stop()
            } catch (_: Exception) {
            }
            it.release()
        }
        musicPlayer = null
    }

    private fun saveToGallery(file: File) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                val values = ContentValues().apply {
                    put(MediaStore.Video.Media.DISPLAY_NAME, file.name)
                    put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                    put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/CloudyCam")
                    put(MediaStore.Video.Media.IS_PENDING, 1)
                }
                val uri = contentResolver.insert(
                    MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values
                )
                if (uri != null) {
                    contentResolver.openOutputStream(uri)?.use { out ->
                        file.inputStream().use { input -> input.copyTo(out) }
                    }
                    values.clear()
                    values.put(MediaStore.Video.Media.IS_PENDING, 0)
                    contentResolver.update(uri, values, null, null)
                    file.delete()
                    Toast.makeText(this, "Saved to gallery: Movies/CloudyCam 🎬", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, "Saved to: " + file.absolutePath, Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                Toast.makeText(this, "Saved to: " + file.absolutePath, Toast.LENGTH_LONG).show()
            }
        } else {
            Toast.makeText(this, "Saved to: " + file.absolutePath, Toast.LENGTH_LONG).show()
        }
    }

    override fun onPause() {
        super.onPause()
        if (isRecording) stopRecording()
    }

    override fun onDestroy() {
        super.onDestroy()
        stopMusic()
    }

    private fun makeButton(label: String, onClick: () -> Unit): Button =
        Button(this).apply {
            text = label
            isAllCaps = false
            background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = dp(20).toFloat()
                setColor(Color.argb(190, 24, 24, 32))
            }
            setTextColor(Color.WHITE)
            setPadding(dp(14), dp(6), dp(14), dp(6))
            minHeight = 0
            minimumHeight = 0
            setOnClickListener { onClick() }
        }

    private fun lpWrap() = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.WRAP_CONTENT,
        LinearLayout.LayoutParams.WRAP_CONTENT
    ).apply { marginStart = dp(4); marginEnd = dp(4) }

    private fun lpWrapV() = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.WRAP_CONTENT,
        LinearLayout.LayoutParams.WRAP_CONTENT
    )

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()
}
