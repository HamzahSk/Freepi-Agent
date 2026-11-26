package com.freepi.lk21

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.freepi.lk21.ui.AppRoot
import com.freepi.lk21.ui.theme.FreepiTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            FreepiTheme {
                AppRoot()
            }
        }
    }
}
