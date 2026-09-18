package com.lumovault.lumovault

import android.Manifest
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.lumovault.lumovault.core.navigation.AppRoot
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            // The theme itself is applied inside AppRoot, where it can read
            // the persisted appearance settings; keeping a second theme wrapper
            // here would double-wrap and ignore the user's choices.
            AppRoot()
        }
    }
}

/**
 * Media permission gate.
 *
 * Android 13 splits photo/video read access, so the whole set is requested at
 * once and the result is re-checked rather than trusting the launch promise.
 * The nav graph runs only after access is granted, so no screen has to
 * defensively handle an empty MediaStore cursor.
 */
@Composable
fun MediaPermissionGate(content: @Composable () -> Unit) {
    val permissions = remember {
        buildList {
            add(Manifest.permission.READ_MEDIA_IMAGES)
            add(Manifest.permission.READ_MEDIA_VIDEO)
            add(Manifest.permission.ACCESS_MEDIA_LOCATION)
        }.toTypedArray()
    }
    var granted by remember { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions(),
    ) { result -> granted = result.values.all { it } }

    if (!granted) {
        Column(
            modifier = Modifier.fillMaxSize().padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("LumoVault needs access to your photos and videos.")
            Button(onClick = { launcher.launch(permissions) }) {
                Text("Grant access")
            }
        }
    } else {
        content()
    }
}
