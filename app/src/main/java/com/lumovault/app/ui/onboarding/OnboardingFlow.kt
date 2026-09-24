package com.lumovault.app.ui.onboarding

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.lumovault.app.domain.model.BackupSource
import com.lumovault.app.domain.model.OptionalStepDecision
import com.lumovault.app.domain.telegram.TelegramAuthState

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
                onContinueWithoutTelegram = { navController.navigate(OnboardingStep.Permissions.route) },
            )

            LaunchedEffect(state.telegram) {
                // A restored or freshly completed session moves on by itself; nothing else does.
                if (state.telegram is TelegramAuthState.Authenticated) {
                    navController.navigate(OnboardingStep.Permissions.route) {
                        popUpTo(OnboardingStep.Connect.route) { saveState = true }
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
                    if (state.progress.backupSource == BackupSource.SelectedFolders) {
                        navController.navigate(OnboardingStep.Folders.route)
                    } else {
                        navController.navigate(OnboardingStep.Ready.route)
                    }
                },
            )
        }

        composable(OnboardingStep.Folders.route) {
            FolderSelectionScreen(
                // No scanner yet (Phase 3), so there is nothing real to list.
                folders = emptyList(),
                selectedFolders = state.progress.selectedFolders,
                onToggle = viewModel::toggleFolder,
                onBack = { navController.popBackStack() },
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
 * "Check Settings" opens the system page and stops there. Nothing is recorded from the act of
 * opening it: the battery status is read live on resume, so LumoVault never claims a setting was
 * changed because a screen was shown (PRD section 38.3).
 */
private fun Context.openBatterySettings() = runCatching {
    startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
}

private fun Context.openAppDetailsSettings() = runCatching {
    startActivity(
        Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.fromParts("package", packageName, null),
        ),
    )
}

private fun Activity.shouldShowPermissionRationaleAny(permissions: List<String>): Boolean =
    permissions.any { shouldShowRequestPermissionRationale(it) }

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
