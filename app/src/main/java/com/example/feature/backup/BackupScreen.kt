package com.example.feature.backup

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.FileOpen
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.SaveAlt
import androidx.compose.material.icons.rounded.Share
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
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import com.example.core.ui.components.SettingsDivider
import com.example.core.ui.localization.LocalIsRussian
import com.example.core.ui.localization.tr
import com.example.core.ui.theme.JetBrainsMonoFamily
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Handles SAF file and clipboard export/import for full client backups, official JSON, and raw tokens.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BackupScreen(
    hapticEngine: HapticEngine,
    onExportBackup: ((String) -> Unit) -> Unit,
    onExportOfficialBackup: ((String) -> Unit) -> Unit,
    onExportRawTokens: ((String) -> Unit) -> Unit = {},
    onImportBackup: (String) -> Unit,
    onShowMessage: (String) -> Unit,
    onNavigateBack: () -> Unit,
) {
    val context = LocalContext.current
    val view = LocalView.current
    val isRu = LocalIsRussian.current
    val coroutineScope = rememberCoroutineScope()
    var backupJsonInput by remember { mutableStateOf("") }
    var pendingFilePayload by remember { mutableStateOf<String?>(null) }

    val saveJsonLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/json"),
    ) { uri ->
        val payload = pendingFilePayload
        pendingFilePayload = null
        if (uri != null && payload != null) {
            coroutineScope.launch {
                val res = withContext(Dispatchers.IO) {
                    runCatching {
                        context.contentResolver.openOutputStream(uri)?.use { out ->
                            out.write(payload.toByteArray(Charsets.UTF_8))
                        } ?: error("Cannot open output stream")
                    }
                }
                res.onSuccess {
                    onShowMessage(context.getString(R.string.backup_saved_msg))
                }.onFailure { err ->
                    onShowMessage(context.getString(R.string.backup_write_error, err.message.orEmpty()))
                }
            }
        }
    }

    val saveTextLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("text/plain"),
    ) { uri ->
        val payload = pendingFilePayload
        pendingFilePayload = null
        if (uri != null && payload != null) {
            coroutineScope.launch {
                val res = withContext(Dispatchers.IO) {
                    runCatching {
                        context.contentResolver.openOutputStream(uri)?.use { out ->
                            out.write(payload.toByteArray(Charsets.UTF_8))
                        } ?: error("Cannot open output stream")
                    }
                }
                res.onSuccess {
                    onShowMessage(context.getString(R.string.backup_tokens_saved_msg))
                }.onFailure { err ->
                    onShowMessage(context.getString(R.string.backup_write_error, err.message.orEmpty()))
                }
            }
        }
    }

    val openBackupFileLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            coroutineScope.launch {
                val res = withContext(Dispatchers.IO) {
                    runCatching {
                        context.contentResolver.openInputStream(uri)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
                            ?: error("Cannot open input stream")
                    }
                }
                res.onSuccess { content ->
                    if (content.isBlank()) {
                        onShowMessage(context.getString(R.string.backup_file_empty_msg))
                    } else {
                        backupJsonInput = content
                        onImportBackup(content)
                    }
                }.onFailure { err ->
                    onShowMessage(context.getString(R.string.backup_read_error, err.message.orEmpty()))
                }
            }
        }
    }

    Scaffold(
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_backup_title)) },
                navigationIcon = {
                    IconButton(
                        onClick = {
                            hapticEngine.performNavigationClick(view)
                            onNavigateBack()
                        },
                        modifier = Modifier.testTag("backup_back_button"),
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
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .widthIn(max = 600.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
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
                        Text(
                            text = tr("Экспорт данных", "Export data"),
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text(
                            text = tr(
                                "Сохраните в файл или скопируйте в буфер обмена.",
                                "Save to a file or copy to the clipboard.",
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )

                        Text(
                            text = tr("1. Полная копия (JSON)", "1. Full client backup (JSON)"),
                            style = MaterialTheme.typography.titleSmall,
                        )
                        Text(
                            text = tr(
                                "Токены, пресеты, DNS, домены, исключения приложений и оформление.",
                                "Tokens, presets, DNS, domains, app exclusions, and appearance.",
                            ),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Button(
                                onClick = {
                                    hapticEngine.performSuccess(view)
                                    onExportBackup { json ->
                                        backupJsonInput = json
                                        pendingFilePayload = json
                                        saveJsonLauncher.launch("fptn_backup.json")
                                    }
                                },
                                modifier = Modifier
                                    .weight(1f)
                                    .height(46.dp)
                                    .testTag("backup_export_file_v8_button"),
                            ) {
                                Icon(Icons.Rounded.SaveAlt, contentDescription = null)
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(tr("В файл", "To file"))
                            }
                            OutlinedButton(
                                onClick = {
                                    hapticEngine.performSuccess(view)
                                    onExportBackup { json ->
                                        backupJsonInput = json
                                        ClipboardHelper.copyText(context, "FPTN Backup", json)
                                        onShowMessage(tr(isRu, "Резервная копия скопирована", "Backup copied to clipboard"))
                                    }
                                },
                                modifier = Modifier
                                    .weight(1f)
                                    .height(46.dp)
                                    .testTag("backup_export_button"),
                            ) {
                                Icon(Icons.Rounded.ContentCopy, contentDescription = null)
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(tr("Копировать", "Copy"))
                            }
                        }

                        SettingsDivider()

                        Text(
                            text = tr("2. Формат официального FPTN (JSON)", "2. Official FPTN format (JSON)"),
                            style = MaterialTheme.typography.titleSmall,
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            FilledTonalButton(
                                onClick = {
                                    hapticEngine.performSuccess(view)
                                    onExportOfficialBackup { json ->
                                        backupJsonInput = json
                                        pendingFilePayload = json
                                        saveJsonLauncher.launch("fptn_official.json")
                                    }
                                },
                                modifier = Modifier
                                    .weight(1f)
                                    .height(46.dp)
                                    .testTag("backup_export_file_official_button"),
                            ) {
                                Icon(Icons.Rounded.SaveAlt, contentDescription = null)
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(tr("В файл", "To file"))
                            }
                            OutlinedButton(
                                onClick = {
                                    hapticEngine.performSuccess(view)
                                    onExportOfficialBackup { json ->
                                        backupJsonInput = json
                                        ClipboardHelper.copyText(context, "FPTN Official Backup", json)
                                        onShowMessage(tr(isRu, "Копия в формате FPTN скопирована", "Official FPTN backup copied"))
                                    }
                                },
                                modifier = Modifier
                                    .weight(1f)
                                    .height(46.dp)
                                    .testTag("backup_export_official_button"),
                            ) {
                                Icon(Icons.Rounded.Share, contentDescription = null)
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(tr("Копировать", "Copy"))
                            }
                        }

                        SettingsDivider()

                        Text(
                            text = tr("3. Список токенов (TXT)", "3. Token list (TXT)"),
                            style = MaterialTheme.typography.titleSmall,
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            FilledTonalButton(
                                onClick = {
                                    hapticEngine.performSuccess(view)
                                    onExportRawTokens { raw ->
                                        if (raw.isBlank()) {
                                            onShowMessage(tr(isRu, "Нет сохранённых токенов", "No saved tokens"))
                                        } else {
                                            backupJsonInput = raw
                                            pendingFilePayload = raw
                                            saveTextLauncher.launch("fptn_tokens.txt")
                                        }
                                    }
                                },
                                modifier = Modifier
                                    .weight(1f)
                                    .height(46.dp)
                                    .testTag("backup_export_file_tokens_button"),
                            ) {
                                Icon(Icons.Rounded.Key, contentDescription = null)
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(tr("В файл", "To file"))
                            }
                            OutlinedButton(
                                onClick = {
                                    hapticEngine.performSuccess(view)
                                    onExportRawTokens { raw ->
                                        if (raw.isBlank()) {
                                            onShowMessage(tr(isRu, "Нет сохранённых токенов", "No saved tokens"))
                                        } else {
                                            backupJsonInput = raw
                                            ClipboardHelper.copyText(context, "FPTN Tokens", raw)
                                            onShowMessage(tr(isRu, "Токены скопированы", "Tokens copied to clipboard"))
                                        }
                                    }
                                },
                                modifier = Modifier
                                    .weight(1f)
                                    .height(46.dp)
                                    .testTag("backup_export_tokens_button"),
                            ) {
                                Icon(Icons.Rounded.ContentCopy, contentDescription = null)
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(tr("Копировать", "Copy"))
                            }
                        }
                    }
                }

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
                        Text(
                            text = tr("Восстановление", "Restore"),
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text(
                            text = tr(
                                "Поддерживаются полные копии, JSON официального FPTN и списки токенов.",
                                "Supports full client backups, official FPTN JSON, and raw token lists.",
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Button(
                            onClick = {
                                hapticEngine.performNavigationClick(view)
                                openBackupFileLauncher.launch(arrayOf("application/json", "text/plain", "*/*"))
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(48.dp)
                                .testTag("backup_import_file_button"),
                        ) {
                            Icon(Icons.Rounded.FileOpen, contentDescription = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(tr("Выбрать файл", "Choose file"))
                        }
                        OutlinedTextField(
                            value = backupJsonInput,
                            onValueChange = { backupJsonInput = it },
                            label = { Text(tr("JSON копии или токены", "Backup JSON or tokens")) },
                            minLines = 4,
                            maxLines = 8,
                            textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = JetBrainsMonoFamily),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("backup_json_input"),
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            OutlinedButton(
                                onClick = {
                                    hapticEngine.performNavigationClick(view)
                                    val pasted = ClipboardHelper.readText(context)
                                    if (pasted.isNotBlank()) backupJsonInput = pasted
                                },
                                modifier = Modifier
                                    .weight(1f)
                                    .height(48.dp)
                                    .testTag("backup_paste_button"),
                            ) {
                                Icon(Icons.Rounded.ContentPaste, contentDescription = null)
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(tr("Вставить", "Paste"))
                            }
                            FilledTonalButton(
                                onClick = {
                                    hapticEngine.performSuccess(view)
                                    onImportBackup(backupJsonInput)
                                },
                                enabled = backupJsonInput.isNotBlank(),
                                modifier = Modifier
                                    .weight(1f)
                                    .height(48.dp)
                                    .testTag("backup_import_button"),
                            ) {
                                Text(tr("Восстановить", "Restore"))
                            }
                        }
                    }
                }
                Spacer(modifier = Modifier.height(48.dp))
            }
        }
    }
}
