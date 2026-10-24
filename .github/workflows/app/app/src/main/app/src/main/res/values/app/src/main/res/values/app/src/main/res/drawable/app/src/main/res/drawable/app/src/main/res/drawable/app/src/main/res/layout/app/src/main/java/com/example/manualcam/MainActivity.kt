package com.example.manualcam

import android.Manifest
import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.graphics.SurfaceTexture
import android.hardware.camera2.*
import android.media.ImageReader
import android.os.*
import android.provider.MediaStore
import android.util.Log
import android.util.Size
import android.view.Surface
import android.view.TextureView
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.bottomsheet.BottomSheetBehavior
import kotlin.math.*

class MainActivity : AppCompatActivity() {

    private lateinit var textureView: TextureView
    private lateinit var cameraManager: CameraManager

    private var cameraId = "0"
    private var cameraDevice: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var previewSurface: Surface? = null
    private var reader: ImageReader? = null
    private var previewSize: Size? = null
    private var jpegSize: Size? = null
    private var sensorOrientation = 90

    private var thread: HandlerThread? = null
    private var handler: Handler? = null

    private lateinit var seekIso: SeekBar
    private lateinit var seekShutter: SeekBar
    private lateinit var tvIso: TextView
    private lateinit var tvShutter: TextView
    private lateinit var spinnerPresets: Spinner
    
    private var isoMin = 100
    private var isoMax = 3200
    private var expMinNs = 1_000_000L
    private var expMaxNs = 100_000_000L

    private var manualSupported = false
    private var iso = 400
    private var shutterNs = 16_000_000L
    private var jpegQuality = 98

    private var suppress = false
    private val prefs by lazy { getSharedPreferences("mcam", Context.MODE_PRIVATE) }
    private val presets = mutableListOf<String>()

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) openCamera()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        cameraManager = getSystemService(Context.CAMERA_SERVICE) as CameraManager

        textureView = findViewById(R.id.preview)
        seekIso = findViewById(R.id.seekIso)
        seekShutter = findViewById(R.id.seekShutter)
        tvIso = findViewById(R.id.tvIso)
        tvShutter = findViewById(R.id.tvShutter)
        spinnerPresets = findViewById(R.id.spinnerPresets)

        val bottomSheet = findViewById<LinearLayout>(R.id.bottomSheet)
        val sheetBehavior = BottomSheetBehavior.from(bottomSheet)
        sheetBehavior.state = BottomSheetBehavior.STATE_COLLAPSED
        sheetBehavior.peekHeight = 120

        findViewById<ImageButton>(R.id.btnSettings).setOnClickListener {
            if (sheetBehavior.state == BottomSheetBehavior.STATE_COLLAPSED) {
                sheetBehavior.state = BottomSheetBehavior.STATE_EXPANDED
            } else {
                sheetBehavior.state = BottomSheetBehavior.STATE_COLLAPSED
            }
        }

        findViewById<Button>(R.id.btnCapture).setOnClickListener { takePhoto() }
        findViewById<Button>(R.id.btnSavePreset).setOnClickListener { savePresetDialog() }

        loadPresets()
        loadCurrent()
        initListeners()
        
        textureView.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) {
                startThread()
                if (ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                    openCamera()
                } else {
                    permissionLauncher.launch(Manifest.permission.CAMERA)
                }
            }
            override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) {}
            override fun onSurfaceTextureDestroyed(st: SurfaceTexture) = true
            override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
        }
    }

    private fun startThread() {
        if (thread != null) return
        thread = HandlerThread("CameraBackground").also { it.start() }
        handler = Handler(thread!!.looper)
    }

    private fun stopThread() {
        thread?.quitSafely()
        try { thread?.join() } catch (_: InterruptedException) {}
        thread = null
        handler = null
    }

    @SuppressLint("MissingPermission")
    private fun openCamera() {
        if (cameraDevice != null || thread == null || !textureView.isAvailable) return
        try {
            cameraId = cameraManager.cameraIdList.firstOrNull { id ->
                cameraManager.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING) == CameraMetadata.LENS_FACING_BACK
            } ?: cameraManager.cameraIdList.first()

            val ch = cameraManager.getCameraCharacteristics(cameraId)
            sensorOrientation = ch.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90
            val map = ch.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: return

            jpegSize = map.getOutputSizes(ImageFormat.JPEG)?.maxByOrNull { it.width * it.height }
            previewSize = map.getOutputSizes(SurfaceTexture::class.java)
                ?.filter { it.width <= 1920 && it.height <= 1080 }
                ?.maxByOrNull { it.width * it.height }

            val isoRange = ch.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)
            val expRange = ch.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)
            
            if (isoRange != null && expRange != null) {
                manualSupported = true
                isoMin = isoRange.lower
                isoMax = isoRange.upper
                expMinNs = max(expRange.lower, 100_000L)
                expMaxNs = expRange.upper
            }

            runOnUiThread {
                seekIso.isEnabled = manualSupported
                seekShutter.isEnabled = manualSupported
                if (!manualSupported) {
                    Toast.makeText(this, "Ручной режим не поддерживается", Toast.LENGTH_LONG).show()
                }
            }

            cameraManager.openCamera(cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    cameraDevice = camera
                    createPreviewSession()
                }
                override fun onDisconnected(camera: CameraDevice) { camera.close(); cameraDevice = null }
                override fun onError(camera: CameraDevice, error: Int) { camera.close(); cameraDevice = null }
            }, handler)
        } catch (e: Exception) { Log.e("ManualCam", "Error opening camera", e) }
    }

    private fun createPreviewSession() {
        val device = cameraDevice ?: return
        val st = textureView.surfaceTexture ?: return
        val ps = previewSize ?: return
        val js = jpegSize ?: return

        st.setDefaultBufferSize(ps.width, ps.height)
        val surface = Surface(st)
        previewSurface = surface

        reader?.close()
        reader = ImageReader.newInstance(js.width, js.height, ImageFormat.JPEG, 2)
        reader!!.setOnImageAvailableListener({ r ->
            val image = r.acquireNextImage() ?: return@setOnImageAvailableListener
            try {
                val buf = image.planes[0].buffer
                val bytes = ByteArray(buf.remaining())
                buf.get(bytes)
                saveJpeg(bytes)
            } finally { image.close() }
        }, handler)

        try {
            val builder = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
            builder.addTarget(surface)
            applySettings(builder)

            device.createCaptureSession(listOf(surface, reader!!.surface), object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(s: CameraCaptureSession) {
                    session = s
                    try { s.setRepeatingRequest(builder.build(), null, handler) } catch (_: Exception) {}
                }
                override fun onConfigureFailed(s: CameraCaptureSession) {}
            }, handler)
        } catch (e: Exception) { Log.e("ManualCam", "Session error", e) }
    }

    private fun applySettings(b: CaptureRequest.Builder) {
        b.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
        if (manualSupported) {
            b.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_OFF)
            b.set(CaptureRequest.SENSOR_SENSITIVITY, iso)
            b.set(CaptureRequest.SENSOR_EXPOSURE_TIME, shutterNs)
        } else {
            b.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_ON)
        }
        b.set(CaptureRequest.NOISE_REDUCTION_MODE, CameraMetadata.NOISE_REDUCTION_MODE_HIGH_QUALITY)
        b.set(CaptureRequest.EDGE_MODE, CameraMetadata.EDGE_MODE_HIGH_QUALITY)
    }

    private fun applyPreview() {
        val s = session ?: return
        val device = cameraDevice ?: return
        val surface = previewSurface ?: return
        try {
            val b = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
            b.addTarget(surface)
            applySettings(b)
            s.setRepeatingRequest(b.build(), null, handler)
        } catch (_: Exception) {}
    }

    private fun takePhoto() {
        val s = session ?: return
        val device = cameraDevice ?: return
        val r = reader ?: return
        try {
            val b = device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
            b.addTarget(r.surface)
            applySettings(b)
            b.set(CaptureRequest.JPEG_QUALITY, jpegQuality.toByte())
            b.set(CaptureRequest.JPEG_ORIENTATION, sensorOrientation)
            s.capture(b.build(), null, handler)
        } catch (_: Exception) {}
    }

    private fun saveJpeg(bytes: ByteArray) {
        try {
            val name = "MCAM_${System.currentTimeMillis()}.jpg"
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, name)
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/ManualCam")
                }
            }
            val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return
            contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
            runOnUiThread { Toast.makeText(this, "Сохранено", Toast.LENGTH_SHORT).show() }
        } catch (e: Exception) { Log.e("ManualCam", "Save error", e) }
    }

    private fun initListeners() {
        suppress = true
        seekIso.progress = isoToProgress(iso)
        seekShutter.progress = shutterToProgress(shutterNs)
        suppress = false

        seekIso.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                if (suppress) return
                iso = (isoMin * (isoMax.toDouble() / isoMin).pow(p / 1000.0)).roundToInt().coerceIn(isoMin, isoMax)
                tvIso.text = "ISO: $iso"
                applyPreview()
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })

        seekShutter.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                if (suppress) return
                shutterNs = (expMinNs * (expMaxNs.toDouble() / expMinNs).pow(p / 1000.0)).toLong().coerceIn(expMinNs, expMaxNs)
                val ms = shutterNs / 1_000_000.0
                tvShutter.text = "Выдержка: 1/${(1000.0 / ms).roundToInt()}"
                applyPreview()
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })
    }

    private fun isoToProgress(v: Int): Int = (1000.0 * ln(v.toDouble() / isoMin) / ln(isoMax.toDouble() / isoMin)).roundToInt().coerceIn(0, 1000)
    private fun shutterToProgress(v: Long): Int = (1000.0 * ln(v.toDouble() / expMinNs) / ln(expMaxNs.toDouble() / expMinNs)).roundToInt().coerceIn(0, 1000)

    private fun savePresetDialog() {
        val input = EditText(this).apply { hint = "Имя пресета" }
        AlertDialog.Builder(this)
            .setTitle("Сохранить")
            .setView(input)
            .setPositiveButton("OK") { _, _ ->
                val name = input.text.toString().ifBlank { "Preset_${System.currentTimeMillis() % 1000}" }
                prefs.edit().putString("preset_$name", "$iso,$shutterNs").apply()
                loadPresets()
                Toast.makeText(this, "Сохранено", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Отмена", null).show()
    }

    private fun loadPresets() {
        presets.clear()
        presets.add("— Выбрать пресет —")
        val all = prefs.all
        for ((k, _) in all) {
            if (k.startsWith("preset_")) presets.add(k.replace("preset_", ""))
        }
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, presets)
        spinnerPresets.adapter = adapter
        spinnerPresets.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: android.view.View?, pos: Int, id: Long) {
                if (pos == 0) return
                val name = presets[pos]
                val data = prefs.getString("preset_$name", null) ?: return
                val parts = data.split(",")
                if (parts.size == 2) {
                    suppress = true
                    iso = parts[0].toIntOrNull() ?: iso
                    shutterNs = parts[1].toLongOrNull() ?: shutterNs
                    seekIso.progress = isoToProgress(iso)
                    seekShutter.progress = shutterToProgress(shutterNs)
                    tvIso.text = "ISO: $iso"
                    val ms = shutterNs / 1_000_000.0
                    tvShutter.text = "Выдержка: 1/${(1000.0 / ms).roundToInt()}"
                    suppress = false
                    applyPreview()
                }
            }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
    }

    private fun loadCurrent() {
        iso = prefs.getInt("iso", 400)
        shutterNs = prefs.getLong("shutter", 16_000_000L)
    }

    override fun onPause() {
        prefs.edit().putInt("iso", iso).putLong("shutter", shutterNs).apply()
        closeCamera()
        stopThread()
        super.onPause()
    }

    private fun closeCamera() {
        try { session?.close() } catch (_: Exception) {}
        session = null
        try { cameraDevice?.close() } catch (_: Exception) {}
        cameraDevice = null
        reader?.close()
        reader = null
        previewSurface = null
    }
}
