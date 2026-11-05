package com.gaslog.app

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
        setContent {
            GasTheme { GasLogApp(vm) }
        }
    }

    override fun onDestroy() {
        if (vm.tokens === auth) vm.tokens = null
        super.onDestroy()
    }
}
