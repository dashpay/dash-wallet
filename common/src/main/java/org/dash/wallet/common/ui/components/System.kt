package org.dash.wallet.common.ui.components

import android.app.Activity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.core.view.WindowCompat

//@Composable
//fun SetSystemBarsColor() {
//    val window = (LocalContext.current as Activity).window
//    SideEffect {
//        window.statusBarColor = Color.White.toArgb()
//        window.navigationBarColor = Color.Black.toArgb()
//        WindowCompat.getInsetsController(window, window.decorView)?.apply {
//            isAppearanceLightStatusBars = false // light icons
//            isAppearanceLightNavigationBars = false
//        }
//    }
//}