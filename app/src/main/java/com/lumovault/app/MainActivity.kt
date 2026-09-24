package com.lumovault.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.lumovault.app.ui.LumoVaultApp
import com.lumovault.app.ui.LumoVaultViewModel
import com.lumovault.app.ui.theme.LumoVaultTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            val viewModel: LumoVaultViewModel = viewModel()
            val uiState by viewModel.uiState.collectAsStateWithLifecycle()

            LumoVaultTheme(mode = uiState.themeMode) {
                LumoVaultApp(onCycleThemeMode = viewModel::cycleThemeMode)
            }
        }
    }
}
