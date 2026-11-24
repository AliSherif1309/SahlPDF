package com.sahl.pdf

import android.content.Context
import android.view.Gravity
import android.view.View
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/** Shows installed Sahl Union apps with their launcher icons inside Settings. */
object SahlUnionApps {
    private val packages = listOf(
        "com.sahl.phone", "com.sahl.contacts", "com.sahl.messages",
        "com.sahl.camera", "com.sahl.gallery", "com.sahl.files",
        "com.sahl.adhan", "com.sahl.tools", "com.aly.sahlsync",
        "com.aly.trueqibla", "com.sahl.pdf", "com.aly.sahlpdf"
    )

    fun strip(c: Context, textColor: Int): View {
        val pm = c.packageManager
        val d = c.resources.displayMetrics.density
        fun px(v: Int) = (v * d).toInt()
        val row = LinearLayout(c).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, px(10), 0, px(4))
        }
        for (p in packages) {
            val info = try { pm.getApplicationInfo(p, 0) } catch (_: Exception) { null } ?: continue
            val item = LinearLayout(c).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                setPadding(px(6), px(4), px(10), px(4))
                setOnClickListener {
                    try { pm.getLaunchIntentForPackage(p)?.let { c.startActivity(it) } } catch (_: Exception) { }
                }
            }
            item.addView(ImageView(c).apply {
                setImageDrawable(pm.getApplicationIcon(info))
                layoutParams = LinearLayout.LayoutParams(px(44), px(44))
            })
            item.addView(TextView(c).apply {
                text = pm.getApplicationLabel(info).toString().removePrefix("Sahl ").removePrefix("سهل ")
                textSize = 11f
                setTextColor(textColor)
                maxLines = 1
                gravity = Gravity.CENTER
                setPadding(0, px(4), 0, 0)
            })
            row.addView(item)
        }
        return HorizontalScrollView(c).apply {
            isHorizontalScrollBarEnabled = false
            addView(row)
        }
    }
}
