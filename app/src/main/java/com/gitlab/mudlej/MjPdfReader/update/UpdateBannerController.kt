package com.gitlab.mudlej.MjPdfReader.update

import android.content.Intent
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.gitlab.mudlej.MjPdfReader.R
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class UpdateBannerController(
    private val activity: AppCompatActivity,
    private val banner: View,
    private val text: TextView,
    private val button: MaterialButton,
) {
    companion object {
        private var promptedThisRun = false
    }

    private var job: Job? = null
    init {
        button.setOnClickListener { activity.startActivity(Intent(activity, UpdateActivity::class.java)) }
    }
    fun refresh() {
        if (job?.isActive == true) return
        job = activity.lifecycleScope.launch {
            val repository = UpdateRepository(activity.applicationContext)
            val cached = withContext(Dispatchers.IO) { repository.cached() }
            show(cached)
            val due = withContext(Dispatchers.IO) {
                activity.getSharedPreferences("sahl-updates", 0).getBoolean("automatic", true) && repository.due()
            }
            if (!due) return@launch
            try {
                val update = withContext(Dispatchers.IO) { repository.check() }
                show(update)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { /* Reading documents works normally while offline. */ }
        }
    }
    private fun show(update: AppUpdate?) {
        banner.visibility = if (update == null) View.GONE else View.VISIBLE
        text.text = update?.let { activity.getString(R.string.update_available, it.version) }.orEmpty()
        if (update != null && !promptedThisRun && !activity.isFinishing && !activity.isDestroyed) {
            promptedThisRun = true
            androidx.appcompat.app.AlertDialog.Builder(activity)
                .setTitle(activity.getString(R.string.update_available, update.version))
                .setMessage(activity.getString(R.string.update_details, update.version, (update.size / (1024 * 1024)).coerceAtLeast(1)))
                .setPositiveButton(R.string.update_install) { _, _ ->
                    activity.startActivity(Intent(activity, UpdateActivity::class.java))
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }
    }
}
