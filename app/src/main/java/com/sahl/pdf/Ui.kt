package com.sahl.pdf

import android.app.UiModeManager
import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.view.View

/** Sahl family colours (same values as Sahl Gallery / Files). */
class Palette(c: Context) {
    val dark = (c.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
    val bg = if (dark) Color.parseColor("#0B110F") else Color.parseColor("#F7F5EE")
    val card = if (dark) Color.parseColor("#131C19") else Color.WHITE
    val text = if (dark) Color.WHITE else Color.parseColor("#111C18")
    val sub = if (dark) Color.parseColor("#9AA3AB") else Color.parseColor("#5C666F")
    val divider = if (dark) Color.parseColor("#2B3035") else Color.parseColor("#EAECEF")
    val accent = Color.parseColor("#0E6B4E")
    val gold = if (dark) Color.parseColor("#F5C84C") else Color.parseColor("#B88922")
}

object Ui {
    fun dp(c: Context, v: Number) = (v.toFloat() * c.resources.displayMetrics.density).toInt()

    fun isTv(c: Context) =
        c.getSystemService(UiModeManager::class.java)?.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION

    fun isArabic(c: Context) = c.resources.configuration.locales[0].language == "ar"

    fun rounded(color: Int, radius: Float, stroke: Int = 0, strokeColor: Int = 0) = GradientDrawable().apply {
        setColor(color); cornerRadius = radius
        if (stroke > 0) setStroke(stroke, strokeColor)
    }

    /** Background that shows a gold ring when focused (TV remote) or pressed. */
    fun focusable(v: View, fill: Int, radius: Float, ring: Int) {
        val w = dp(v.context, 2)
        v.background = StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused), rounded(fill, radius, w, ring))
            addState(intArrayOf(android.R.attr.state_pressed), rounded(fill, radius, w, ring))
            addState(intArrayOf(), rounded(fill, radius))
        }
        v.isFocusable = true
        v.isClickable = true
    }
}
