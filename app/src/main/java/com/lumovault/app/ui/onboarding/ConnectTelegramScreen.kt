package com.lumovault.app.ui.onboarding

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.lumovault.app.R
import com.lumovault.app.domain.model.Country
import com.lumovault.app.domain.telegram.AuthCodeChannel
import com.lumovault.app.domain.telegram.TelegramAuthState
import com.lumovault.app.ui.components.CountryPicker
import com.lumovault.app.util.PhoneNumbers

/**
 * Screen 3, and the only screen that talks to Telegram.
 *
 * One scaffold, three panels: which panel shows is decided by [TelegramPanel.of] from the state
 * TDLib reported, so the UI can never run ahead of authentication or fall behind it. Draft code and
 * password text live here only for the duration of the step and are cleared on submit — they are
 * never persisted and never logged.
 */
@Composable
fun ConnectTelegramScreen(
    state: OnboardingUiState,
    onSubmitPhoneNumber: () -> Unit,
    onSubmitCode: (String) -> Unit,
    onResendCode: () -> Unit,
    onChangeNumber: () -> Unit,
    onSubmitPassword: (String) -> Unit,
    onCountrySelected: (Country) -> Unit,
    onPhoneChange: (String) -> Unit,
    onBack: () -> Unit,
    onContinueWithoutTelegram: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var pickerOpen by rememberSaveable { mutableStateOf(false) }
    var codeDraft by rememberSaveable { mutableStateOf("") }
    var passwordDraft by rememberSaveable { mutableStateOf("") }

    val panel = state.panel
    val failure = (state.telegram as? TelegramAuthState.Failed)?.failure
    val waitingForCode = panel == TelegramPanel.Code

    OnboardingScaffold(
        step = 3,
        totalSteps = ONBOARDING_STEPS,
        title = stringResource(panel.titleRes()),
        description = when (panel) {
            TelegramPanel.Phone -> stringResource(R.string.connect_description)
            TelegramPanel.Code -> codeDescription(state.destinationHint, state.codeChannel)
            TelegramPanel.Password -> stringResource(R.string.password_description)
        },
        primaryLabel = stringResource(panel.actionRes()),
        primaryEnabled = panel.primaryEnabled(state),
        onPrimary = {
            when (panel) {
                TelegramPanel.Phone -> if (state.telegramUnavailable) onContinueWithoutTelegram() else onSubmitPhoneNumber()
                TelegramPanel.Code -> onSubmitCode(codeDraft)
                TelegramPanel.Password -> {
                    onSubmitPassword(passwordDraft)
                    passwordDraft = ""
                }
            }
        },
        onBack = onBack.takeIf { panel == TelegramPanel.Phone },
        modifier = modifier,
    ) {
        if (failure != null) {
            TelegramErrorBanner(
                failure = failure,
                onRetry = when (panel) {
                    TelegramPanel.Phone -> onSubmitPhoneNumber.takeIf { state.canRequestCode }
                    TelegramPanel.Code -> onSubmitCode.takeIf { codeDraft.isNotBlank() }?.let { { onSubmitCode(codeDraft) } }
                    TelegramPanel.Password -> onSubmitPassword.takeIf { passwordDraft.isNotBlank() }
                        ?.let { { onSubmitPassword(passwordDraft) } }
                },
            )
        }

        when (panel) {
            TelegramPanel.Phone -> PhoneFields(
                state = state,
                onCountryClick = { pickerOpen = true },
                onPhoneChange = onPhoneChange,
            )

            TelegramPanel.Code -> CodeFields(
                state = state,
                codeDraft = codeDraft,
                onCodeChange = { codeDraft = it.filter(Char::isDigit) },
                onResendCode = onResendCode,
                onChangeNumber = onChangeNumber,
            )

            TelegramPanel.Password -> PasswordField(
                passwordDraft = passwordDraft,
                onPasswordChange = { passwordDraft = it },
                busy = state.busy,
            )
        }

        if (state.busy && !waitingForCode) {
            BusyRow()
        }
    }

    if (pickerOpen) {
        CountryPicker(
            countries = state.countries,
            selected = state.selectedCountry,
            onSelected = {
                onCountrySelected(it)
                pickerOpen = false
            },
            onDismiss = { pickerOpen = false },
        )
    }
}

@Composable
private fun PhoneFields(
    state: OnboardingUiState,
    onCountryClick: () -> Unit,
    onPhoneChange: (String) -> Unit,
) {
    if (state.telegramUnavailable) {
        NotConfiguredCard()
        return
    }

    CountryButton(
        country = state.selectedCountry,
        onClick = onCountryClick,
        enabled = !state.busy,
    )

    OutlinedTextField(
        value = state.phoneInput,
        onValueChange = onPhoneChange,
        label = { Text(stringResource(R.string.connect_phone_label)) },
        placeholder = {
            Text(state.selectedCountry?.let { PhoneNumbers.exampleFor(it.iso2) }.orEmpty())
        },
        singleLine = true,
        enabled = !state.busy,
        isError = state.phoneInput.isNotEmpty() && !state.canRequestCode,
        supportingText = {
            if (state.phoneInput.isNotEmpty() && !state.canRequestCode) {
                Text(stringResource(R.string.error_phone_invalid_detail))
            }
        },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone, imeAction = ImeAction.Go),
        prefix = {
            state.selectedCountry?.let { Text("${it.dialPrefix} ") }
        },
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun CodeFields(
    state: OnboardingUiState,
    codeDraft: String,
    onCodeChange: (String) -> Unit,
    onResendCode: () -> Unit,
    onChangeNumber: () -> Unit,
) {
    val expectedLength = (state.telegram as? TelegramAuthState.WaitingForCode)?.codeLength

    OutlinedTextField(
        value = codeDraft,
        onValueChange = onCodeChange,
        label = { Text(stringResource(R.string.code_label)) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword, imeAction = ImeAction.Go),
        supportingText = expectedLength?.let { length ->
            { Text(stringResource(R.string.code_length_hint, length)) }
        },
        modifier = Modifier.fillMaxWidth(),
    )

    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TextButton(onClick = onResendCode, enabled = !state.busy) {
            Text(stringResource(R.string.code_resend))
        }
        TextButton(onClick = onChangeNumber, enabled = !state.busy) {
            Text(stringResource(R.string.code_change_number))
        }
    }
}

@Composable
private fun PasswordField(passwordDraft: String, onPasswordChange: (String) -> Unit, busy: Boolean) {
    OutlinedTextField(
        value = passwordDraft,
        onValueChange = onPasswordChange,
        label = { Text(stringResource(R.string.password_hint_label)) },
        singleLine = true,
        enabled = !busy,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(
            keyboardType = KeyboardType.Password,
            imeAction = ImeAction.Go,
        ),
        modifier = Modifier.fillMaxWidth(),
    )
}

/** The country control doubles as the dial-prefix display, so the number field never repeats it. */
@Composable
private fun CountryButton(country: Country?, onClick: () -> Unit, enabled: Boolean) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick)
            .semantics { role = Role.Button },
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = country?.flag ?: "?",
                style = MaterialTheme.typography.titleLarge,
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = country?.name ?: stringResource(R.string.country_picker_title),
                    style = MaterialTheme.typography.bodyLarge,
                )
                if (country != null) {
                    Text(
                        text = country.dialPrefix,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Icon(
                imageVector = Icons.Filled.ExpandMore,
                contentDescription = stringResource(R.string.connect_country_description),
            )
        }
    }
}

@Composable
private fun NotConfiguredCard() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Icon(
                    imageVector = Icons.Filled.Info,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
                Text(
                    text = stringResource(R.string.connect_not_configured),
                    style = MaterialTheme.typography.titleSmall,
                )
            }
            Text(
                text = stringResource(R.string.connect_not_configured_detail),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun BusyRow() {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
        Text(
            // Neutral wording: this row also shows while a stored session is being re-read.
            text = stringResource(R.string.connect_restoring),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * Telegram names the delivery channel, so the prompt says "calling" when a call is coming instead
 * of implying a text that isn't.
 */
@Composable
private fun codeDescription(destinationHint: String, channel: AuthCodeChannel?): String = when (channel) {
    AuthCodeChannel.Call, AuthCodeChannel.MissedCall -> stringResource(R.string.code_description_call)
    AuthCodeChannel.FlashCall -> stringResource(R.string.code_description)
    else -> if (destinationHint.isBlank()) {
        stringResource(R.string.code_description)
    } else {
        stringResource(R.string.code_description_destination, destinationHint)
    }
}

private fun TelegramPanel.titleRes(): Int = when (this) {
    TelegramPanel.Phone -> R.string.connect_title
    TelegramPanel.Code -> R.string.code_title
    TelegramPanel.Password -> R.string.password_title
}

private fun TelegramPanel.actionRes(): Int = when (this) {
    TelegramPanel.Phone -> R.string.connect_action
    TelegramPanel.Code -> R.string.code_action
    TelegramPanel.Password -> R.string.password_action
}

private fun TelegramPanel.primaryEnabled(state: OnboardingUiState): Boolean = when (this) {
    // A build that cannot reach Telegram must not become a wall the user cannot get past.
    TelegramPanel.Phone -> state.telegramUnavailable || (state.canRequestCode && !state.busy)
    TelegramPanel.Code -> !state.busy
    TelegramPanel.Password -> !state.busy
}
