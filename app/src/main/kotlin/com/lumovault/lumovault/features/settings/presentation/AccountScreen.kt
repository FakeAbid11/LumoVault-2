package com.lumovault.lumovault.features.settings.presentation

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lumovault.lumovault.R
import com.lumovault.lumovault.core.auth.AuthState

/**
 * Account screen.
 *
 * Ported from account_screen.dart. The original rendered the signed-in
 * profile from a TDLib `getMe` provider; that query path is not wired in this
 * rewrite, so the signed-in state is a summary and the signed-out state is
 * the original's placeholder. Sign-out and profile details land with the
 * account batch.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccountScreen(
    onBack: () -> Unit,
    viewModel: AccountViewModel = hiltViewModel(),
) {
    val authState by viewModel.authState.collectAsStateWithLifecycle()

    Scaffold(
        topBar = { SettingsTopBar(R.string.account_title, onBack) },
    ) { padding ->
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(24.dp),
        ) {
            Spacer(Modifier.height(16.dp))
            Icon(
                Icons.Default.Person,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(72.dp),
            )
            Spacer(Modifier.height(16.dp))
            when (authState) {
                AuthState.authenticated -> {
                    Text(
                        stringResource(R.string.account_signed_in),
                        style = MaterialTheme.typography.titleLarge,
                    )
                }
                AuthState.loading -> {
                    CircularProgressIndicator()
                    Spacer(Modifier.height(16.dp))
                    Text(
                        stringResource(R.string.account_checking),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                else -> {
                    Text(
                        stringResource(R.string.account_not_signed_in),
                        style = MaterialTheme.typography.titleLarge,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        stringResource(R.string.account_not_signed_in_subtitle),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}
