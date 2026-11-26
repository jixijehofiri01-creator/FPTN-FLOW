package com.example.feature.shared

import com.example.core.performance.AdaptiveBoostTier
import com.example.data.repository.FptnProjectMeta
import com.example.data.repository.SponsorsFetcher
import com.example.domain.model.FptnServer
import com.example.domain.model.InstalledAppInfo
import com.example.domain.model.LiveWeatherState
import com.example.domain.model.LogEntry
import com.example.domain.model.SniScanProgress
import com.example.domain.model.SniScanResult
import com.example.domain.model.TokenProfile
import com.example.domain.model.TrafficStats
import com.example.domain.model.VpnConnectionState
import com.example.domain.model.VpnSettings
import com.example.vpn.sni.SniAutoScanner

/**
 * Snapshot of required system permissions and live Root access health shown on Home and Permissions screens.
 */
data class PermissionHealthState(
    val missingNotifications: Boolean = false,
    val missingBatteryUnrestricted: Boolean = false,
    val missingVpnConsent: Boolean = false,
    val rootRevokedWarning: Boolean = false,
) {
    val hasMissingSystemPermissions: Boolean
        get() = missingNotifications || missingBatteryUnrestricted || missingVpnConsent
}

/**
 * Immutable UI state emitted by [FptnViewModel] for all Compose feature screens.
 */
data class FptnUiState(
    val profiles: List<TokenProfile> = emptyList(),
    val enabledServers: List<FptnServer> = emptyList(),
    val activeServer: FptnServer? = null,
    val settings: VpnSettings = VpnSettings(),
    val connectionState: VpnConnectionState = VpnConnectionState.Disconnected,
    val trafficStats: TrafficStats = TrafficStats(),
    val isRootAvailable: Boolean = false,
    val areRootRulesActive: Boolean = false,
    val rootProbeDetail: String = "",
    val rootBoostTier: AdaptiveBoostTier = AdaptiveBoostTier.INACTIVE,
    val isRootActionBusy: Boolean = false,
    val permissionHealth: PermissionHealthState = PermissionHealthState(),
    val isPinging: Boolean = false,
    val isPingingDns: Boolean = false,
    val dnsPingResults: Map<String, Int?> = emptyMap(),
    val sniCatalog: List<String> = SniAutoScanner.fallbackDomains,
    val sniScanProgress: SniScanProgress = SniScanProgress(),
    val sniScanResults: List<SniScanResult> = emptyList(),
    val logs: List<LogEntry> = emptyList(),
    val installedApps: List<InstalledAppInfo> = emptyList(),
    val isLoadingApps: Boolean = false,
    val isSavingToken: Boolean = false,
    val projectMeta: FptnProjectMeta = SponsorsFetcher.fallbackMeta,
    val isLoadingProjectMeta: Boolean = false,
    val liveWeather: LiveWeatherState = LiveWeatherState(),
    val snackbarMessage: String? = null,
    val tokenErrorMessage: String? = null,
) {
    val isScanningSni: Boolean
        get() = sniScanProgress.isScanning

    val isVpnActive: Boolean
        get() = connectionState.isTunnelBusyOrActive
}
