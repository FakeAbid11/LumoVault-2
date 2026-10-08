package com.lumovault.app.ui.onboarding

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.lumovault.app.domain.model.BackupSource
import com.lumovault.app.domain.model.OptionalStepDecision
import com.lumovault.app.domain.telegram.TelegramAuthState
import com.lumovault.app.ui.navigation.navEnterTransition
import com.lumovault.app.ui.navigation.navExitTransition
import com.lumovault.app.ui.navigation.navPopEnterTransition
import com.lumovault.app.ui.navigation.navPopExitTransition
import com.lumovault.app.util.openAppDetailsSettings
import com.lumovault.app.util.openBatterySettings

/** The seven routes behind the six onboarding screens — folder picking is a sub-screen, not a step. */
internal enum class OnboardingStep(val route: String) {
    Welcome("onboarding/welcome"),
    HowItWorks("onboarding/how-it-works"),
    Connect("onboarding/connect-telegram"),
    Permissions("onboarding/permissions"),
    Sources("onboarding/backup-source"),
    Folders("onboarding/folders"),
    Ready("onboarding/ready"),
    ;

    companion object {
        val Start = Welcome.route
    }
}

/**
 * Hosts the onboarding graph and owns the effects that belong to the platform rather than to a
 * screen: permission launchers, the two settings screens, and back behaviour.
 *
 * Authentication cannot be stepped over. The Continue action on screen 3 only advances when
 * Telegram reports a session, or when this build genuinely cannot reach Telegram at all — in which
 * case the Ready screen says so instead of showing a tick.
 */
@Composable
fun OnboardingFlow(
    viewModel: OnboardingViewModel,
    modifier: Modifier = Modifier,
    navController: NavHostController = rememberNavController(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }

    var mediaRequested by rememberSaveable { mutableStateOf(false) }

    /**
     * Re-read the grants and battery state on every resume: all three can change while the app is
     * backgrounded — the user grants media access from system settings, revokes it again, flips the
     * unrestricted-battery toggle — and the statuses shown live on the cards would otherwise be the
     * ones remembered from first entry, which is exactly the remembered-vs-live state PRD 38 forbids.
     * The screen's own LaunchedEffect only covers its first appearance, not a return from Settings.
     */
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, viewModel) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) viewModel.refreshSystemStatuses()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val mediaLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        mediaRequested = true
        viewModel.refreshSystemStatuses()
    }

    val notificationsLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        viewModel.setNotificationsDecision(
            if (granted) OptionalStepDecision.Completed else OptionalStepDecision.Skipped,
        )
        viewModel.refreshSystemStatuses()
    }

    NavHost(
        navController = navController,
        startDestination = OnboardingStep.Start,
        modifier = modifier,
        // The app's motion, not this flow's — the main graph calls the same functions, so every route
        // here takes the pushed-screen shape and a step change looks like the same event wherever it
        // starts. The rules live in ui/navigation/NavMotion.kt, with the reasoning for their timings.
        enterTransition = { navEnterTransition() },
        exitTransition = { navExitTransition() },
        popEnterTransition = { navPopEnterTransition() },
        popExitTransition = { navPopExitTransition() },
        predictivePopEnterTransition = { navPopEnterTransition() },
        predictivePopExitTransition = { navPopExitTransition() },
    ) {
        composable(OnboardingStep.Welcome.route) {
            WelcomeScreen(onGetStarted = { navController.navigate(OnboardingStep.HowItWorks.route) })
        }

        composable(OnboardingStep.HowItWorks.route) {
            HowItWorksScreen(
                onContinue = { navController.navigate(OnboardingStep.Connect.route) },
                onBack = { navController.popBackStack() },
            )
        }

        composable(OnboardingStep.Connect.route) {
            // Back leaves the credential prompt, not the flow: the typed number stays put.
            BackHandler(enabled = state.panel != TelegramPanel.Phone) { viewModel.editPhoneNumber() }

            ConnectTelegramScreen(
                state = state,
                onSubmitPhoneNumber = viewModel::requestCode,
                onSubmitCode = viewModel::submitCode,
                onResendCode = viewModel::resendCode,
                onChangeNumber = viewModel::editPhoneNumber,
                onSubmitPassword = viewModel::submitPassword,
                onCountrySelected = viewModel::selectCountry,
                onPhoneChange = viewModel::onPhoneChange,
                onBack = { navController.popBackStack() },
                // Skipping leaves the folders and the queue intact: what is chosen here is queued on the
                // phone and drains the first time they connect.
                onSkipTelegram = { navController.navigate(OnboardingStep.Permissions.route) },
            )

            LaunchedEffect(state.telegram) {
                // A restored or freshly completed session moves on by itself; nothing else does. Connect is
                // popped *inclusively*: left on the stack, this effect re-fired on every return from
                // Permissions and made that screen's back arrow bounce straight back here — an
                // authenticated Connect can never be stayed on, so there is nothing to keep.
                if (state.telegram is TelegramAuthState.Authenticated) {
                    navController.navigate(OnboardingStep.Permissions.route) {
                        popUpTo(OnboardingStep.Connect.route) {
                            inclusive = true
                            saveState = true
                        }
                        launchSingleTop = true
                    }
                }
            }
        }

        composable(OnboardingStep.Permissions.route) {
            // Re-read on every visit: grants and battery settings can change while we are away.
            LaunchedEffect(Unit) { viewModel.refreshSystemStatuses() }

            PermissionsSetupScreen(
                state = state,
                canOpenBatterySettings = viewModel.canOpenBatterySettings(),
                mediaPermanentlyDenied = mediaRequested &&
                    !state.mediaAccess.allowsScanning &&
                    activity?.shouldShowPermissionRationaleAny(viewModel.mediaPermissionsToRequest()) != true,
                onRequestMedia = {
                    mediaLauncher.launch(viewModel.mediaPermissionsToRequest().toTypedArray())
                },
                onOpenMediaSettings = { context.openAppDetailsSettings() },
                onRequestNotifications = {
                    notificationsLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                },
                onSkipNotifications = { viewModel.setNotificationsDecision(OptionalStepDecision.Skipped) },
                // Opening the system page proves nothing about what the user did there, so no
                // decision is recorded here; the status chip comes back accurate on resume.
                onOpenBatterySettings = { context.openBatterySettings() },
                onSkipBackgroundBackup = { viewModel.setBackgroundBackupDecision(OptionalStepDecision.Skipped) },
                onBack = { navController.popBackStack() },
                onContinue = { navController.navigate(OnboardingStep.Sources.route) },
            )
        }

        composable(OnboardingStep.Sources.route) {
            BackupSourceScreen(
                selected = state.progress.backupSource,
                selectedFolderCount = state.progress.selectedFolders.size,
                onSelect = { viewModel.setBackupSource(it) },
                onOpenFolders = { navController.navigate(OnboardingStep.Folders.route) },
                onBack = { navController.popBackStack() },
                onContinue = {
                    // The picker is a stop on the way to Ready only while there is nothing picked:
                    // a user who already chose folders and comes back here continues forward, and
                    // "Selected folders" with zero folders is the one case that must not skip the pick.
                    if (state.progress.backupSource == BackupSource.SelectedFolders &&
                        state.progress.selectedFolders.isEmpty()
                    ) {
                        navController.navigate(OnboardingStep.Folders.route)
                    } else {
                        navController.navigate(OnboardingStep.Ready.route)
                    }
                },
            )
        }

        composable(OnboardingStep.Folders.route) {
            FolderSelectionScreen(
                // Folders come from the media index, so an unscanned device legitimately has none.
                folders = state.availableFolders,
                selectedFolders = state.progress.selectedFolders,
                onToggle = viewModel::toggleFolder,
                onBack = { navController.popBackStack() },
                onContinue = { navController.navigate(OnboardingStep.Ready.route) },
            )
        }

        composable(OnboardingStep.Ready.route) {
            ReadyScreen(
                summary = state.summary(),
                onStartBackup = viewModel::completeOnboarding,
                onBack = { navController.popBackStack() },
            )
        }
    }
}

/**
 * The battery page's own exit now lives in [openBatterySettings], beside the app-details page it is a
 * sibling of: the setup card and the Settings permissions screen both offer it, and two copies of an
 * intent is how one of them ends up opening a different page than the other.
 */
private fun Activity.shouldShowPermissionRationaleAny(permissions: List<String>): Boolean =
    permissions.any { shouldShowRequestPermissionRationale(it) }

internal tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
