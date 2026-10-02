package com.lumovault.app.ui.onboarding

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import com.lumovault.app.R
import com.lumovault.app.domain.model.Country
import com.lumovault.app.domain.telegram.AuthCodeChannel
import com.lumovault.app.domain.telegram.TelegramAuthState
import com.lumovault.app.ui.components.CountryPicker
import com.lumovault.app.util.PhoneNumbers
import kotlinx.coroutines.delay
import com.lumovault.app.ui.theme.IconLeading
import com.lumovault.app.ui.theme.MarkInline
import com.lumovault.app.ui.theme.RingStroke
import com.lumovault.app.ui.theme.SpaceLg
import com.lumovault.app.ui.theme.SpaceMd
import com.lumovault.app.ui.theme.SpaceSm
import com.lumovault.app.ui.theme.SpaceXs

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
    /**
     * Leaves setup without an account. Used by the primary button when the *build* cannot authenticate —
     * and offered as its own control, when it can, by [telegramSkipOffered].
     */
    onSkipTelegram: () -> Unit,
    /** True when hosted outside the setup flow — which drops the step counter, correctly. */
    standalone: Boolean = false,
    modifier: Modifier = Modifier,
) {
    var pickerOpen by rememberSaveable { mutableStateOf(false) }
    var codeDraft by rememberSaveable { mutableStateOf("") }
    var passwordDraft by rememberSaveable { mutableStateOf("") }

    val panel = state.panel
    val failure = (state.telegram as? TelegramAuthState.Failed)?.failure
    val primaryEnabled = panel.primaryEnabled(state, codeDraft, passwordDraft)

    // Drafts belong to their panel: a code typed for a number the user then changed, or a password
    // from an attempt that ended, must not survive into the next one.
    LaunchedEffect(panel) {
        if (panel != TelegramPanel.Code) codeDraft = ""
        if (panel != TelegramPanel.Password) passwordDraft = ""
    }

    // A refusal retires the code it refused. `Failed` deliberately holds this panel open, so without
    // this the rejected digits would sit in the field — and the auto-submit below would fire them at
    // Telegram's next WaitingForCode answer (a code-expiry re-emission, or a reconnect after process
    // death restored the draft), spending attempt after attempt on a code that already failed, with
    // nobody touching anything.
    LaunchedEffect(state.telegram) {
        if (state.telegram is TelegramAuthState.Failed) codeDraft = ""
    }

    // Telegram's own behaviour: a code that is complete is a code that is submitted. It fires only
    // when Telegram stated the length — a guessed one would submit half-typed digits — and never
    // while a verification is in flight.
    val expectedLength = (state.telegram as? TelegramAuthState.WaitingForCode)?.codeLength
    LaunchedEffect(codeDraft, expectedLength, state.busy, panel) {
        if (panel == TelegramPanel.Code && !state.busy &&
            expectedLength != null && codeDraft.length == expectedLength
        ) {
            onSubmitCode(codeDraft)
        }
    }

    OnboardingScaffold(
        step = if (standalone) null else 3,
        totalSteps = ONBOARDING_STEPS,
        title = stringResource(panel.titleRes()),
        description = when (panel) {
            TelegramPanel.Phone -> stringResource(R.string.connect_description)
            TelegramPanel.Code -> codeDescription(state.destinationHint, state.codeChannel)
            TelegramPanel.Password -> stringResource(R.string.password_description)
        },
        primaryLabel = stringResource(panel.actionRes()),
        primaryEnabled = primaryEnabled,
        primaryBusy = state.busy,
        onPrimary = {
            when (panel) {
                TelegramPanel.Phone -> if (state.telegramUnavailable) onSkipTelegram() else onSubmitPhoneNumber()
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
                    TelegramPanel.Code -> onSubmitCode.takeIf { codeDraft.isNotEmpty() }
                        ?.let { { onSubmitCode(codeDraft) } }
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
                onSubmit = {
                    if (state.telegramUnavailable) onSkipTelegram() else onSubmitPhoneNumber()
                },
                submitEnabled = primaryEnabled,
            )

            TelegramPanel.Code -> CodeFields(
                state = state,
                codeDraft = codeDraft,
                onCodeChange = { codeDraft = it.filter(Char::isDigit) },
                // The old code died with the resend; keeping it in the field would only invite a
                // second submit of something Telegram has already invalidated.
                onResendCode = {
                    codeDraft = ""
                    onResendCode()
                },
                onChangeNumber = onChangeNumber,
                onSubmitCode = { onSubmitCode(codeDraft) },
                submitEnabled = primaryEnabled,
            )

            TelegramPanel.Password -> PasswordField(
                state = state,
                passwordDraft = passwordDraft,
                onPasswordChange = { passwordDraft = it },
                onSubmit = {
                    onSubmitPassword(passwordDraft)
                    passwordDraft = ""
                },
                submitEnabled = primaryEnabled,
            )
        }

        // Under the primary button, not beside it: this is the choice not to do the thing the button above
        // does, and a user who wants it should not have to find it first.
        if (
            telegramSkipOffered(
                panel = panel,
                standalone = standalone,
                buildHasTelegram = !state.telegramUnavailable,
                busy = state.busy,
            )
        ) {
            OutlinedButton(onClick = onSkipTelegram, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.connect_skip))
            }
            Text(
                text = stringResource(R.string.connect_skip_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        if (state.busy) {
            BusyRow(state.telegram)
        }
    }

    if (pickerOpen) {
        CountryPicker(
            countries = state.countries,
            // The list builds off-main after the view model starts; until it lands the sheet has
            // nothing to show and must say that, not "no country matches that search".
            loading = state.countries.isEmpty(),
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
    onSubmit: () -> Unit,
    submitEnabled: Boolean,
) {
    if (state.telegramUnavailable) {
        NotConfiguredCard()
        return
    }

    val focusRequester = remember { FocusRequester() }
    // The keyboard is the point of this panel: opening it is the screen's job, not the user's.
    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    // A number is only wrong on its own account once a country has settled what a valid length is. Before
    // that, the missing country is the whole of the problem, and it is not the field's fault.
    val numberRejected = state.phoneInput.isNotEmpty() &&
        state.selectedCountry != null &&
        !state.canRequestCode
    val countryMissing = state.phoneInput.isNotEmpty() && state.selectedCountry == null

    Row(
        modifier = Modifier.fillMaxWidth(),
        // Top rather than bottom: the field grows a supporting-text line the moment it rejects a number, and a
        // bottom-aligned chip would visibly slide down while the person is still typing. Both boxes are the
        // same height, so aligning them at the top is what puts them on one line.
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(SpaceSm),
    ) {
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
            isError = numberRejected,
            supportingText = {
                when {
                    numberRejected -> Text(stringResource(R.string.error_phone_invalid_detail))
                    countryMissing -> Text(stringResource(R.string.connect_country_missing))
                    else -> Unit
                }
            },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone, imeAction = ImeAction.Go),
            keyboardActions = KeyboardActions(onGo = { if (submitEnabled) onSubmit() }),
            modifier = Modifier
                .weight(1f)
                .focusRequester(focusRequester),
        )
    }
}

@Composable
private fun CodeFields(
    state: OnboardingUiState,
    codeDraft: String,
    onCodeChange: (String) -> Unit,
    onResendCode: () -> Unit,
    onChangeNumber: () -> Unit,
    onSubmitCode: () -> Unit,
    submitEnabled: Boolean,
) {
    val waiting = state.telegram as? TelegramAuthState.WaitingForCode
    val expectedLength = waiting?.codeLength

    // Telegram's own re-send wait, counted down locally. A resend answer is usually an equal
    // WaitingForCode value, which the state flow does not re-emit — so the tap re-arms the countdown
    // itself instead of waiting for a re-emission that cannot come. Telegram enforces the real limit
    // regardless; a refusal arrives in the banner.
    var secondsLeft by rememberSaveable { mutableStateOf(waiting?.timeoutSeconds ?: 0) }
    // Armed per answer, not per composition: re-arming unconditionally would clobber the countdown a
    // saver just restored on recreation. `armedFor` starts at the current answer, so first composition
    // — restored or fresh — keeps the value it already has, and only a genuinely new WaitingForCode
    // resets it. A resend answered with an identical value does not re-emit at all, and the tap below
    // re-arms that case itself.
    var armedFor by remember { mutableStateOf(waiting) }
    LaunchedEffect(waiting) {
        if (waiting != null && waiting != armedFor) {
            armedFor = waiting
            secondsLeft = waiting.timeoutSeconds ?: 0
        }
    }
    LaunchedEffect(secondsLeft) {
        if (secondsLeft > 0) {
            delay(1_000)
            secondsLeft = secondsLeft - 1
        }
    }

    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    OutlinedTextField(
        value = codeDraft,
        onValueChange = onCodeChange,
        label = { Text(stringResource(R.string.code_label)) },
        singleLine = true,
        enabled = !state.busy,
        // A wrong code is a Failed state; the field wearing the error colour is what connects the
        // banner to the thing being corrected.
        isError = state.telegram is TelegramAuthState.Failed,
        // Digits at heading size with room between them: an OTP reads as a code, not as a sentence.
        textStyle = MaterialTheme.typography.headlineSmall.copy(letterSpacing = 0.3.em),
        keyboardOptions = KeyboardOptions(
            keyboardType = KeyboardType.NumberPassword,
            imeAction = ImeAction.Go,
        ),
        keyboardActions = KeyboardActions(onGo = { if (submitEnabled) onSubmitCode() }),
        supportingText = expectedLength?.let { length ->
            { Text(stringResource(R.string.code_length_hint, length)) }
        },
        modifier = Modifier
            .fillMaxWidth()
            .focusRequester(focusRequester),
    )

    Row(horizontalArrangement = Arrangement.spacedBy(SpaceSm)) {
        TextButton(
            onClick = {
                secondsLeft = waiting?.timeoutSeconds ?: 0
                onResendCode()
            },
            enabled = secondsLeft <= 0 && !state.busy,
        ) {
            Text(
                if (secondsLeft > 0) {
                    pluralStringResource(R.plurals.code_resend_countdown, secondsLeft, secondsLeft)
                } else {
                    stringResource(R.string.code_resend)
                },
            )
        }
        TextButton(onClick = onChangeNumber, enabled = !state.busy) {
            Text(stringResource(R.string.code_change_number))
        }
    }
}

@Composable
private fun PasswordField(
    state: OnboardingUiState,
    passwordDraft: String,
    onPasswordChange: (String) -> Unit,
    onSubmit: () -> Unit,
    submitEnabled: Boolean,
) {
    var visible by rememberSaveable { mutableStateOf(false) }
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    // Telegram's own hint for this account, captured since Phase 4 and never shown until now. It is
    // the account owner's text on the owner's screen; like every credential here, it is never logged.
    val hint = (state.telegram as? TelegramAuthState.WaitingForPassword)?.hint?.takeIf { it.isNotBlank() }

    OutlinedTextField(
        value = passwordDraft,
        onValueChange = onPasswordChange,
        label = { Text(stringResource(R.string.password_hint_label)) },
        singleLine = true,
        enabled = !state.busy,
        isError = state.telegram is TelegramAuthState.Failed,
        visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
        supportingText = hint?.let { { Text(stringResource(R.string.password_hint_prefix, it)) } },
        trailingIcon = {
            IconButton(onClick = { visible = !visible }) {
                Icon(
                    imageVector = if (visible) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                    contentDescription = stringResource(
                        if (visible) R.string.password_hide else R.string.password_show,
                    ),
                )
            }
        },
        keyboardOptions = KeyboardOptions(
            keyboardType = KeyboardType.Password,
            imeAction = ImeAction.Go,
        ),
        keyboardActions = KeyboardActions(onGo = { if (submitEnabled) onSubmit() }),
        modifier = Modifier
            .fillMaxWidth()
            .focusRequester(focusRequester),
    )
}

/**
 * The country, as a compact prefix to the number field beside it: flag and dial code, and nothing else.
 *
 * The full name does not fit here without taking the width the number itself needs, and it is one tap away in
 * the picker, which lists it beside the same flag. This control is the only place the dial code is shown — the
 * field no longer repeats it in a prefix.
 *
 * [CountryFieldHeight] is matched to the outlined field's own box rather than letting the chip size to its
 * content, so the two sit on one line and the chip cannot move when the field grows a supporting-text line
 * downward.
 */
@Composable
private fun CountryButton(country: Country?, onClick: () -> Unit, enabled: Boolean) {
    Card(
        modifier = Modifier
            .clickable(enabled = enabled, onClick = onClick)
            .semantics {
                role = Role.Button
                // The country's name is not on the chip — only the flag and dial code fit — so it is
                // said here; without it a TalkBack user cannot confirm which country is selected.
                country?.let { contentDescription = it.name }
            },
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Row(
            modifier = Modifier
                .height(CountryFieldHeight)
                .padding(horizontal = SpaceMd),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(SpaceXs),
        ) {
            Text(
                text = country?.flag ?: "?",
                style = MaterialTheme.typography.titleLarge,
                // No country yet: the glyph is a placeholder, and left alone TalkBack reads it aloud
                // as "question mark" ahead of the button's own purpose.
                modifier = if (country == null) Modifier.clearAndSetSemantics { } else Modifier,
            )
            if (country != null) {
                Text(
                    text = country.dialPrefix,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            Icon(
                imageVector = Icons.Filled.ExpandMore,
                contentDescription = stringResource(R.string.connect_country_description),
            )
        }
    }
}

/** The Material 3 outlined text field's box height, so the chip and the field line up as one control. */
private val CountryFieldHeight = 56.dp

@Composable
private fun NotConfiguredCard() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(
            modifier = Modifier.padding(SpaceLg),
            verticalArrangement = Arrangement.spacedBy(SpaceSm),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(SpaceMd),
            ) {
                Icon(
                    imageVector = Icons.Filled.Info,
                    contentDescription = null,
                    modifier = Modifier.size(IconLeading),
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

/**
 * One line saying which request is on the wire. The old fixed "checking your session" played over
 * every busy moment, including a code being sent — a label that describes a different step than the
 * one running is worse than no label, because it is believed.
 */
@Composable
private fun BusyRow(telegram: TelegramAuthState) {
    val textRes = when (telegram) {
        TelegramAuthState.SendingCode -> R.string.connect_sending_code
        TelegramAuthState.VerifyingCode -> R.string.connect_verifying_code
        TelegramAuthState.Authenticating -> R.string.connect_signing_in
        // Neutral wording: this row also shows while a stored session is being re-read.
        else -> R.string.connect_restoring
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(SpaceMd),
    ) {
        CircularProgressIndicator(modifier = Modifier.size(MarkInline), strokeWidth = RingStroke)
        Text(
            text = stringResource(textRes),
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

private fun TelegramPanel.primaryEnabled(
    state: OnboardingUiState,
    codeDraft: String,
    passwordDraft: String,
): Boolean = when (this) {
    // A build that cannot reach Telegram must not become a wall the user cannot get past.
    TelegramPanel.Phone -> state.telegramUnavailable || (state.canRequestCode && !state.busy)

    // Non-empty, and the full length when Telegram stated one: a short code is a wasted round trip,
    // and an empty Confirm button is a tap that teaches nothing.
    TelegramPanel.Code -> !state.busy && codeDraft.isNotEmpty() &&
        ((state.telegram as? TelegramAuthState.WaitingForCode)?.codeLength?.let { codeDraft.length >= it } ?: true)

    TelegramPanel.Password -> !state.busy && passwordDraft.isNotBlank()
}
