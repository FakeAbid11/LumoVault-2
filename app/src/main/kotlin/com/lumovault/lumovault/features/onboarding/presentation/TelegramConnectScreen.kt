package com.lumovault.lumovault.features.onboarding.presentation

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lumovault.lumovault.R
import com.lumovault.lumovault.core.auth.AuthResult
import com.lumovault.lumovault.core.auth.AuthState
import kotlinx.coroutines.launch

/**
 * Telegram connect screen — phone → code → 2FA password, then done.
 *
 * Ported from telegram_connect_screen.dart, with its three timing defects
 * fixed by construction rather than by discipline:
 *
 *  - The Kotlin repository subscribes to the TDLib update stream *before*
 *    sending checkAuthenticationCode / checkAuthenticationPassword, so an
 *    `Ok` reply is never mistaken for the next state. This screen therefore
 *    trusts only the returned [AuthResult] and the standing [AuthState].
 *  - A timeout in the repository re-reads the state instead of reporting
 *    success, so this screen never sees Success from ignorance.
 *  - [AuthResult.PasswordRequired] routes to the password step — the exact
 *    transition the original missed, letting 2FA accounts "connect" while
 *    TDLib was still parked in WaitPassword.
 *
 * Success (authState == authenticated) is observed from the state flow and
 * completes onboarding exactly once. The country picker and the password
 * visibility toggle both carry semantic labels, which the original lacked.
 */
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TelegramConnectScreen(
    onFinished: () -> Unit,
    onBack: () -> Unit,
    viewModel: OnboardingViewModel = hiltViewModel(),
) {
    val authState by viewModel.authState.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

    var step by rememberSaveable { mutableStateOf(AuthStep.PHONE) }
    var dialCode by rememberSaveable { mutableStateOf("+") }
    var phone by rememberSaveable { mutableStateOf("") }
    var code by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    var passwordVisible by rememberSaveable { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var showForgot by remember { mutableStateOf(false) }

    // Success is observed from the standing state, not a call result — a
    // restored session (already authenticated) skips the whole flow.
    LaunchedEffect(authState) {
        if (authState == AuthState.authenticated) {
            viewModel.completeOnboarding()
            onFinished()
        }
    }
    LaunchedEffect(Unit) {
        busy = true
        if (viewModel.initializeAuth() && viewModel.isAlreadyAuthenticated()) {
            viewModel.completeOnboarding()
            onFinished()
        }
        busy = false
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.onboarding_telegram_title)) },
                navigationIcon = {
                    TextButton(onClick = onBack) { Text(stringResource(R.string.onboarding_back)) }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
        ) {
            Text(stringResource(R.string.onboarding_telegram_header_title),
                style = MaterialTheme.typography.titleLarge)
            Text(stringResource(R.string.onboarding_telegram_header_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(24.dp))

            error?.let {
                Text(it, color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(8.dp))
            }

            when (step) {
                AuthStep.PHONE -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = dialCode,
                            onValueChange = { dialCode = it },
                            label = { Text(stringResource(R.string.onboarding_telegram_country_code_label)) },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                            modifier = Modifier.width(110.dp),
                        )
                        Spacer(Modifier.width(8.dp))
                        OutlinedTextField(
                            value = phone,
                            onValueChange = { phone = it },
                            label = { Text(stringResource(R.string.onboarding_telegram_phone_label)) },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    Spacer(Modifier.height(16.dp))
                    Button(
                        enabled = !busy && phone.isNotBlank(),
                        onClick = {
                            scope.launch {
                                busy = true
                                error = null
                                val full = dialCode + phone.filter { ch -> ch.isDigit() }
                                when (val r = viewModel.sendCode(full)) {
                                    is AuthResult.CodeSent -> step = AuthStep.CODE
                                    is AuthResult.Error -> error = r.message
                AuthStep.CODE -> {
                    Text(stringResource(R.string.onboarding_telegram_code_sent_to, "$dialCode$phone"),
                        style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = code,
                        onValueChange = { code = it },
                        label = { Text(stringResource(R.string.onboarding_telegram_code_label)) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(16.dp))
                    Button(
                        enabled = !busy && code.isNotBlank(),
                        onClick = {
                            scope.launch {
                                busy = true
                                error = null
                                when (val r = viewModel.verifyCode(code.trim())) {
                                    AuthResult.PasswordRequired -> step = AuthStep.PASSWORD
                                    is AuthResult.Error -> error = r.message
                                    else -> Unit // Success arrives via authState.
                                }
                                busy = false
                            }
                        },
                    ) { Text(stringResource(R.string.onboarding_telegram_verify)) }
                }

                AuthStep.PASSWORD -> {
                    Text(stringResource(R.string.onboarding_telegram_password_body),
                        style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = password,
                        onValueChange = { password = it },
                        label = { Text(stringResource(R.string.onboarding_telegram_password_label)) },
                        singleLine = true,
                        visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        trailingIcon = {
                            IconButton(onClick = { passwordVisible = !passwordVisible }) {
                                Icon(
                                    if (passwordVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                    contentDescription = stringResource(
                                        if (passwordVisible) R.string.onboarding_telegram_password_hide
                                        else R.string.onboarding_telegram_password_show,
                                    ),
                                )
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(16.dp))
                    Button(
                        enabled = !busy && password.isNotBlank(),
                        onClick = {
                            scope.launch {
                                busy = true
                                error = null
                                when (val r = viewModel.submitPassword(password)) {
                                    is AuthResult.Error -> error = r.message
                                    else -> Unit // Success arrives via authState.
                                }
                                busy = false
                            }
                        },
                    ) { Text(stringResource(R.string.onboarding_telegram_verify)) }
                    TextButton(onClick = { showForgot = true }) {
                        Text(stringResource(R.string.onboarding_telegram_forgot_password))
                    }
                }
            }
        }
    }

    if (showForgot) {
        AlertDialog(
            onDismissRequest = { showForgot = false },
            title = { Text(stringResource(R.string.onboarding_telegram_forgot_password_title)) },
            text = { Text(stringResource(R.string.onboarding_telegram_forgot_password_body)) },
            confirmButton = {
                TextButton(onClick = { showForgot = false }) {
                    Text(stringResource(R.string.onboarding_telegram_forgot_password_close))
                }
            },
        )
    }
}

private enum class AuthStep { PHONE, CODE, PASSWORD }
                                    else -> Unit
                                }
                                busy = false
                            }
                        },
                    ) { Text(stringResource(R.string.onboarding_telegram_send_code)) }
                }

