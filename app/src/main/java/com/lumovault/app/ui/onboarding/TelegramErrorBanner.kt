package com.lumovault.app.ui.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.lumovault.app.R
import com.lumovault.app.domain.telegram.TelegramAuthFailure

/**
 * Human-readable authentication failures (PRD section 58). The raw TDLib reason never appears here:
 * it is Telegram's text, not ours, and it can quote the number being dialled.
 */
@Composable
fun TelegramErrorBanner(
    failure: TelegramAuthFailure,
    onRetry: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val (titleRes, detailRes) = when (failure.kind) {
        TelegramAuthFailure.Kind.InvalidPhoneNumber ->
            R.string.error_phone_invalid to R.string.error_phone_invalid_detail

        TelegramAuthFailure.Kind.InvalidCode ->
            R.string.error_code_invalid to R.string.error_code_invalid_detail

        TelegramAuthFailure.Kind.CodeExpired ->
            R.string.error_code_expired to R.string.error_code_expired_detail

        TelegramAuthFailure.Kind.PasswordIncorrect ->
            R.string.error_password_incorrect to R.string.error_password_incorrect_detail

        TelegramAuthFailure.Kind.TooManyRequests ->
            R.string.error_rate_limited to R.string.error_rate_limited_detail

        TelegramAuthFailure.Kind.NetworkUnavailable ->
            R.string.error_network to R.string.error_network_detail

        TelegramAuthFailure.Kind.AccountNotFound ->
            R.string.error_no_account to R.string.error_no_account_detail

        TelegramAuthFailure.Kind.SessionInvalid ->
            R.string.error_session_invalid to R.string.error_session_invalid_detail

        TelegramAuthFailure.Kind.Unexpected ->
            R.string.error_unexpected to R.string.error_unexpected_detail
    }

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Icon(
                    imageVector = Icons.Filled.ErrorOutline,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.onErrorContainer,
                )
                Text(
                    text = stringResource(titleRes),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
            }

            val detail = if (failure.kind == TelegramAuthFailure.Kind.TooManyRequests) {
                stringResource(detailRes, rememberWaitText(failure.retryAfterSeconds))
            } else {
                stringResource(detailRes)
            }
            Text(
                text = detail,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )

            if (onRetry != null) {
                TextButton(onClick = onRetry, modifier = Modifier.padding(start = 0.dp)) {
                    Text(stringResource(R.string.error_retry))
                }
            }
        }
    }
}

/** "about 3 min" / "about 45 s" — wording chosen so it reads as an estimate, not a countdown promise. */
@Composable
private fun rememberWaitText(seconds: Int?): String = when {
    seconds == null || seconds <= 0 -> stringResource(R.string.duration_short)
    seconds >= 120 -> stringResource(R.string.duration_minutes, seconds / 60)
    else -> stringResource(R.string.duration_seconds, seconds)
}
