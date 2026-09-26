package com.lumovault.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.lumovault.app.ui.LumoVaultRoot

/**
 * Hosts Compose and nothing else: edge-to-edge, then the root, which owns theme and the
 * onboarding-or-main decision.
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            LumoVaultRoot()
        }
    }
}
