package com.janeadora.clicker.ui.theme

import android.content.Context
import android.provider.Settings as AndroidSettings

/** True when the phone's animator duration scale is 0 (Remove animations). */
fun reduceMotion(context: Context): Boolean =
    AndroidSettings.Global.getFloat(
        context.contentResolver,
        AndroidSettings.Global.ANIMATOR_DURATION_SCALE,
        1f,
    ) == 0f
