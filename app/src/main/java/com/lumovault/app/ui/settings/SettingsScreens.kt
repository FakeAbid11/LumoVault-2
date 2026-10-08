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
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.lumovault.app.BuildConfig
import com.lumovault.app.R
import com.lumovault.app.domain.model.NotificationsStatus
import com.lumovault.app.domain.telegram.TelegramAuthState
import com.lumovault.app.domain.model.ThemeMode
import com.lumovault.app.ui.backup.TelegramWord
import com.lumovault.app.ui.components.DetailRow
import com.lumovault.app.ui.components.EntryRow
import com.lumovault.app.ui.components.IconCircle
import com.lumovault.app.ui.components.PillTone
import com.lumovault.app.ui.components.SectionHeader
import com.lumovault.app.ui.components.StatusPill
import com.lumovault.app.ui.onboarding.label
import com.lumovault.app.ui.onboarding.onboardingBackdrop
import com.lumovault.app.ui.onboarding.pillTone
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
internal fun SettingsScaffold(
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
                                contentDescription = stringResource(R.string.back),
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
internal fun SettingsList(padding: PaddingValues, content: LazyListScope.() -> Unit) {
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
internal fun SectionNote(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth().padding(vertical = SpaceSm),
    )
}

/** A fact with room for its explanation underneath — not a door, so it draws no chevron and takes no press. */
@Composable
internal fun InfoRow(@StringRes title: Int, @StringRes subtitle: Int) {
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
internal fun ActionRow(title: String, subtitle: String, onClick: () -> Unit) {
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
 * Section 43's hub: the account and cloud status, then every category in three groups.
 *
 * The doors are [settingsHubItems] rather than a composable body, so the screen is a loop and the *list*
 * is testable — see that file for why. Backup and Storage point at the screens that already exist rather
 * than at new copies.
 *
 * The card at the top is the reason this screen has a view model now. Every other screen in Settings
 * reports something the user went there to ask about; the hub used to make them walk into one to find out
 * whether they were signed in at all, which is the one fact that decides whether the rest of the app can
 * back anything up. It says the session's word and the channel's, in the same words and the same colours
 * the screens behind it use, and stops there: there is no phone number to print because the app stores
 * none, and nothing here reports a state the app has not actually looked up.
 */
@Composable
fun SettingsHubScreen(
    onOpenAccount: () -> Unit,
    onOpenBackup: () -> Unit,
    onOpenCloud: () -> Unit,
    onOpenStorage: () -> Unit,
    onOpenAppearance: () -> Unit,
    onOpenNotifications: () -> Unit,
    onOpenPermissions: () -> Unit,
    onOpenAbout: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SettingsViewModel = viewModel(),
) {
    val auth by viewModel.authState.collectAsStateWithLifecycle()
    val channelAvailable by viewModel.channelAvailable.collectAsStateWithLifecycle()

    SettingsScaffold(
        title = stringResource(R.string.settings_title),
        modifier = modifier,
    ) { padding ->
        SettingsList(padding) {
            item(key = HUB_STATUS_KEY) {
                HubStatusCard(
                    authState = auth,
                    // Null is "not looked up yet", which the card reports as nothing rather than as a
                    // refusal: "Not found yet" over a query still in flight is the app claiming to have
                    // checked when it has not, and the Account row behind the card is one press away.
                    channelAvailable = channelAvailable,
                    onOpenAccount = onOpenAccount,
                )
            }
            settingsHubItems().forEach { hubItem ->
                when (hubItem) {
                    is SettingsHubHeader -> item(key = "header-${hubItem.labelRes}") {
                        SectionHeader(hubItem.labelRes)
                    }

                    is SettingsHubDoor -> item(key = "door-${hubItem.door.name}") {
                        EntryRow(
                            title = stringResource(hubItem.labelRes),
                            subtitle = stringResource(hubItem.noteRes),
                            onClick = when (hubItem.door) {
                                SettingsDoor.Account -> onOpenAccount
                                SettingsDoor.Cloud -> onOpenCloud
                                SettingsDoor.Backup -> onOpenBackup
                                SettingsDoor.Storage -> onOpenStorage
                                SettingsDoor.Appearance -> onOpenAppearance
                                SettingsDoor.Notifications -> onOpenNotifications
                                SettingsDoor.Permissions -> onOpenPermissions
                                SettingsDoor.About -> onOpenAbout
                            },
                        )
                    }
                }
            }
        }
    }
}

/** Stable key for the card, so a rotation does not re-create the row that holds the session word. */
private const val HUB_STATUS_KEY = "hub-status"

/**
 * What is true right now, above the doors.
 *
 * Two facts and nothing else, because those two are the ones that decide what the rest of the app can do:
 * a session decides whether anything can be uploaded, and a channel decides whether there is anywhere to
 * upload it to. Both are read live, so signing out on the account screen changes this card.
 *
 * The whole card is one door to the account screen, not to the sign-in panels: signing in is a decision
 * with consequences, and the row that reports a state is not the row that resolves it. It takes
 * [Role.Button] because it opens something rather than switching something, and its title, word and pill
 * are left to be read in order — one merged description would have to invent its own sentence for the
 * two statuses, which is how a screen ends up with a fourth way of saying "connected".
 */
@Composable
private fun HubStatusCard(
    authState: TelegramAuthState,
    channelAvailable: Boolean?,
    onOpenAccount: () -> Unit,
) {
    val word = TelegramWord.of(authState)
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = MinTouchTarget)
            .clip(MaterialTheme.shapes.medium)
            .clickable(role = Role.Button, onClick = onOpenAccount),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(SpaceLg),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(SpaceMd),
        ) {
            IconCircle(
                imageVector = if (word == TelegramWord.Connected) {
                    Icons.Filled.CloudDone
                } else {
                    Icons.Filled.CloudOff
                },
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.settings_cloud_account),
                    style = LumoVaultType.itemTitle,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    // The session's own word rather than a second sentence about it, so the hub and the
                    // Account screen can never describe the same state two different ways.
                    text = stringResource(word.labelRes),
                    style = LumoVaultType.sectionDetail,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            StatusPill(
                text = stringResource(
                    // null means the cloud query has not answered, and "Not found yet" is a claim about
                    // the channel this app has not made yet.
                    when (channelAvailable) {
                        true -> R.string.diag_channel_available
                        false -> R.string.diag_channel_missing
                        null -> R.string.status_not_set
                    },
                ),
                tone = when (channelAvailable) {
                    true -> PillTone.Done
                    false -> PillTone.Missing
                    null -> PillTone.Neutral
                },
            )
        }
    }
}

/**
 * The account category: how the session stands, the door to change it, and the way out.
 *
 * It reports rather than manages — the sign-in panels stay behind the Reconnect row, and there is no
 * phone number to display because the app stores none: the session is the identity, and [TelegramWord]
 * gives it the same four answers diagnostics already gives, in the same colours, so one state never
 * wears two faces depending on which screen found it. Exactly one action is offered, chosen by
 * [accountActionFor]: reconnect while there is something to reconnect, sign out while there is a session to
 * end — a Reconnect button on a healthy session would be a door to a screen that has nothing to do.
 *
 * Signing out asks first, and says what it will and will not do. Nothing is lost — the backups in the
 * channel stay where they are — but getting back in costs a phone number and a two-step password, and
 * that is not something a row's one-line note can convey while the finger is already on the glass. See
 * [signOutNeedsConfirming] for why this changed.
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
    var confirmingSignOut by rememberSaveable { mutableStateOf(false) }

    SettingsScaffold(
        title = stringResource(R.string.settings_account),
        onNavigateUp = onNavigateUp,
        modifier = modifier,
    ) { padding ->
        SettingsList(padding) {
            item {
                DetailRow(
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
            when (accountActionFor(word)) {
                AccountAction.SignOut -> item {
                    ActionRow(
                        title = stringResource(R.string.account_sign_out),
                        subtitle = stringResource(R.string.account_sign_out_note),
                        // Asks, then signs out — never the other way round, and never in one tap.
                        onClick = { confirmingSignOut = true },
                    )
                }

                AccountAction.Connect -> item {
                    EntryRow(
                        title = stringResource(R.string.account_reconnect),
                        subtitle = stringResource(R.string.account_reconnect_note),
                        onClick = onConnectTelegram,
                    )
                }
            }
        }
    }

    if (confirmingSignOut) {
        AlertDialog(
            onDismissRequest = { confirmingSignOut = false },
            title = { Text(stringResource(R.string.account_sign_out_confirm_title)) },
            text = { Text(stringResource(R.string.account_sign_out_confirm_body)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmingSignOut = false
                        viewModel.signOut()
                    },
                ) {
                    Text(stringResource(R.string.account_sign_out_confirm_action))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmingSignOut = false }) {
                    Text(stringResource(R.string.album_cancel))
                }
            },
        )
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
            val indexed = indexedCount
            if (available != null) {
                item {
                    DetailRow(
                        label = R.string.diag_channel,
                        value = stringResource(
                            if (available) R.string.diag_channel_available else R.string.diag_channel_missing,
                        ),
                        tone = if (available) PillTone.Done else PillTone.Missing,
                    )
                }
            }
            if (indexed != null) {
                item {
                    DetailRow(
                        label = R.string.settings_cloud_indexed,
                        // A figure, not a state: plain text with no chip, the way diagnostics reads
                        // every count it prints.
                        value = pluralStringResource(R.plurals.cloud_items_found, indexed),
                    )
                }
            }
            // Room has not answered yet. Without this the screen opens with a single door at the bottom
            // and no sign that the two rows above it are still being looked up — which reads as "there is
            // no channel" to anyone who arrived here from the hub's card saying the same thing. It says
            // nothing about the channel, only that the check is running.
            if (available == null || indexed == null) {
                item { SectionNote(stringResource(R.string.settings_cloud_checking)) }
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
                DetailRow(
                    label = R.string.notifications_permission,
                    value = status.label(),
                    // The shared answer, not a second one: the setup checklist and this screen report one
                    // grant, and two `when`s over one enum is how they end up disagreeing about it.
                    tone = status.pillTone(),
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
                DetailRow(
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
                // "Apache License 2.0" would make the notice wrong, so the resource that carries them is
                // marked translatable="false". It is still a resource rather than a Kotlin literal —
                // hard-coded user-facing text is the one kind of string nothing in the build will report
                // as missing, and this is the one screen where a stale attribution would go unnoticed.
                Text(
                    text = stringResource(R.string.about_licenses_notice),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth().padding(vertical = SpaceSm),
                )
            }
        }
    }
}
