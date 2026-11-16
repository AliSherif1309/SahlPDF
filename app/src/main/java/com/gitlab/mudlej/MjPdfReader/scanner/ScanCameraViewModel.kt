package com.gitlab.mudlej.MjPdfReader.scanner

import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.lifecycle.ViewModel
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

data class ScanCameraState(val busy: Boolean = false, val done: Boolean = false, val failed: Boolean = false)
class ScanCameraViewModel : ViewModel() {
    private val mutable = MutableStateFlow(ScanCameraState())
    val state = mutable.asStateFlow()
    private var output: File? = null
    private var delivered = false
    private var closed = false
    fun begin(file: File): Boolean {
        if (mutable.value.busy || mutable.value.done) return false
        output = file
        mutable.value = ScanCameraState(busy = true)
        return true
    }
    fun recover(file: File) {
        if (mutable.value.busy || mutable.value.done || !file.isFile) return
        output = file
        mutable.value = ScanCameraState(busy = true)
        viewModelScope.launch {
            val completeJpeg = withContext(Dispatchers.IO) {
                runCatching {
                    java.io.RandomAccessFile(file, "r").use {
                        require(it.length() >= 4)
                        it.seek(it.length() - 2)
                        it.readUnsignedShort() == 0xffd9
                    }
                }.getOrDefault(false)
            }
            if (completeJpeg) complete() else failed()
        }
    }
    fun complete() {
        if (closed && !delivered) output?.delete()
        else mutable.value = ScanCameraState(done = true)
    }
    fun failed() { output?.delete(); if (!closed) mutable.value = ScanCameraState(failed = true) }
    fun delivered() { delivered = true }
    override fun onCleared() { closed = true; if (!delivered) output?.delete() }
}
