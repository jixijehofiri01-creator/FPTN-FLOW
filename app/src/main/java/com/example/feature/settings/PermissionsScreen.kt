package com.example.feature.settings

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
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
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.R
import com.example.core.haptics.HapticEngine
import com.example.feature.shared.FptnUiState

/**
 * Displays runtime status and request triggers for VPN profile, notifications, battery exemption, and Root.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PermissionsScreen(
    uiState: FptnUiState,
    hapticEngine: HapticEngine,
    onRequestRootAccess: () -> Unit,
    onVerifyRootLive: () -> Unit,
    onShowMessage: (String) -> Unit,
    onNavigateBack: () -> Unit,
) {
    val context = LocalContext.current
    val view = LocalView.current
    val lifecycleOwner = LocalLifecycleOwner.current

    fun checkVpn(): Boolean = VpnService.prepare(context) == null
    fun checkNotif(): Boolean = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
        PackageManager.PERMISSION_GRANTED
    fun checkBattery(): Boolean {
        val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        return pm?.isIgnoringBatteryOptimizations(context.packageName) ?: true
    }

    var vpnGranted by remember { mutableStateOf(checkVpn()) }
    var notifGranted by remember { mutableStateOf(checkNotif()) }
    var batteryUnrestricted by remember { mutableStateOf(checkBattery()) }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                vpnGranted = checkVpn()
                notifGranted = checkNotif()
                batteryUnrestricted = checkBattery()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val vpnLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) {
        vpnGranted = checkVpn()
        onShowMessage(
            context.getString(
                if (vpnGranted) R.string.perm_vpn_granted_msg else R.string.perm_vpn_denied_msg,
            ),
        )
    }

    val notifLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        notifGranted = granted
        onShowMessage(
            context.getString(
                if (granted) R.string.perm_notif_granted_msg else R.string.perm_notif_denied_msg,
            ),
        )
    }

    Scaffold(
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_permissions_title)) },
                navigationIcon = {
                    IconButton(
                        onClick = {
                            hapticEngine.performNavigationClick(view)
                            onNavigateBack()
                        },
                        modifier = Modifier.testTag("permissions_back_button"),
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
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                PermissionStatusCard(
                    title = stringResource(R.string.perm_vpn_title),
                    status = stringResource(
                        if (vpnGranted) R.string.perm_status_granted else R.string.perm_vpn_needed,
                    ),
                    actionLabel = stringResource(
                        if (vpnGranted) R.string.perm_status_granted else R.string.perm_action_request,
                    ),
                    actionEnabled = !vpnGranted,
                    testTag = "perm_vpn_button",
                    onAction = {
                        hapticEngine.performNavigationClick(view)
                        val intent = VpnService.prepare(context)
                        if (intent != null) vpnLauncher.launch(intent) else vpnGranted = true
                    },
                )

                PermissionStatusCard(
                    title = stringResource(R.string.perm_notif_title),
                    status = stringResource(
                        if (notifGranted) R.string.perm_status_granted else R.string.perm_notif_needed,
                    ),
                    actionLabel = stringResource(
                        if (notifGranted) R.string.perm_status_granted else R.string.perm_action_allow,
                    ),
                    actionEnabled = !notifGranted && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU,
                    testTag = "perm_notif_button",
                    onAction = {
                        hapticEngine.performNavigationClick(view)
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        }
                    },
                )

                PermissionStatusCard(
                    title = stringResource(R.string.perm_battery_title),
                    status = stringResource(
                        if (batteryUnrestricted) {
                            R.string.perm_battery_unrestricted
                        } else {
                            R.string.perm_battery_needed
                        },
                    ),
                    actionLabel = stringResource(
                        if (batteryUnrestricted) R.string.perm_action_configured else R.string.perm_action_open,
                    ),
                    actionEnabled = true,
                    testTag = "perm_battery_button",
                    onAction = {
                        hapticEngine.performNavigationClick(view)
                        runCatching {
                            context.startActivity(
                                Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS),
                            )
                        }
                    },
                )

                PermissionStatusCard(
                    title = stringResource(R.string.perm_root_title),
                    status = if (uiState.isRootAvailable) {
                        stringResource(R.string.perm_root_active)
                    } else {
                        uiState.rootProbeDetail.ifBlank {
                            stringResource(R.string.perm_root_default_hint)
                        }
                    },
                    actionLabel = stringResource(
                        if (uiState.isRootAvailable) R.string.perm_action_verify else R.string.perm_action_request,
                    ),
                    actionEnabled = true,
                    testTag = "perm_root_probe_button",
                    onAction = {
                        hapticEngine.performSuccess(view)
                        if (uiState.isRootAvailable) {
                            onVerifyRootLive()
                            onShowMessage(context.getString(R.string.perm_root_verified_msg))
                        } else {
                            onRequestRootAccess()
                        }
                    },
                )
                Spacer(modifier = Modifier.height(48.dp))
            }
        }
    }
}

@Composable
private fun PermissionStatusCard(
    title: String,
    status: String,
    actionLabel: String,
    actionEnabled: Boolean,
    testTag: String,
    onAction: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(18.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = status,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            FilledTonalButton(
                onClick = onAction,
                enabled = actionEnabled,
                modifier = Modifier.testTag(testTag),
            ) {
                Text(actionLabel)
            }
        }
    }
}
