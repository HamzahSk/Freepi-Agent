package rofil.movie.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import rofil.movie.app.ui.AppRoot
import rofil.movie.app.ui.theme.FreepiTheme

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
