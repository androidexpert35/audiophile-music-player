package com.androidexpert35.audiophilemusicplayer.presentation.screen.onboarding

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.FolderSpecial
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.androidexpert35.audiophilemusicplayer.R
import com.androidexpert35.audiophilemusicplayer.presentation.theme.AudiophileMusicPlayerTheme
import com.androidexpert35.audiophilemusicplayer.presentation.theme.MotionTokens
import com.androidexpert35.audiophilemusicplayer.presentation.viewmodel.onboarding.OnboardingState
import com.androidexpert35.audiophilemusicplayer.presentation.viewmodel.onboarding.OnboardingUiEffect
import com.androidexpert35.audiophilemusicplayer.presentation.viewmodel.onboarding.OnboardingUiEvent
import com.androidexpert35.audiophilemusicplayer.presentation.viewmodel.onboarding.OnboardingUiModel
import com.androidexpert35.audiophilemusicplayer.presentation.viewmodel.onboarding.OnboardingViewModel
import com.tony.coreui.presentation.components.basescreen.AppBaseScreen

/**
 * Entry point for the initial onboarding and media-indexing flow.
 *
 * @param onNavigateToHome Callback invoked once indexing completes and the app should enter the home flow.
 * @param viewModel Hilt-provided onboarding ViewModel instance.
 */
@Composable
fun OnboardingScreen(
    onNavigateToHome: () -> Unit,
    viewModel: OnboardingViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val requiredPermission = rememberRequiredMediaPermission()
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        viewModel.onEvent(OnboardingUiEvent.PermissionResult(granted))
    }
    val folderPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { treeUri ->
        viewModel.onEvent(OnboardingUiEvent.MusicFolderPicked(treeUri?.toString()))
    }

    LaunchedEffect(requiredPermission) {
        val isGranted = ContextCompat.checkSelfPermission(
            context,
            requiredPermission
        ) == PackageManager.PERMISSION_GRANTED
        viewModel.onEvent(OnboardingUiEvent.Initialize(hasMediaPermission = isGranted))
    }

    LaunchedEffect(Unit) {
        viewModel.uiEffect.collect { effect ->
            when (effect) {
                OnboardingUiEffect.RequestPermission -> permissionLauncher.launch(requiredPermission)
                // No initial URI is supplied: the chooser then opens at the device's own
                // last-used location, which is where an audiophile's music folder is far
                // more likely to be than at a hardcoded Music/ root.
                OnboardingUiEffect.PickMusicFolder -> folderPickerLauncher.launch(null)
                OnboardingUiEffect.NavigateToHome -> onNavigateToHome()
            }
        }
    }

    AppBaseScreen(uiState = uiState, onErrorDialogDismiss = viewModel::dismissErrorPopup) { model ->
        OnboardingContent(
            model = model,
            onRequestPermission = {
                viewModel.onEvent(OnboardingUiEvent.RequestPermissionTapped)
            },
            onAddMusicFolder = {
                viewModel.onEvent(OnboardingUiEvent.AddMusicFolderTapped)
            },
            onRetryIndexing = {
                viewModel.onEvent(OnboardingUiEvent.RetryIndexing)
            }
        )
    }
}

/**
 * Stateless onboarding content driven entirely by [OnboardingUiModel].
 *
 * @param model Current immutable onboarding model.
 * @param onRequestPermission Callback requesting the platform permission prompt.
 * @param onAddMusicFolder Callback requesting the platform folder chooser.
 * @param onRetryIndexing Callback retrying the scan with the saved folders.
 */
@Composable
private fun OnboardingContent(
    model: OnboardingUiModel,
    onRequestPermission: () -> Unit,
    onAddMusicFolder: () -> Unit,
    onRetryIndexing: () -> Unit = {}
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(horizontal = 24.dp, vertical = 32.dp),
        contentAlignment = Alignment.Center
    ) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(28.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerHighest
            ),
            elevation = CardDefaults.cardElevation(defaultElevation = 14.dp)
        ) {
            AnimatedContent(
                targetState = model.state,
                label = "OnboardingStepTransition",
                contentKey = { it::class.simpleName },
                transitionSpec = {
                    fadeIn(tween(MotionTokens.DurationMedium)) togetherWith
                        fadeOut(tween(MotionTokens.DurationShort))
                }
            ) { state ->
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 24.dp, vertical = 28.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(18.dp)
                ) {
                    when (state) {
                        OnboardingState.RequiresPermission -> {
                            OnboardingIconBadge(icon = Icons.Filled.FolderSpecial)
                            Text(
                                text = stringResource(R.string.onboarding_permission_title),
                                style = MaterialTheme.typography.headlineSmall,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = stringResource(R.string.onboarding_permission_message),
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Button(
                                onClick = onRequestPermission,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(text = stringResource(R.string.onboarding_permission_action))
                            }
                        }

                        is OnboardingState.RequiresMusicFolder -> {
                            OnboardingIconBadge(icon = Icons.Filled.CreateNewFolder)
                            Text(
                                text = stringResource(R.string.onboarding_folder_title),
                                style = MaterialTheme.typography.headlineSmall,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = stringResource(R.string.onboarding_folder_message),
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            if (state.hasFailedAttempt) {
                                Text(
                                    text = state.errorMessage
                                        ?: stringResource(R.string.onboarding_folder_required),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.error
                                )
                            }
                            Button(
                                onClick = onAddMusicFolder,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(text = stringResource(R.string.onboarding_folder_action))
                            }
                        }

                        is OnboardingState.IndexingFailed -> {
                            OnboardingIconBadge(icon = Icons.Filled.FolderSpecial)
                            Text(
                                text = stringResource(R.string.onboarding_scan_failed_title),
                                style = MaterialTheme.typography.headlineSmall
                            )
                            Text(
                                text = state.message,
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.error
                            )
                            Text(
                                text = stringResource(R.string.onboarding_scan_failed_message),
                                style = MaterialTheme.typography.bodyMedium
                            )
                            Button(onClick = onRetryIndexing, modifier = Modifier.fillMaxWidth()) {
                                Text(stringResource(R.string.onboarding_retry_action))
                            }
                            Button(onClick = onAddMusicFolder, modifier = Modifier.fillMaxWidth()) {
                                Text(stringResource(R.string.onboarding_folder_action))
                            }
                        }

                        is OnboardingState.Scanning -> {
                            val animatedProgress by animateFloatAsState(
                                targetValue = state.progress.coerceIn(0f, 1f),
                                animationSpec = tween(
                                    durationMillis = MotionTokens.DurationMedium,
                                    easing = MotionTokens.EasingStandard
                                ),
                                label = "OnboardingScanProgress"
                            )
                            OnboardingIconBadge(icon = Icons.Filled.GraphicEq, pulsing = true)
                            Text(
                                text = stringResource(R.string.onboarding_scanning_title),
                                style = MaterialTheme.typography.headlineSmall,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = stringResource(R.string.onboarding_scanning_message),
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            LinearProgressIndicator(
                                progress = { animatedProgress },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(MaterialTheme.shapes.large)
                            )
                            Text(
                                text = stringResource(
                                    R.string.onboarding_scanning_progress,
                                    (animatedProgress * 100).toInt()
                                ),
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Surface(
                                modifier = Modifier.fillMaxWidth(),
                                shape = MaterialTheme.shapes.large,
                                color = MaterialTheme.colorScheme.surfaceContainer
                            ) {
                                Text(
                                    text = state.currentFile.ifBlank {
                                        stringResource(R.string.onboarding_scanning_preparing)
                                    },
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp)
                                )
                            }
                        }

                        OnboardingState.Completed -> {
                            OnboardingIconBadge(icon = Icons.Filled.CheckCircle)
                            Text(
                                text = stringResource(R.string.onboarding_completed_title),
                                style = MaterialTheme.typography.headlineSmall,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = stringResource(R.string.onboarding_completed_message),
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Circular tinted badge shared by every onboarding step so the permission, scanning, and
 * completed states read as one cohesive visual language.
 *
 * @param icon Glyph rendered at the centre of the badge.
 * @param pulsing Whether to apply a gentle breathing alpha animation, used while the
 *   scanning step is active work is in progress and there is no spinner to convey motion.
 */
@Composable
private fun OnboardingIconBadge(
    icon: ImageVector,
    pulsing: Boolean = false
) {
    val pulseAlpha = if (pulsing) {
        val transition = rememberInfiniteTransition(label = "OnboardingBadgePulse")
        val alpha by transition.animateFloat(
            initialValue = 0.55f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 1100, easing = LinearEasing),
                repeatMode = RepeatMode.Reverse
            ),
            label = "OnboardingBadgePulseAlpha"
        )
        alpha
    } else {
        1f
    }

    Surface(
        shape = CircleShape,
        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = pulseAlpha),
        modifier = Modifier.size(88.dp)
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.size(40.dp)
            )
        }
    }
}

/**
 * Resolves the runtime permission required for audio-library access.
 *
 * Android 13+ uses `READ_MEDIA_AUDIO`, while older platform versions fall back to
 * `READ_EXTERNAL_STORAGE` for backward compatibility.
 *
 * @return Manifest permission string to request.
 */
@Composable
private fun rememberRequiredMediaPermission(): String = remember {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        Manifest.permission.READ_MEDIA_AUDIO
    } else {
        Manifest.permission.READ_EXTERNAL_STORAGE
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF000000)
@Composable
private fun OnboardingPermissionPreview() {
    AudiophileMusicPlayerTheme {
        OnboardingContent(
            model = OnboardingUiModel(OnboardingState.RequiresPermission),
            onRequestPermission = {},
            onAddMusicFolder = {}
        )
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF000000)
@Composable
private fun OnboardingMusicFolderPreview() {
    AudiophileMusicPlayerTheme {
        OnboardingContent(
            model = OnboardingUiModel(OnboardingState.RequiresMusicFolder()),
            onRequestPermission = {},
            onAddMusicFolder = {}
        )
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF000000)
@Composable
private fun OnboardingScanningPreview() {
    AudiophileMusicPlayerTheme {
        OnboardingContent(
            model = OnboardingUiModel(
                OnboardingState.Scanning(
                    progress = 0.62f,
                    currentFile = "Music/Reference/Kind of Blue/01 - So What.flac"
                )
            ),
            onRequestPermission = {},
            onAddMusicFolder = {}
        )
    }
}


