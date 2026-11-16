package com.gitlab.mudlej.MjPdfReader.update

import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.content.FileProvider
import java.io.File

object UpdateInstaller {
    const val APK_TYPE = "application/vnd.android.package-archive"
    fun canInstall(context: Context): Boolean =
        Build.VERSION.SDK_INT < 26 || context.packageManager.canRequestPackageInstalls()

    fun intent(context: Context, file: File): Intent {
        require(file.canonicalFile.parentFile == File(context.filesDir, "updates").canonicalFile)
        require(file.isFile && file.name.endsWith(".apk"))
        val uri = FileProvider.getUriForFile(context, context.packageName + ".fileprovider", file)
        return Intent(Intent.ACTION_VIEW).setDataAndType(uri, APK_TYPE)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            .apply { clipData = android.content.ClipData.newRawUri("Sahl PDF update", uri) }
    }
}
