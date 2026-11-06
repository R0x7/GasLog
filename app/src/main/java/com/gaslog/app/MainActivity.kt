package com.gaslog.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import com.gaslog.app.ui.GasLogApp
import com.gaslog.app.ui.GasTheme

class MainActivity : ComponentActivity() {
    private val vm: GasViewModel by viewModels()
    private lateinit var auth: GoogleAuth

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        auth = GoogleAuth(this) // registers the Google consent screen launcher
        vm.tokens = auth
        if (savedInstanceState == null) handle(intent)
        setContent {
            GasTheme { GasLogApp(vm) }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    private fun handle(intent: Intent?) {
        if (intent?.action == ACTION_QUICK_LOG) vm.requestQuickPhoto()
    }

    override fun onDestroy() {
        if (vm.tokens === auth) vm.tokens = null
        super.onDestroy()
    }

    companion object {
        /** Sent by the Quick Settings tile, the widget and the app shortcut: open straight to the camera. */
        const val ACTION_QUICK_LOG = "com.gaslog.app.QUICK_LOG"
    }
}
