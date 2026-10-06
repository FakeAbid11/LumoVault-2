package com.lumovault.app.ui.settings

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.lumovault.app.BuildConfig
import com.lumovault.app.R
import com.lumovault.app.domain.model.NotificationsStatus
import com.lumovault.app.domain.model.ThemeMode
import com.lumovault.app.ui.backup.EntryRow
import com.lumovault.app.ui.backup.TelegramWord
import com.lumovault.app.ui.components.PillTone
import com.lumovault.app.ui.components.StatusPill
import com.lumovault.app.ui.onboarding.label
import com.lumovault.app.ui.onboarding.onboardingBackdrop
import com.lumovault.app.ui.theme.LumoVaultType
import com.lumovault.app.ui.theme.MinTouchTarget
import com.lumovault.app.ui.theme.SpaceLg
import com.lumovault.app.ui.theme.SpaceMd
import com.lumovault.app.ui.theme.SpaceSm
import com.lumovault.app.ui.theme.SpaceXl
import com.lumovault.app.ui.theme.SpaceXs
import com.lumovault.app.util.openAppDetailsSettings

/**
 * PRD section 43's screens: the hub, now a bottom-bar destination of its own, and the five categories
 * that had no home.
 *
 * They are one file because they share one vocabulary — a door with a note, a label with an answer,
 * a paragraph that explains — and because none of them is allowed to assert anything it has not
 * read: every state comes from a live repository flow, every permission word is re-fetched when the
 * screen shows, and an answer the app has not looked up yet is drawn as nothing rather than as a
 * default. The two categories that already existed (Backup, Storage) are reached from the hub's rows
 * instead of being rebuilt here, which is what keeps one queue and one free-up-space from having
 * two copies that drift.
 */

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsScaffold(
    title: String,
    onNavigateUp: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    content: @Composable (PaddingValues) -> Unit,
) {
    // The setup flow's frame, the same way the backup hub uses it: the app's configuration half sits
    // on the one brand gradient rather than inventing a second background per category. Appearance is
    // a category behind this bar, so a screen that changed its own surface as a "preview" would be
    // claiming a reaction the theme system does not perform.
    Scaffold(
        modifier = modifier.fillMaxSize().background(onboardingBackdrop()),
        containerColor = Color.Transparent,
        topBar = {
            TopAppBar(
                title = { Text(title) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
                // The hub is a tab root, so it has nothing to go back to: the categories keep the
                // arrow (their parent is the hub), and the root draws no empty slot for one.
                navigationIcon = {
                    if (onNavigateUp != null) {
                        IconButton(onClick = onNavigateUp) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = stringResource(R.string.action_back),
                            )
                        }
                    }
                },
            )
        },
        content = content,
    )
}

/**
 * The list every category draws its rows into.
 *
 * The hub's own padding and rhythm — settings is the backup screens' sibling, and a different gutter
 * would make two configuration screens feel unrelated. Rows use [EntryRow]'s touch floor
 * already, so the spacing here only decides how far apart the doors stand.
 */
@Composable
private fun SettingsList(padding: PaddingValues, content: LazyListScope.() -> Unit) {
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(padding),
        contentPadding = PaddingValues(
            start = SpaceLg,
            end = SpaceLg,
            top = SpaceXs,
            bottom = SpaceXl,
        ),
        verticalArrangement = Arrangement.spacedBy(SpaceXs),
        content = content,
    )
}

/** The paragraph before a group of rows: it explains rather than labels, so it wears no title beside it. */
@Composable
private fun SectionNote(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth().padding(vertical = SpaceSm),
    )
}

/**
 * One line that answers "how does this stand?": the label, and the answer as words, a [StatusPill]
 * or a figure.
 *
 * The diagnostics panel's row, redrawn for the settings lists — same order and same rule: a state
 * earns the chip's colour, a number stays plain text, and neither ever says more than the fact
 * behind it.
 */
@Composable
private fun StatusRow(
    @StringRes label: Int,
    value: String,
    tone: PillTone? = null,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = SpaceSm),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(label),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (tone == null) {
            Text(
                text = value,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
            )
        } else {
            StatusPill(text = value, tone = tone)
        }
    }
}

/** A fact with room for its explanation underneath — not a door, so it draws no chevron and takes no press. */
@Composable
private fun InfoRow(@StringRes title: Int, @StringRes subtitle: Int) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = SpaceSm)) {
        Text(
            text = stringResource(title),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = stringResource(subtitle),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * A row that does something rather than goes somewhere: the same touch floor and shape as [EntryRow],
 * no chevron — a chevron promises a destination, and a sign-out that confirms nothing has no second
 * screen behind it. The title wears the error colour because it names a destructive act, which is
 * what the colour is for everywhere else in the app.
 */
@Composable
private fun ActionRow(title: String, subtitle: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = MinTouchTarget)
            .clip(MaterialTheme.shapes.medium)
            .clickable(onClick = onClick)
            .padding(horizontal = SpaceXs, vertical = SpaceSm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(SpaceMd),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = LumoVaultType.itemTitle,
                color = MaterialTheme.colorScheme.error,
            )
            Text(
                text = subtitle,
                style = LumoVaultType.sectionDetail,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * One theme choice as a whole row rather than a radio pinned to its label, so the touch target is the
 * line you aimed at.
 *
 * [Role.RadioButton] is the part a screen reader depends on: three of these together are a pick-one
 * group, and without the role each reads as an unexplained toggle. The [RadioButton] itself takes no
 * click — the row is the target, and a control inside a control would report itself twice.
 */
@Composable
private fun ThemeOption(label: String, selected: Boolean, onSelect: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = MinTouchTarget)
            .clip(MaterialTheme.shapes.medium)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onSelect)
            .padding(horizontal = SpaceXs, vertical = SpaceSm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(SpaceMd),
    ) {
        RadioButton(selected = selected, onClick = null)
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

/**
 * Section 43's hub: the seven categories, in the order the section lists them.
 *
 * It needs no view model — every row is a door, and the states live on the screens behind them.
 * Backup and Storage point at the screens that already exist rather than at new copies; their notes
 * say what is behind each door, because a settings list that renamed what was already built would
 * send the reader looking for a second queue.
 */
@Composable
fun SettingsHubScreen(
    onOpenAccount: () -> Unit,
    onOpenBackup: () -> Unit,
    onOpenCloud: () -> Unit,
    onOpenStorage: () -> Unit,
    onOpenAppearance: () -> Unit,
    onOpenNotifications: () -> Unit,
    onOpenAbout: () -> Unit,
    modifier: Modifier = Modifier,
) {
    SettingsScaffold(
        title = stringResource(R.string.settings_title),
        modifier = modifier,
    ) { padding ->
        SettingsList(padding) {
            item {
                EntryRow(
                    title = stringResource(R.string.settings_account),
                    subtitle = stringResource(R.string.settings_account_note),
                    onClick = onOpenAccount,
                )
            }
            item {
                EntryRow(
                    title = stringResource(R.string.settings_backup),
                    subtitle = stringResource(R.string.settings_backup_note),
                    onClick = onOpenBackup,
                )
            }
            item {
                EntryRow(
                    title = stringResource(R.string.settings_cloud),
                    subtitle = stringResource(R.string.settings_cloud_note),
                    onClick = onOpenCloud,
                )
            }
            item {
                EntryRow(
                    title = stringResource(R.string.settings_storage),
                    subtitle = stringResource(R.string.free_space_entry_note),
                    onClick = onOpenStorage,
                )
            }
            item {
                EntryRow(
                    title = stringResource(R.string.settings_appearance),
                    subtitle = stringResource(R.string.settings_appearance_note),
                    onClick = onOpenAppearance,
                )
            }
            item {
                EntryRow(
                    title = stringResource(R.string.settings_notifications),
                    subtitle = stringResource(R.string.settings_notifications_note),
                    onClick = onOpenNotifications,
                )
            }
            item {
                EntryRow(
                    title = stringResource(R.string.settings_about),
                    subtitle = stringResource(R.string.settings_about_note),
                    onClick = onOpenAbout,
                )
            }
        }
    }
}

/**
 * The account category: how the session stands, the door to change it, and the way out.
 *
 * It reports rather than manages — the sign-in panels stay behind the Reconnect row, and there is no
 * phone number to display because the app stores none: the session is the identity, and [TelegramWord]
 * gives it the same four answers diagnostics already gives, in the same colours, so one state never
 * wears two faces depending on which screen found it. Exactly one action is offered: reconnect while
 * there is something to reconnect, sign out while there is a session to end — a Reconnect button on a
 * healthy session would be a door to a screen that has nothing to do.
 *
 * Sign-out asks for nothing first. PRD section 75 keeps dialogs for decisions with a real cost, and
 * the row's own note says the part that matters: the session leaves this phone, the backups in the
 * channel stay where they are.
 */
@Composable
fun AccountSettingsScreen(
    onNavigateUp: () -> Unit,
    onConnectTelegram: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SettingsViewModel = viewModel(),
) {
    val auth by viewModel.authState.collectAsStateWithLifecycle()
    val word = TelegramWord.of(auth)

    SettingsScaffold(
        title = stringResource(R.string.settings_account),
        onNavigateUp = onNavigateUp,
        modifier = modifier,
    ) { padding ->
        SettingsList(padding) {
            item {
                StatusRow(
                    label = R.string.diag_telegram,
                    value = stringResource(word.labelRes),
                    // The diagnostics panel's four colours for the same four words: connected is the
                    // only Done, waiting on the user is quiet, not-configured is an outline because
                    // nobody has been asked yet, and unavailable is the one that means something is
                    // actually wrong.
                    tone = when (word) {
                        TelegramWord.Connected -> PillTone.Done
                        TelegramWord.WaitingForSignIn -> PillTone.Neutral
                        TelegramWord.NotConfigured -> PillTone.Missing
                        TelegramWord.Unavailable -> PillTone.Unavailable
                    },
                )
            }
            if (word == TelegramWord.Connected) {
                item {
                    ActionRow(
                        title = stringResource(R.string.account_sign_out),
                        subtitle = stringResource(R.string.account_sign_out_note),
                        onClick = viewModel::signOut,
                    )
                }
            } else {
                item {
                    EntryRow(
                        title = stringResource(R.string.account_reconnect),
                        subtitle = stringResource(R.string.account_reconnect_note),
                        onClick = onConnectTelegram,
                    )
                }
            }
        }
    }
}

/**
 * The cloud category: does this account have a channel LumoVault can see, and what has been indexed
 * from it.
 *
 * Both answers wait for Room. The channel row is simply not drawn until the first query answers —
 * null is "not looked up yet", and a default of "Not found yet" over a lookup still in flight would
 * be the app claiming a check it has not made. The count row starts absent for the same reason: "0
 * items found" during the opening read is a number the database has not produced. The third row is
 * the existing diagnostics panel, whose title already promises the technical detail this screen
 * should not reimplement a row of.
 */
@Composable
fun CloudSettingsScreen(
    onNavigateUp: () -> Unit,
    onOpenDiagnostics: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SettingsViewModel = viewModel(),
) {
    val channelAvailable by viewModel.channelAvailable.collectAsStateWithLifecycle()
    val indexedCount by viewModel.indexedCount.collectAsStateWithLifecycle()

    SettingsScaffold(
        title = stringResource(R.string.settings_cloud),
        onNavigateUp = onNavigateUp,
        modifier = modifier,
    ) { padding ->
        SettingsList(padding) {
            val available = channelAvailable
            if (available != null) {
                item {
                    StatusRow(
                        label = R.string.diag_channel,
                        value = stringResource(
                            if (available) R.string.diag_channel_available else R.string.diag_channel_missing,
                        ),
                        tone = if (available) PillTone.Done else PillTone.Missing,
                    )
                }
            }
            val indexed = indexedCount
            if (indexed != null) {
                item {
                    StatusRow(
                        label = R.string.settings_cloud_indexed,
                        // A figure, not a state: plain text with no chip, the way diagnostics reads
                        // every count it prints.
                        value = pluralStringResource(R.plurals.cloud_items_found, indexed),
                    )
                }
            }
            item {
                EntryRow(
                    title = stringResource(R.string.diagnostics_title),
                    subtitle = stringResource(R.string.diagnostics_entry_note),
                    onClick = onOpenDiagnostics,
                )
            }
        }
    }
}

/**
 * The appearance category: PRD section 44's three answers as one pick-one group.
 *
 * The selection writes the one stored [ThemeMode] the whole app reads, so this list and the theme
 * system can never hold two different truths about it. "Follow system" is a first-class
 * row rather than an escape hatch, and the note says the shipped default is Dark — which is section
 * 44's rule — so an untouched install reads as chosen rather than unexplained.
 */
@Composable
fun AppearanceSettingsScreen(
    onNavigateUp: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SettingsViewModel = viewModel(),
) {
    val mode by viewModel.themeMode.collectAsStateWithLifecycle()

    SettingsScaffold(
        title = stringResource(R.string.settings_appearance),
        onNavigateUp = onNavigateUp,
        modifier = modifier,
    ) { padding ->
        SettingsList(padding) {
            item { SectionNote(stringResource(R.string.appearance_note)) }
            item {
                ThemeOption(
                    label = stringResource(R.string.appearance_system),
                    selected = mode == ThemeMode.System,
                    onSelect = { viewModel.setThemeMode(ThemeMode.System) },
                )
            }
            item {
                ThemeOption(
                    label = stringResource(R.string.appearance_light),
                    selected = mode == ThemeMode.Light,
                    onSelect = { viewModel.setThemeMode(ThemeMode.Light) },
                )
            }
            item {
                ThemeOption(
                    label = stringResource(R.string.appearance_dark),
                    selected = mode == ThemeMode.Dark,
                    onSelect = { viewModel.setThemeMode(ThemeMode.Dark) },
                )
            }
        }
    }
}

/**
 * The notifications category: what LumoVault posts, and what Android currently allows.
 *
 * There is no switch here, and that is the point. Whether a notification can appear at all is
 * Android's answer, made in the system settings this screen's button opens — a local toggle that
 * could not change that answer would be a control pretending to work, which a settings screen least
 * of all may do. So the row reports the grant in the checklist's own words and colours (the shared
 * [NotificationsStatus.label], never a second mapping), and re-reads it every time the screen comes
 * back to the foreground: the permission is revocable in a window this app never sees, and a held
 * word would be stale by the time it was read.
 */
@Composable
fun NotificationsSettingsScreen(
    onNavigateUp: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SettingsViewModel = viewModel(),
) {
    val status by viewModel.notificationsStatus.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    DisposableEffect(lifecycleOwner, viewModel) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) viewModel.refreshNotifications()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    SettingsScaffold(
        title = stringResource(R.string.settings_notifications),
        onNavigateUp = onNavigateUp,
        modifier = modifier,
    ) { padding ->
        SettingsList(padding) {
            item { SectionNote(stringResource(R.string.setup_notifications_description)) }
            item {
                InfoRow(
                    title = R.string.backup_channel_name,
                    subtitle = R.string.backup_channel_description,
                )
            }
            item {
                StatusRow(
                    label = R.string.notifications_permission,
                    value = status.label(),
                    tone = when (status) {
                        NotificationsStatus.Granted, NotificationsStatus.NotRequired -> PillTone.Done
                        NotificationsStatus.Denied -> PillTone.Skipped
                        NotificationsStatus.Unknown -> PillTone.Missing
                    },
                )
            }
            // Only a version that has a permission to grant is worth a door to the screen that grants
            // it; "Not required" is an answer, not a gap, and it gets the sentence that says so.
            if (!status.satisfied) {
                item {
                    OutlinedButton(
                        onClick = { context.openAppDetailsSettings() },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(stringResource(R.string.notifications_open_system))
                    }
                }
            }
            if (status == NotificationsStatus.NotRequired) {
                item { SectionNote(stringResource(R.string.setup_notifications_not_required)) }
            }
        }
    }
}

/**
 * The about category: which build this is, the privacy claim in full, and what it is built from.
 *
 * The version is [BuildConfig]'s own number rather than a string someone maintains beside it, the
 * privacy paragraph is the whole promise rather than a link to one (nothing here phones home, so
 * there is no policy page to fetch), and the licenses name their libraries because that is what an
 * open-source notice is for. A "view licenses" button with nowhere to go would be decoration, and
 * this app's rule about unavailable affordances applies to buttons as much as to states.
 */
@Composable
fun AboutSettingsScreen(
    onNavigateUp: () -> Unit,
    modifier: Modifier = Modifier,
) {
    SettingsScaffold(
        title = stringResource(R.string.settings_about),
        onNavigateUp = onNavigateUp,
        modifier = modifier,
    ) { padding ->
        SettingsList(padding) {
            item {
                StatusRow(
                    label = R.string.about_version,
                    value = BuildConfig.VERSION_NAME,
                )
            }
            item {
                InfoRow(
                    title = R.string.about_privacy,
                    subtitle = R.string.about_privacy_text,
                )
            }
            item { SectionNote(stringResource(R.string.about_licenses)) }
            item {
                // Names and license titles are facts about artifacts, not copy: translating
                // "Apache License 2.0" would make the notice wrong, so they live here rather than
                // in strings.xml.
                Text(
                    text = listOf(
                        "Telegram TDLib — GNU LGPL 2.1",
                        "Apache License 2.0 — osmdroid, Coil, AndroidX Compose, Room, " +
                            "WorkManager, Media3, libphonenumber, kotlinx.coroutines",
                    ).joinToString("\n"),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth().padding(vertical = SpaceSm),
                )
            }
        }
    }
}
