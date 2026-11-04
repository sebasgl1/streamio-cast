package com.sebasgl1.streamiocast

import android.graphics.Color
import android.os.Bundle
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import com.sebasgl1.streamiocast.ui.AppRoot
import com.sebasgl1.streamiocast.ui.StreamioTheme

/** AppCompatActivity: el botón de Cast necesita una FragmentActivity con tema AppCompat. */
class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        setContent {
            StreamioTheme {
                AppRoot()
            }
        }
    }
}
