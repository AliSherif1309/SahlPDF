package com.sahl.pdf

import android.app.Activity
import android.graphics.Color
import android.graphics.ImageFormat
import android.graphics.SurfaceTexture
import android.graphics.Typeface
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.TotalCaptureResult
import android.media.ImageReader
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.util.Size
import android.view.Gravity
import android.view.Surface
import android.view.TextureView
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import java.io.File
import java.io.FileOutputStream

/**
 * Built-in Camera2 document capture screen so Scan to PDF works on every device
 * without depending on an external camera app's IMAGE_CAPTURE intent.
 */
class CameraScanActivity : Activity() {

    private lateinit var preview: TextureView
    private lateinit var outputPath: String
    private var camera: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var reader: ImageReader? = null
    private var bgThread: HandlerThread? = null
    private var bgHandler: Handler? = null
    private var sensorOrientation = 90
    private var capturing = false

    private fun dp(v: Number) = Ui.dp(this, v)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        SahlCore.catchCrashes(this)
        outputPath = intent.getStringExtra(EXTRA_OUTPUT_PATH) ?: run { finish(); return }
        val pal = Palette(this)

        val root = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            fitsSystemWindows = true
        }
        preview = TextureView(this)
        root.addView(preview, FrameLayout.LayoutParams(-1, -1))

        val topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.parseColor("#88000000"))
            setPadding(dp(8), dp(8), dp(12), dp(8))
        }
        topBar.addView(ImageView(this).apply {
            setImageResource(R.drawable.ic_back)
            setColorFilter(Color.WHITE)
            setPadding(dp(12), dp(12), dp(12), dp(12))
            setOnClickListener { finish() }
        }, LinearLayout.LayoutParams(dp(44), dp(44)))
        topBar.addView(TextView(this).apply {
            text = getString(R.string.scan_to_pdf)
            textSize = 18f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            setPadding(dp(6), 0, 0, 0)
        })
        root.addView(topBar, FrameLayout.LayoutParams(-1, -2, Gravity.TOP))

        val captureBtn = TextView(this).apply {
            text = getString(R.string.capture_photo)
            setTextColor(Color.WHITE)
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setPadding(dp(28), dp(14), dp(28), dp(14))
            Ui.focusable(this, pal.accent, dp(28).toFloat(), pal.gold)
            setOnClickListener { capture() }
        }
        root.addView(captureBtn, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
            bottomMargin = dp(28)
        })

        setContentView(root)
    }

    override fun onResume() {
        super.onResume()
        bgThread = HandlerThread("cam_scan").apply { start() }
        bgHandler = Handler(bgThread!!.looper)
        if (preview.isAvailable) {
            openCamera()
        } else {
            preview.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) { openCamera() }
                override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) {}
                override fun onSurfaceTextureDestroyed(st: SurfaceTexture) = true
                override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
            }
        }
    }

    override fun onPause() {
        closeCamera()
        bgThread?.quitSafely()
        try { bgThread?.join() } catch (_: InterruptedException) {}
        bgThread = null
        bgHandler = null
        super.onPause()
    }

    private fun openCamera() {
        try {
            val mgr = getSystemService(CAMERA_SERVICE) as CameraManager
            val camId = mgr.cameraIdList.firstOrNull { id ->
                mgr.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
            } ?: mgr.cameraIdList.firstOrNull() ?: run {
                Toast.makeText(this, R.string.cannot_open, Toast.LENGTH_SHORT).show()
                finish()
                return
            }
            val chars = mgr.getCameraCharacteristics(camId)
            sensorOrientation = chars.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90
            val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            val jpegSizes = map?.getOutputSizes(ImageFormat.JPEG) ?: arrayOf(Size(1920, 1080))
            val bestJpeg = jpegSizes
                .filter { it.width <= 2560 && it.height <= 2560 }
                .maxByOrNull { it.width * it.height } ?: jpegSizes[0]

            val prevSizes = map?.getOutputSizes(SurfaceTexture::class.java) ?: arrayOf(Size(1280, 720))
            val bestPrev = prevSizes
                .filter { it.width <= 1920 && it.height <= 1920 }
                .maxByOrNull { it.width * it.height } ?: prevSizes[0]

            preview.surfaceTexture?.setDefaultBufferSize(bestPrev.width, bestPrev.height)

            reader = ImageReader.newInstance(bestJpeg.width, bestJpeg.height, ImageFormat.JPEG, 2).apply {
                setOnImageAvailableListener({ r ->
                    val img = r.acquireLatestImage() ?: return@setOnImageAvailableListener
                    try {
                        val buf = img.planes[0].buffer
                        val bytes = ByteArray(buf.remaining())
                        buf.get(bytes)
                        val f = File(outputPath)
                        f.parentFile?.mkdirs()
                        FileOutputStream(f).use { it.write(bytes) }
                        runOnUiThread {
                            setResult(RESULT_OK)
                            finish()
                        }
                    } catch (_: Throwable) {
                        capturing = false
                    } finally {
                        img.close()
                    }
                }, bgHandler)
            }

            mgr.openCamera(camId, object : CameraDevice.StateCallback() {
                override fun onOpened(dev: CameraDevice) {
                    camera = dev
                    startPreview(dev)
                }
                override fun onDisconnected(dev: CameraDevice) { dev.close(); camera = null }
                override fun onError(dev: CameraDevice, error: Int) { dev.close(); camera = null }
            }, bgHandler)
        } catch (_: SecurityException) {
            Toast.makeText(this, R.string.camera_permission_needed, Toast.LENGTH_SHORT).show()
            finish()
        } catch (e: Exception) {
            Toast.makeText(this, e.localizedMessage ?: getString(R.string.cannot_open), Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    private fun startPreview(dev: CameraDevice) {
        val st = preview.surfaceTexture ?: return
        val previewSurface = Surface(st)
        val jpegSurface = reader?.surface ?: return
        try {
            val req = dev.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                addTarget(previewSurface)
                set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
            }
            @Suppress("DEPRECATION")
            dev.createCaptureSession(listOf(previewSurface, jpegSurface), object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(s: CameraCaptureSession) {
                    if (camera == null) return
                    session = s
                    try { s.setRepeatingRequest(req.build(), null, bgHandler) } catch (_: Exception) {}
                }
                override fun onConfigureFailed(s: CameraCaptureSession) {}
            }, bgHandler)
        } catch (_: Exception) {}
    }

    private fun capture() {
        val dev = camera ?: return
        val s = session ?: return
        val jpegSurface = reader?.surface ?: return
        if (capturing) return
        capturing = true
        try {
            val req = dev.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                addTarget(jpegSurface)
                set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                @Suppress("DEPRECATION")
                val rot = windowManager.defaultDisplay.rotation
                val deg = when (rot) {
                    Surface.ROTATION_90 -> 90
                    Surface.ROTATION_180 -> 180
                    Surface.ROTATION_270 -> 270
                    else -> 0
                }
                set(CaptureRequest.JPEG_ORIENTATION, (sensorOrientation - deg + 360) % 360)
            }
            s.capture(req.build(), object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureCompleted(session: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {}
            }, bgHandler)
        } catch (_: Exception) {
            capturing = false
        }
    }

    private fun closeCamera() {
        try { session?.close() } catch (_: Exception) {}
        session = null
        try { camera?.close() } catch (_: Exception) {}
        camera = null
        try { reader?.close() } catch (_: Exception) {}
        reader = null
    }

    companion object {
        const val EXTRA_OUTPUT_PATH = "output_path"
    }
}
