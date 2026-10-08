package com.lumovault.app.ui.settings

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BatteryStd
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.NotificationsNone
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.lumovault.app.R
import com.lumovault.app.ui.components.CardAction
import com.lumovault.app.ui.components.EntryRow
import com.lumovault.app.ui.components.PermissionCard
import com.lumovault.app.ui.theme.LumoVaultType
import com.lumovault.app.util.openAppDetailsSettings
import com.lumovault.app.util.openBatterySettings

/**
 * Everything Android decides about what LumoVault may read, in one list.
 *
 * Onboarding asks for these once, at the moment the user has just been told why. This screen is the other
 * half of that conversation — the place a user comes back to *after* answering, including weeks later,
 * after a grant was revoked in system settings without the app ever hearing about it. That is why every
 * row is re-read on the way back to the foreground rather than remembered: an "Allowed" that was true when
 * the screen was composed is a lie by the time it is drawn.
 *
 * It reports, and it opens the page that fixes things. It does not re-request: a settings screen that
 * threw Android's own permission dialog unbidden is the behaviour that makes "Don't ask again" necessary
 * in the first place, and this app already has a place that asks properly.
 *
 * The cards are [PermissionCard] and the words on them are the ones [permissionRowsFor] chose, so this
 * screen and the setup screen are the same four cards saying the same four things.
 */
@Composable
fun PermissionsSettingsScreen(
    onNavigateUp: () -> Unit,
    onOpenNotifications: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SettingsViewModel = viewModel(),
) {
    val overview by viewModel.permissions.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    DisposableEffect(lifecycleOwner, viewModel) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) viewModel.refreshPermissions()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // Decided once per composition rather than per row: the content must not depend on where the user
    // happens to be looking, and a list that re-evaluated the whole answer at every index would.
    val rows = permissionRowsFor(
        media = overview.media,
        notifications = overview.notifications,
        photoLocationGranted = overview.photoLocationGranted,
        backgroundBackup = overview.backgroundBackup,
        canOpenBatterySettings = overview.canOpenBatterySettings,
    )

    SettingsScaffold(
        title = stringResource(R.string.settings_permissions),
        onNavigateUp = onNavigateUp,
        modifier = modifier,
    ) { padding ->
        SettingsList(padding) {
            item { SectionNote(stringResource(R.string.permissions_note)) }
            rows.forEach { row ->
                item(key = row.labelRes) {
                    PermissionCard(
                        title = stringResource(row.labelRes),
                        // The setup card's own sentence, reused rather than paraphrased: the reason
                        // LumoVault wants a permission does not change because it is asked elsewhere.
                        description = stringResource(row.descriptionRes),
                        icon = row.kind.icon(),
                        status = stringResource(row.statusRes),
                        tone = row.tone,
                        actions = {
                            when (row.fix) {
                                PermissionFix.OpenAppSettings -> CardAction(
                                    label = stringResource(row.fixLabelRes),
                                    onClick = { context.openAppDetailsSettings() },
                                )

                                PermissionFix.OpenBatterySettings -> CardAction(
                                    label = stringResource(row.fixLabelRes),
                                    onClick = { context.openBatterySettings() },
                                )

                                // Nothing is missing, so nothing is pressed. An enabled button here would
                                // send the user to a page to change a decision they have already made.
                                PermissionFix.None -> Unit

                                // No page exists to reach, so the card says so in words. A disabled button
                                // would be the same sentence with a control attached that cannot be used.
                                PermissionFix.Unavailable -> Text(
                                    text = stringResource(
                                        row.fixNoteRes ?: R.string.permissions_no_way_to_fix,
                                    ),
                                    style = LumoVaultType.sectionDetail,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        },
                    )
                }
            }
            // The notification channel is a channel, not a permission, so it does not belong in this list —
            // but a user who came here to turn notifications on deserves to be told where that lives.
            item {
                EntryRow(
                    title = stringResource(R.string.settings_notifications),
                    subtitle = stringResource(R.string.permissions_notifications_door_note),
                    onClick = onOpenNotifications,
                )
            }
        }
    }
}

/**
 * The mark for each row.
 *
 * Four marks rather than one reused glyph, because these four are not four flavours of the same thing:
 * the library, the alerts, the coordinates inside photographs, and the device's power budget. The
 * decision this screen makes is about whether a permission is granted; the mark is only how the row is
 * recognisable, which is why it lives here and not in [PermissionRow] — a vector is not something a
 * JVM test can construct.
 */
private fun PermissionKind.icon(): ImageVector = when (this) {
    PermissionKind.Media -> Icons.Filled.PhotoLibrary
    PermissionKind.Notifications -> Icons.Filled.NotificationsNone
    PermissionKind.PhotoLocation -> Icons.Filled.LocationOn
    PermissionKind.BackgroundBackup -> Icons.Filled.BatteryStd
}