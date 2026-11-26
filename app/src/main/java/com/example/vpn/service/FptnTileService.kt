package com.example.vpn.service

import android.app.PendingIntent
import android.content.Intent
import android.graphics.drawable.Icon
import android.net.VpnService
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.example.FptnApplication
import com.example.MainActivity
import com.example.R
import com.example.domain.model.VpnConnectionState
import com.example.vpn.root.RootShellManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Android Quick Settings Tile for one-tap FPTN VPN toggle.
 *
 * Uses a lifecycle-scoped [CoroutineScope] so `onClick()` never blocks the Main Thread with
 * `runBlocking`, and live-observes [VpnStateHolder.connectionState] while the shade is open.
 */
class FptnTileService : TileService() {

    private val tileScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var stateObserverJob: Job? = null
    private var clickJob: Job? = null

    override fun onStartListening() {
        super.onStartListening()
        syncTileState()
        stateObserverJob?.cancel()
        stateObserverJob = tileScope.launch {
            VpnStateHolder.connectionState.collect {
                syncTileState()
            }
        }
    }

    override fun onStopListening() {
        stateObserverJob?.cancel()
        stateObserverJob = null
        super.onStopListening()
    }

    override fun onDestroy() {
        clickJob?.cancel()
        stateObserverJob?.cancel()
        tileScope.cancel()
        super.onDestroy()
    }

    @android.annotation.SuppressLint("StartActivityAndCollapseDeprecated")
    override fun onClick() {
        super.onClick()
        when (VpnStateHolder.connectionState.value) {
            is VpnConnectionState.Connected,
            is VpnConnectionState.Connecting,
            is VpnConnectionState.SwitchingServer,
            is VpnConnectionState.Reconnecting,
            -> {
                FptnVpnService.stopDisconnect(this)
                syncTileState()
            }
            is VpnConnectionState.Disconnecting -> {
                // Ignore rapid double-tap while teardown is in progress.
                syncTileState()
            }
            else -> {
                if (clickJob?.isActive == true) return
                clickJob = tileScope.launch {
                    val useStealthRoot = withContext(Dispatchers.IO) {
                        runCatching {
                            val app = application as? FptnApplication ?: return@runCatching false
                            val settings = app.container.repository.getSettings()
                            settings.useRootMode && RootShellManager.isRootAvailable.value
                        }.getOrDefault(false)
                    }

                    val prepare = if (useStealthRoot) null else VpnService.prepare(this@FptnTileService)
                    if (prepare == null) {
                        FptnVpnService.startConnect(this@FptnTileService, null)
                    } else {
                        val launch = Intent(this@FptnTileService, MainActivity::class.java).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                            val pending = PendingIntent.getActivity(
                                this@FptnTileService,
                                REQUEST_CODE_OPEN_APP,
                                launch,
                                PendingIntent.FLAG_IMMUTABLE,
                            )
                            startActivityAndCollapse(pending)
                        } else {
                            @Suppress("DEPRECATION")
                            startActivityAndCollapse(launch)
                        }
                    }
                    syncTileState()
                }
            }
        }
    }

    private fun syncTileState() {
        val tile = qsTile ?: return
        tile.icon = Icon.createWithResource(this, R.drawable.ic_qs_fptn)
        when (val state = VpnStateHolder.connectionState.value) {
            is VpnConnectionState.Connected -> {
                tile.state = Tile.STATE_ACTIVE
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    tile.subtitle = if (state.isRootMode) {
                        "Stealth • ${state.server.name}"
                    } else {
                        state.server.name
                    }
                }
            }
            is VpnConnectionState.Connecting -> {
                tile.state = Tile.STATE_ACTIVE
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    tile.subtitle = "Подключение…"
                }
            }
            is VpnConnectionState.SwitchingServer -> {
                tile.state = Tile.STATE_ACTIVE
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    tile.subtitle = "Смена узла…"
                }
            }
            is VpnConnectionState.Reconnecting -> {
                tile.state = Tile.STATE_ACTIVE
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    tile.subtitle = "Переподключение (${state.attempt}/${state.maxAttempts})"
                }
            }
            is VpnConnectionState.Disconnecting -> {
                tile.state = Tile.STATE_INACTIVE
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    tile.subtitle = "Отключение…"
                }
            }
            else -> {
                tile.state = Tile.STATE_INACTIVE
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    tile.subtitle = "Отключено"
                }
            }
        }
        tile.updateTile()
    }

    companion object {
        private const val REQUEST_CODE_OPEN_APP = 202
    }
}
