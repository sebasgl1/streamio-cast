package com.sebasgl1.streamiocast

import android.content.Intent
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
        handleIntent(intent)
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

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    /** Tocar la notificación abre el reproductor de lo que está sonando. */
    private fun handleIntent(intent: Intent?) {
        if (intent?.getBooleanExtra(EXTRA_OPEN_PLAYER, false) == true) {
            (application as StremioApp).requestOpenPlayer()
        }
    }

    companion object {
        const val EXTRA_OPEN_PLAYER = "openPlayer"
    }
}
