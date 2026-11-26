package com.example.feature.tokens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.VpnKey
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.core.clipboard.ClipboardHelper
import com.example.core.haptics.HapticEngine
import com.example.core.ui.theme.JetBrainsMonoFamily
import com.example.domain.model.TokenProfile
import com.example.feature.shared.FptnUiState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TokenManagerScreen(
    uiState: FptnUiState,
    hapticEngine: HapticEngine,
    onAddToken: (name: String, rawToken: String, onSuccess: () -> Unit) -> Unit,
    onClearTokenError: () -> Unit,
    onRenameProfile: (Long, String) -> Unit,
    onToggleProfileEnabled: (Long, Boolean) -> Unit,
    onDeleteProfile: (Long) -> Unit,
    onNavigateBack: () -> Unit,
) {
    val context = LocalContext.current
    val view = LocalView.current

    var profileNameInput by remember { mutableStateOf("") }
    var rawTokenInput by remember { mutableStateOf("") }
    var renameTarget by remember { mutableStateOf<TokenProfile?>(null) }
    var renameInput by remember { mutableStateOf("") }
    var deleteTarget by remember { mutableStateOf<TokenProfile?>(null) }

    Scaffold(
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_tokens_title)) },
                navigationIcon = {
                    IconButton(
                        onClick = {
                            hapticEngine.performSegmentTick(view)
                            onNavigateBack()
                        },
                        modifier = Modifier.testTag("tokens_back_button"),
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                            contentDescription = stringResource(R.string.back_cd),
                        )
                    }
                },
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .widthIn(max = 600.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                // Add real token card (scrollable layout keeps Save/Cancel accessible above keyboard)
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.large,
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                    ),
                ) {
                    Column(
                        modifier = Modifier.padding(18.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Rounded.Add,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = stringResource(R.string.tokens_add_card_title),
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                        }

                        OutlinedTextField(
                            value = profileNameInput,
                            onValueChange = { profileNameInput = it },
                            label = { Text(stringResource(R.string.tokens_name_label)) },
                            placeholder = { Text(stringResource(R.string.tokens_name_placeholder)) },
                            singleLine = true,
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("token_name_input"),
                        )

                        OutlinedTextField(
                            value = rawTokenInput,
                            onValueChange = {
                                rawTokenInput = it
                                if (uiState.tokenErrorMessage != null) onClearTokenError()
                            },
                            label = { Text(stringResource(R.string.tokens_raw_label)) },
                            placeholder = { Text(stringResource(R.string.tokens_raw_placeholder)) },
                            textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = JetBrainsMonoFamily),
                            minLines = 2,
                            maxLines = 5,
                            isError = uiState.tokenErrorMessage != null,
                            supportingText = {
                                val err = uiState.tokenErrorMessage
                                if (err != null) {
                                    Text(text = err, color = MaterialTheme.colorScheme.error)
                                } else {
                                    Text(stringResource(R.string.tokens_raw_support))
                                }
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("token_raw_input"),
                        )

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            FilledTonalButton(
                                onClick = {
                                    hapticEngine.performSegmentTick(view)
                                    val pasted = ClipboardHelper.readText(context)
                                    if (pasted.isNotBlank()) {
                                        rawTokenInput = pasted
                                        onClearTokenError()
                                    }
                                },
                                modifier = Modifier
                                    .height(48.dp)
                                    .testTag("token_paste_button"),
                            ) {
                                Icon(Icons.Rounded.ContentPaste, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(stringResource(R.string.tokens_paste_button))
                            }

                            Button(
                                onClick = {
                                    hapticEngine.performConfirm(view)
                                    onAddToken(profileNameInput, rawTokenInput) {
                                        profileNameInput = ""
                                        rawTokenInput = ""
                                    }
                                },
                                enabled = !uiState.isSavingToken,
                                modifier = Modifier
                                    .weight(1f)
                                    .height(48.dp)
                                    .testTag("token_save_button"),
                            ) {
                                Text(
                                    text = stringResource(
                                        if (uiState.isSavingToken) R.string.tokens_saving_button else R.string.tokens_save_button,
                                    ),
                                )
                            }
                        }

                        if (rawTokenInput.isNotBlank() || profileNameInput.isNotBlank()) {
                            OutlinedButton(
                                onClick = {
                                    rawTokenInput = ""
                                    profileNameInput = ""
                                    onClearTokenError()
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(48.dp)
                                    .testTag("token_clear_button"),
                            ) {
                                Text(stringResource(R.string.tokens_cancel_button))
                            }
                        }
                    }
                }

                Text(
                    text = stringResource(R.string.tokens_saved_profiles_header, uiState.profiles.size),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onBackground,
                )

                if (uiState.profiles.isEmpty()) {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = MaterialTheme.shapes.large,
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                        ),
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(20.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.VpnKey,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(32.dp),
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = stringResource(R.string.tokens_empty_title),
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            Text(
                                text = stringResource(R.string.tokens_empty_subtitle),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                } else {
                    uiState.profiles.forEach { profile ->
                        TokenProfileCard(
                            profile = profile,
                            onToggleEnabled = { enabled ->
                                hapticEngine.performSegmentTick(view)
                                onToggleProfileEnabled(profile.id, enabled)
                            },
                            onRenameClick = {
                                hapticEngine.performSegmentTick(view)
                                renameTarget = profile
                                renameInput = profile.name
                            },
                            onDeleteClick = {
                                hapticEngine.performSegmentTick(view)
                                deleteTarget = profile
                            },
                        )
                    }
                }

                Spacer(modifier = Modifier.height(88.dp))
            }
        }
    }

    renameTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { renameTarget = null },
            title = { Text(stringResource(R.string.tokens_rename_dialog_title)) },
            text = {
                OutlinedTextField(
                    value = renameInput,
                    onValueChange = { renameInput = it },
                    label = { Text(stringResource(R.string.tokens_name_label)) },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("rename_profile_input"),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        hapticEngine.performConfirm(view)
                        onRenameProfile(target.id, renameInput)
                        renameTarget = null
                    },
                    modifier = Modifier.testTag("rename_profile_confirm"),
                ) {
                    Text(stringResource(R.string.tokens_save_short))
                }
            },
            dismissButton = {
                TextButton(onClick = { renameTarget = null }) {
                    Text(stringResource(R.string.tokens_cancel_button))
                }
            },
        )
    }

    deleteTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text(stringResource(R.string.tokens_delete_dialog_title, target.name)) },
            text = { Text(stringResource(R.string.tokens_delete_dialog_message, target.servers.size)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        hapticEngine.performReject(view)
                        onDeleteProfile(target.id)
                        deleteTarget = null
                    },
                    modifier = Modifier.testTag("delete_profile_confirm"),
                ) {
                    Text(stringResource(R.string.tokens_delete_confirm), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) {
                    Text(stringResource(R.string.tokens_cancel_button))
                }
            },
        )
    }
}

@Composable
private fun TokenProfileCard(
    profile: TokenProfile,
    onToggleEnabled: (Boolean) -> Unit,
    onRenameClick: () -> Unit,
    onDeleteClick: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("token_profile_card_${profile.id}"),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = profile.name,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = stringResource(
                            R.string.tokens_profile_meta,
                            profile.serviceName,
                            profile.servers.size,
                            profile.username,
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = profile.enabled,
                    onCheckedChange = onToggleEnabled,
                    modifier = Modifier.testTag("token_profile_switch_${profile.id}"),
                )
            }
            Spacer(modifier = Modifier.height(6.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                IconButton(
                    onClick = onRenameClick,
                    modifier = Modifier.testTag("token_profile_rename_${profile.id}"),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Edit,
                        contentDescription = stringResource(R.string.tokens_rename_cd),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
                IconButton(
                    onClick = onDeleteClick,
                    modifier = Modifier.testTag("token_profile_delete_${profile.id}"),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.DeleteOutline,
                        contentDescription = stringResource(R.string.tokens_delete_cd),
                        tint = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
    }
}
