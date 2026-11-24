package com.sahl.pdf

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

object SahlCore {
    const val UNION = "com.sahl.permission.UNION"

    fun catchCrashes(c: Context) {
        val app = c.applicationContext
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        if (prev is Handler) return
        Thread.setDefaultUncaughtExceptionHandler(Handler(app, prev))
    }

    private class Handler(val app: Context, val prev: Thread.UncaughtExceptionHandler?) : Thread.UncaughtExceptionHandler {
        override fun uncaughtException(t: Thread, e: Throwable) {
            try {
                val v = try { app.packageManager.getPackageInfo(app.packageName, 0).versionName } catch (_: Exception) { "?" }
                val log = "${app.packageName} v$v\n" + android.util.Log.getStackTraceString(e).take(6000)
                app.startActivity(
                    Intent(app, SahlCrashActivity::class.java)
                        .putExtra("log", log)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                )
                android.os.Process.killProcess(android.os.Process.myPid())
                System.exit(10)
            } catch (_: Exception) {
                prev?.uncaughtException(t, e)
            }
        }
    }
}

class SahlCrashActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val log = intent.getStringExtra("log") ?: "No report"
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(40, 80, 40, 40) }
        box.addView(TextView(this).apply { text = "Sahl crash report"; textSize = 22f })
        box.addView(Button(this).apply {
            text = "Copy report"
            setOnClickListener {
                getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("crash", log))
                Toast.makeText(this@SahlCrashActivity, "Copied", Toast.LENGTH_SHORT).show()
            }
        })
        box.addView(
            ScrollView(this).apply {
                addView(TextView(this@SahlCrashActivity).apply { text = log; textSize = 11f; setTextIsSelectable(true) })
            },
            LinearLayout.LayoutParams(-1, 0, 1f)
        )
        setContentView(box)
    }
}
