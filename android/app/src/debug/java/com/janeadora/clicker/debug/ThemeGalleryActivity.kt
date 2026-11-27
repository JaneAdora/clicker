package com.janeadora.clicker.debug

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.janeadora.clicker.core.ThemeMode
import com.janeadora.clicker.ui.theme.ClickerTheme

/** Debug-only host for [ThemeGallery]. Follows the phone's light or dark setting. */
class ThemeGalleryActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { ClickerTheme(ThemeMode.System) { ThemeGallery() } }
    }
}
