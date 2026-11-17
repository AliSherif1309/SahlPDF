package com.gitlab.mudlej.MjPdfReader.scanner

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.Settings
import android.net.Uri
import android.util.Size
import android.view.MotionEvent
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.gitlab.mudlej.MjPdfReader.R
import com.gitlab.mudlej.MjPdfReader.core.ui.ColorUtil
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

class ScanCameraActivity : AppCompatActivity() {
    private val model: ScanCameraViewModel by viewModels()
    private lateinit var destination: File
    private lateinit var preview: PreviewView
    private lateinit var status: TextView
    private lateinit var shutter: MaterialButton
    private lateinit var light: MaterialButton
    private lateinit var access: MaterialButton
    private var capture: ImageCapture? = null
    private var camera: Camera? = null
    private var torch = false
    private var starting = false
    private val permission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { allowed ->
        if (allowed) startCamera() else status.setText(R.string.scanner_camera_permission)
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val path = intent.getStringExtra(DESTINATION) ?: run { finish(); return }
        destination = File(path).canonicalFile
        val rootFolder = File(filesDir, "scans").canonicalFile
        val session = destination.parentFile
        if (session == null || session.parentFile != rootFolder || !ScanStore.validId(session.name) ||
            !Regex("""capture-[a-f0-9-]{36}\.jpg""").matches(destination.name)) {
            finish(); return
        }
        if (savedInstanceState != null) model.recover(destination)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val safe = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            view.setPadding(safe.left, safe.top, safe.right, safe.bottom); insets
        }
        root.addView(MaterialToolbar(this).apply {
            setTitle(R.string.scanner_capture)
            setNavigationIcon(R.drawable.ic_nav_back)
            setNavigationContentDescription(R.string.pdf_tools_back)
            setNavigationOnClickListener { finish() }
        })
        preview = PreviewView(this).apply {
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            scaleType = PreviewView.ScaleType.FIT_CENTER
        }
        root.addView(preview, LinearLayout.LayoutParams(-1, 0, 1f))
        status = TextView(this).apply { setText(R.string.scanner_camera_help) }
        root.addView(status)
        val row = LinearLayout(this)
        access = MaterialButton(this).apply {
            setText(R.string.scanner_camera_access)
            setOnClickListener {
                if (ContextCompat.checkSelfPermission(this@ScanCameraActivity, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                    startCamera()
                } else if (shouldShowRequestPermissionRationale(Manifest.permission.CAMERA)) {
                    permission.launch(Manifest.permission.CAMERA)
                } else {
                    startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + packageName)))
                }
            }
        }
        row.addView(access)
        light = MaterialButton(this).apply {
            setText(R.string.scanner_light)
            isCheckable = true
            isEnabled = false
            setOnClickListener {
                torch = !torch
                camera?.cameraControl?.enableTorch(torch)
                isChecked = torch
            }
        }
        row.addView(light)
        shutter = MaterialButton(this).apply {
            id = R.id.scanner_shutter
            setText(R.string.scanner_shutter)
            isEnabled = false
            setOnClickListener { takePhoto() }
        }
        row.addView(shutter)
        root.addView(android.widget.HorizontalScrollView(this).apply { addView(row) })
        setContentView(root)
        ColorUtil.colorize(this, window, supportActionBar)
        ViewCompat.requestApplyInsets(root)
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) { model.state.collect { state ->
                shutter.isEnabled = capture != null && !state.busy
                if (state.failed) status.setText(R.string.scanner_camera_failed)
                if (state.done) {
                    model.delivered()
                    setResult(RESULT_OK)
                    finish()
                }
            } }
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) startCamera()
        else if (savedInstanceState == null) permission.launch(Manifest.permission.CAMERA)
    }
    override fun onResume() {
        super.onResume()
        if (::preview.isInitialized && capture == null &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) startCamera()
    }
    private fun startCamera() {
        if (starting || capture != null) return
        starting = true
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            if (isDestroyed || isFinishing) return@addListener
            try {
                val provider = future.get()
                require(provider.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA))
                val rotation = preview.display?.rotation ?: android.view.Surface.ROTATION_0
                val selector = ResolutionSelector.Builder()
                    .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                    .setResolutionStrategy(ResolutionStrategy(Size(4000, 3000),
                        ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER)).build()
                val still = ImageCapture.Builder().setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                    .setJpegQuality(95).setTargetRotation(rotation).setResolutionSelector(selector).build()
                val view = Preview.Builder().setTargetRotation(rotation).build().apply {
                    setSurfaceProvider(preview.surfaceProvider)
                }
                provider.unbindAll()
                camera = provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, view, still)
                capture = still
                shutter.isEnabled = !model.state.value.busy
                light.isEnabled = camera?.cameraInfo?.hasFlashUnit() == true
                access.visibility = android.view.View.GONE
                preview.setOnTouchListener { _, event ->
                    if (event.action == MotionEvent.ACTION_UP) {
                        val point = preview.meteringPointFactory.createPoint(event.x, event.y)
                        camera?.cameraControl?.startFocusAndMetering(
                            FocusMeteringAction.Builder(point).setAutoCancelDuration(3, TimeUnit.SECONDS).build())
                        preview.performClick()
                    }
                    true
                }
            } catch (_: Exception) { status.setText(R.string.scanner_camera_failed) }
            finally { starting = false }
        }, ContextCompat.getMainExecutor(this))
    }
    private fun takePhoto() {
        val still = capture ?: return
        if (!model.begin(destination)) return
        val captureModel = model
        still.targetRotation = preview.display?.rotation ?: android.view.Surface.ROTATION_0
        still.takePicture(ImageCapture.OutputFileOptions.Builder(destination).build(),
            ContextCompat.getMainExecutor(this), object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) { captureModel.complete() }
                override fun onError(exception: ImageCaptureException) { captureModel.failed() }
            })
    }
    companion object { const val DESTINATION = "scan_destination" }
}
