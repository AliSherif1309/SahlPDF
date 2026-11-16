package com.gitlab.mudlej.MjPdfReader.update

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class UpdateState(
    val status: Status = Status.CHECKING,
    val update: AppUpdate? = null,
    val file: File? = null,
    val progress: Int = 0,
    val automatic: Boolean = true,
) {
    enum class Status { CHECKING, CURRENT, AVAILABLE, DOWNLOADING, READY, FAILED }
    val busy: Boolean get() = status == Status.CHECKING || status == Status.DOWNLOADING
}

class UpdateViewModel(application: Application) : AndroidViewModel(application) {
    private val mutable = MutableStateFlow(UpdateState())
    val state = mutable.asStateFlow()
    private val repository = UpdateRepository(application)
    init { check() }

    fun check() {
        if (mutable.value.status == UpdateState.Status.DOWNLOADING) return
        mutable.value = mutable.value.copy(status = UpdateState.Status.CHECKING)
        viewModelScope.launch {
            try {
                val automatic = withContext(Dispatchers.IO) {
                    getApplication<Application>().getSharedPreferences("sahl-updates", 0).getBoolean("automatic", true)
                }
                mutable.value = mutable.value.copy(automatic = automatic)
                val update = withContext(Dispatchers.IO) { repository.check() }
                mutable.value = UpdateState(
                    status = if (update == null) UpdateState.Status.CURRENT else UpdateState.Status.AVAILABLE,
                    update = update, automatic = automatic)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { mutable.value = mutable.value.copy(status = UpdateState.Status.FAILED) }
        }
    }
    fun automatic(enabled: Boolean) {
        mutable.value = mutable.value.copy(automatic = enabled)
        viewModelScope.launch(Dispatchers.IO) {
            getApplication<Application>().getSharedPreferences("sahl-updates", 0)
                .edit().putBoolean("automatic", enabled).apply()
        }
    }
    fun download() {
        val update = mutable.value.update ?: return
        if (mutable.value.busy) return
        mutable.value = mutable.value.copy(status = UpdateState.Status.DOWNLOADING, progress = 0, file = null)
        viewModelScope.launch {
            try {
                val file = withContext(Dispatchers.IO) {
                    repository.download(update) { percent -> mutable.value = mutable.value.copy(progress = percent) }
                }
                mutable.value = mutable.value.copy(status = UpdateState.Status.READY, file = file, progress = 100)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { mutable.value = mutable.value.copy(status = UpdateState.Status.FAILED, file = null) }
        }
    }
    fun installFailed() {
        mutable.value = mutable.value.copy(status = UpdateState.Status.FAILED, file = null)
    }
}
