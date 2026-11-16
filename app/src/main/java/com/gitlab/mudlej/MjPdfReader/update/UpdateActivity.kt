package com.gitlab.mudlej.MjPdfReader.update

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.gitlab.mudlej.MjPdfReader.BuildConfig
import com.gitlab.mudlej.MjPdfReader.R
import com.gitlab.mudlej.MjPdfReader.core.ui.ColorUtil
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.switchmaterial.SwitchMaterial
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class UpdateActivity : AppCompatActivity() {
    private val model: UpdateViewModel by viewModels()
    private lateinit var status: TextView
    private lateinit var details: TextView
    private lateinit var progress: ProgressBar
    private lateinit var action: MaterialButton
    private lateinit var check: MaterialButton
    private lateinit var automatic: SwitchMaterial

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val safe = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            view.setPadding(safe.left, safe.top, safe.right, safe.bottom)
            insets
        }
        root.addView(MaterialToolbar(this).apply {
            setTitle(R.string.update_title)
            setNavigationIcon(R.drawable.ic_nav_back)
            setNavigationContentDescription(R.string.pdf_tools_back)
            setNavigationOnClickListener { finish() }
        })
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val p = (20 * resources.displayMetrics.density).toInt()
            setPadding(p, p, p, p)
        }
        root.addView(ScrollView(this).apply { addView(content) }, LinearLayout.LayoutParams(-1, 0, 1f))
        content.addView(TextView(this).apply { text = getString(R.string.update_installed, BuildConfig.VERSION_NAME) })
        status = TextView(this).apply {
            id = R.id.update_status
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        content.addView(status)
        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            id = R.id.update_progress
            max = 100
        }
        content.addView(progress)
        details = TextView(this)
        content.addView(details)
        action = MaterialButton(this).apply {
            id = R.id.update_action
            setOnClickListener {
                if (model.state.value.status == UpdateState.Status.READY) install() else model.download()
            }
        }
        content.addView(action)
        check = MaterialButton(this).apply {
            id = R.id.update_check
            setText(R.string.update_check)
            setOnClickListener { model.check() }
        }
        content.addView(check)
        automatic = SwitchMaterial(this).apply {
            id = R.id.update_automatic
            setText(R.string.update_automatic)
            setOnCheckedChangeListener { _, enabled -> model.automatic(enabled) }
        }
        content.addView(automatic)
        content.addView(TextView(this).apply { setText(R.string.update_explanation) })
        setContentView(root)
        ColorUtil.colorize(this, window, supportActionBar)
        ViewCompat.requestApplyInsets(root)
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) { model.state.collect { render(it) } }
        }
    }
    private fun render(state: UpdateState) {
        status.text = when (state.status) {
            UpdateState.Status.CHECKING -> getString(R.string.update_checking)
            UpdateState.Status.CURRENT -> getString(R.string.update_current)
            UpdateState.Status.AVAILABLE -> getString(R.string.update_available, state.update?.version.orEmpty())
            UpdateState.Status.DOWNLOADING -> getString(R.string.update_downloading, state.progress)
            UpdateState.Status.READY -> getString(R.string.update_ready)
            UpdateState.Status.FAILED -> getString(R.string.update_failed)
        }
        details.text = state.update?.let {
            getString(R.string.update_details, it.version, (it.size / 1048576.0).toInt().coerceAtLeast(1)) + "\n\n" + it.notes
        }.orEmpty()
        check.isEnabled = !state.busy
        action.visibility = if (state.update != null && !state.busy) View.VISIBLE else View.GONE
        action.isEnabled = !state.busy
        action.setText(if (state.status == UpdateState.Status.READY) R.string.update_install else R.string.update_download)
        progress.visibility = if (state.busy) View.VISIBLE else View.GONE
        progress.isIndeterminate = state.status == UpdateState.Status.CHECKING
        progress.progress = state.progress
        automatic.isEnabled = !state.busy
        automatic.setOnCheckedChangeListener(null)
        automatic.isChecked = state.automatic
        automatic.setOnCheckedChangeListener { _, enabled -> model.automatic(enabled) }
    }
    private fun install() {
        if (!UpdateInstaller.canInstall(this)) {
            try {
                if (Build.VERSION.SDK_INT >= 26) {
                    startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName")))
                    status.setText(R.string.update_permission)
                }
            } catch (_: ActivityNotFoundException) { status.setText(R.string.update_permission) }
            return
        }
        val state = model.state.value
        val file = state.file ?: return
        val update = state.update ?: return
        action.isEnabled = false
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) { UpdateRepository(applicationContext).verify(file, update) }
                startActivity(UpdateInstaller.intent(this@UpdateActivity, file))
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { model.installFailed() }
            finally { action.isEnabled = true }
        }
    }
}
