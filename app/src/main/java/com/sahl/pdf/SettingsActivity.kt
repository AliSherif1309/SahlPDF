package com.sahl.pdf

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast

class SettingsActivity : Activity() {
    companion object {
        const val PREFS = "sahl_pdf_prefs"
        const val NIGHT = "night_pages"
        const val KEEP_SCREEN = "keep_screen_on"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        SahlCore.catchCrashes(this)
        val sp = getSharedPreferences(PREFS, MODE_PRIVATE)
        val pal = Palette(this)
        fun dp(v: Number) = Ui.dp(this, v)
        val radius = dp(22).toFloat()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(pal.bg)
            fitsSystemWindows = true
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(12), dp(16), dp(8))
        }
        header.addView(android.widget.ImageView(this).apply {
            setImageResource(R.drawable.ic_back)
            setColorFilter(pal.text)
            contentDescription = getString(R.string.app_name)
            setPadding(dp(12), dp(12), dp(12), dp(12))
            Ui.focusable(this, 0, dp(24).toFloat(), pal.gold)
            setOnClickListener { finish() }
        }, LinearLayout.LayoutParams(dp(48), dp(48)))
        header.addView(TextView(this).apply {
            text = getString(R.string.settings_title)
            textSize = 20f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(pal.text)
            setPadding(dp(8), 0, 0, 0)
        })
        root.addView(header)

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(4), dp(16), dp(24))
        }

        fun card() = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = Ui.rounded(pal.card, radius)
            setPadding(dp(16), dp(6), dp(16), dp(6))
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(14) }
        }
        fun divider() = View(this).apply {
            setBackgroundColor(pal.divider)
            layoutParams = LinearLayout.LayoutParams(-1, dp(1))
        }
        fun switchRow(label: Int, key: String, def: Boolean): View {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(4), dp(10), dp(4), dp(10))
            }
            val sw = Switch(this).apply {
                isChecked = sp.getBoolean(key, def)
                isFocusable = false
                isClickable = false
            }
            row.addView(TextView(this).apply {
                text = getString(label); textSize = 15.5f; setTextColor(pal.text)
                layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
            })
            row.addView(sw)
            Ui.focusable(row, 0, dp(12).toFloat(), pal.gold)
            row.setOnClickListener {
                sw.isChecked = !sw.isChecked
                sp.edit().putBoolean(key, sw.isChecked).apply()
            }
            return row
        }
        fun textRow(title: String, subtitle: String, subColor: Int, click: (TextView) -> Unit): View {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(4), dp(14), dp(4), dp(14))
            }
            row.addView(TextView(this).apply { text = title; textSize = 15.5f; setTextColor(pal.text) })
            val sub = TextView(this).apply { text = subtitle; textSize = 13f; setTextColor(subColor) }
            row.addView(sub)
            Ui.focusable(row, 0, dp(12).toFloat(), pal.gold)
            row.setOnClickListener { click(sub) }
            return row
        }

        val reading = card()
        reading.addView(switchRow(R.string.set_night_default, NIGHT, false))
        reading.addView(divider())
        reading.addView(switchRow(R.string.set_keep_screen, KEEP_SCREEN, true))
        content.addView(reading)

        val updates = card()
        updates.addView(textRow(Updater.checkText(this), getString(R.string.version_label, BuildConfig.VERSION_NAME), pal.sub) { sub ->
            sub.text = Updater.checkingText(this)
            Thread {
                val rel = Updater.fetchNewer(this)
                runOnUiThread {
                    if (isFinishing) return@runOnUiThread
                    if (rel != null) {
                        sub.text = Updater.availableText(this, rel)
                        AlertDialog.Builder(this)
                            .setTitle(Updater.availableText(this, rel))
                            .setMessage(rel.notes.ifBlank { Updater.updateNowText(this) })
                            .setPositiveButton(Updater.updateNowText(this)) { _, _ -> Updater.download(this, rel) }
                            .setNegativeButton(android.R.string.cancel, null)
                            .show()
                    } else {
                        sub.text = Updater.upToDateText(this)
                        Toast.makeText(this, Updater.upToDateText(this), Toast.LENGTH_SHORT).show()
                    }
                }
            }.start()
        })
        content.addView(updates)

        val about = card()
        about.addView(TextView(this).apply {
            text = getString(R.string.about_title); textSize = 15.5f; typeface = Typeface.DEFAULT_BOLD
            setTextColor(pal.text); setPadding(dp(4), dp(14), dp(4), dp(4))
        })
        about.addView(TextView(this).apply {
            text = getString(R.string.about_body); textSize = 13.5f
            setTextColor(pal.sub); setPadding(dp(4), 0, dp(4), dp(10))
        })
        about.addView(divider())
        about.addView(textRow(getString(R.string.licences), "PDFium · PdfiumAndroidKt · Kotlin", pal.accent) {
            AlertDialog.Builder(this)
                .setTitle(R.string.licences)
                .setMessage(R.string.licences_body)
                .setPositiveButton(android.R.string.ok, null)
                .show()
        })
        content.addView(about)

        val union = card()
        union.addView(TextView(this).apply {
            text = if (Ui.isArabic(this@SettingsActivity)) "اتحاد سهل" else "Sahl Union"
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(pal.gold)
            setPadding(0, dp(8), 0, dp(4))
        })
        union.addView(SahlUnionApps.strip(this, pal.sub))
        content.addView(union)

        root.addView(ScrollView(this).apply { addView(content) }, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
    }
}
