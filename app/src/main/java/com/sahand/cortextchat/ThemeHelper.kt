package com.sahand.cortextchat

import android.app.Activity
import android.content.Context
import android.util.TypedValue

/**
 * Dark / light theme. Two framework themes (no AppCompat), switched with
 * setTheme() before setContentView() and a recreate() on toggle — the
 * switch is immediate, no restart. The choice is persisted in settings.
 */
object ThemeHelper {

    fun isDark(prefs: SettingsStore): Boolean = prefs.theme == "dark"

    /** Call before setContentView() in every Activity. */
    fun applyTheme(activity: Activity, prefs: SettingsStore) {
        activity.setTheme(
            if (isDark(prefs)) R.style.Theme_CorTextChat_Dark
            else R.style.Theme_CorTextChat_Light
        )
    }

    /** Flip the theme and recreate the Activity so it applies at once. */
    fun toggle(activity: Activity, prefs: SettingsStore) {
        prefs.theme = if (isDark(prefs)) "light" else "dark"
        activity.recreate()
    }

    /** Resolve one of the chat theme attributes (R.attr.chat*) to a colour. */
    fun attrColor(context: Context, attr: Int): Int {
        val tv = TypedValue()
        context.theme.resolveAttribute(attr, tv, true)
        return tv.data
    }
}
