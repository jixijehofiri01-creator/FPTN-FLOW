package com.example.feature.shared

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.FptnApplication
import com.example.core.performance.AdaptiveBoostTier
import com.example.core.logging.AppLogStore
import com.example.core.ui.localization.tr
import com.example.data.remote.AppUpdateChecker
import com.example.data.remote.AppUpdateStatus
import com.example.data.repository.FptnProjectMeta
import com.example.data.repository.SponsorsFetcher
import com.example.domain.dns.DnsEndpointParser
import com.example.domain.model.AppLanguage
import com.example.domain.model.BackgroundGradientStyle
import com.example.domain.model.BypassMethod
import com.example.domain.model.BypassRegion
import com.example.domain.model.DnsCategory
import com.example.domain.model.DnsMode
import com.example.domain.model.FlagMatrixDirection
import com.example.domain.model.FptnServer
import com.example.domain.model.InstalledAppInfo
import com.example.domain.model.LiveWeatherState
import com.example.domain.model.LogEntry
import com.example.domain.model.ManualOverrideKey
import com.example.domain.model.NetworkPresetTrigger
import com.example.domain.model.SavedDnsEntry
import com.example.domain.model.SlidingTunnelMode
import com.example.domain.model.SniScanProgress
import com.example.domain.model.SniScanResult
import com.example.domain.model.SniSpoofingMode
import com.example.domain.model.SplitTunnelMode
import com.example.domain.model.ThemeMode
import com.example.domain.model.TokenProfile
import com.example.domain.model.TrafficStats
import com.example.domain.model.VpnConnectionState
import com.example.domain.model.VpnSettings
import com.example.domain.model.WavePhysicsPreset
import com.example.domain.model.WeatherEffectMode
import com.example.domain.routing.ProtectedRuApps
import com.example.domain.routing.SmartRfRouter
import com.example.domain.vpn.WatchdogSensitivity
import com.example.vpn.root.AdaptiveRootBoostController
import com.example.vpn.root.RootShellManager
import com.example.vpn.service.FptnVpnService
import com.example.vpn.service.VpnStateHolder
import com.example.vpn.sni.SniAutoScanner
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

class FptnViewModel(application: Application) : AndroidViewModel(application) {

    private val appContainer = (application as FptnApplication).container
    private val repository = appContainer.repository
    private val weatherRepository = appContainer.weatherRepository
    private var sniScanJob: Job? = null
    private var liveReconnectDebounceJob: Job? = null

    @Volatile
    private var lastDisconnectRequestElapsedMs: Long = 0L

    @Volatile
    private var lastDisconnectedAtElapsedMs: Long = 0L

    @Volatile
    private var isAppInForeground: Boolean = true

    private val rootActionInFlight = AtomicBoolean(false)

    private val isPingingFlow = MutableStateFlow(false)
    private val isRootActionBusyFlow = MutableStateFlow(false)
    private val isPingingDnsFlow = MutableStateFlow(false)
    private val dnsPingResultsFlow = MutableStateFlow<Map<String, Int?>>(emptyMap())
    private val sniCatalogFlow = MutableStateFlow(SniAutoScanner.fallbackDomains)
    private val sniScanProgressFlow = MutableStateFlow(SniScanProgress())
    private val sniScanResultsFlow = MutableStateFlow<List<SniScanResult>>(emptyList())
    private val isSavingTokenFlow = MutableStateFlow(false)
    private val installedAppsFlow = MutableStateFlow<List<InstalledAppInfo>>(emptyList())
    private val isLoadingAppsFlow = MutableStateFlow(false)
    private val projectMetaFlow = MutableStateFlow(SponsorsFetcher.fallbackMeta)
    private val isLoadingProjectMetaFlow = MutableStateFlow(false)
    private val liveWeatherFlow = MutableStateFlow(LiveWeatherState())
    private val snackbarFlow = MutableStateFlow<String?>(null)
    private val tokenErrorFlow = MutableStateFlow<String?>(null)
    private val permissionHealthFlow = MutableStateFlow(PermissionHealthState())

    private val appUpdateStatusFlow = MutableStateFlow<AppUpdateStatus>(AppUpdateStatus.Idle)

    // High-frequency telemetry & logs are exposed as dedicated flows so 1Hz traffic ticks and
    // log bursts NEVER recompose the root NavHost, HomeScreen, Flag Backdrop, or Weather Overlay!
    val trafficStatsFlow: StateFlow<TrafficStats> = VpnStateHolder.trafficStats
    val logsFlow: StateFlow<List<LogEntry>> = AppLogStore.logs
    val appUpdateStatus: StateFlow<AppUpdateStatus> = appUpdateStatusFlow

    private val coreDataFlow = combine(
        repository.profilesFlow,
        repository.settingsFlow,
        VpnStateHolder.connectionState,
    ) { profiles, settings, connState ->
        CoreBundle(profiles, settings, connState, TrafficStats())
    }

    private val metaDataFlow = combine(
        RootShellManager.isRootAvailable,
        RootShellManager.areRulesActive,
        RootShellManager.lastProbeDetail,
        isPingingFlow,
        AdaptiveRootBoostController.activeTier,
    ) { rootAvail, rootActive, rootDetail, pinging, boostTier ->
        MetaBundle(rootAvail, rootActive, rootDetail, pinging, emptyList(), boostTier)
    }

    private val scanAndProjectFlow = combine(
        sniCatalogFlow,
        sniScanProgressFlow,
        sniScanResultsFlow,
        projectMetaFlow,
        isLoadingProjectMetaFlow,
    ) { catalog, progress, sniResults, projectMeta, loadingMeta ->
        ScanProjectBundle(catalog, progress, sniResults, projectMeta, loadingMeta)
    }

    private val auxFlow = combine(
        installedAppsFlow,
        isLoadingAppsFlow,
        isSavingTokenFlow,
        snackbarFlow,
        tokenErrorFlow,
    ) { apps, loadingApps, savingToken, snack, tokenErr ->
        AuxBundle(apps, loadingApps, savingToken, snack, tokenErr)
    }

    private val dnsPingBundleFlow = combine(
        isPingingDnsFlow,
        dnsPingResultsFlow,
        permissionHealthFlow,
        liveWeatherFlow,
        isRootActionBusyFlow,
    ) { pingingDns, dnsPings, permHealth, weather, rootActionBusy ->
        DnsWeatherBundle(pingingDns, dnsPings, permHealth, weather, rootActionBusy)
    }

    val uiState: StateFlow<FptnUiState> = combine(
        coreDataFlow,
        metaDataFlow,
        scanAndProjectFlow,
        auxFlow,
        dnsPingBundleFlow,
    ) { core, meta, sp, aux, dnsWeather ->
        val enabledServers = core.profiles
            .filter { it.enabled }
            .flatMap { it.servers }

        val activeServer = if (core.settings.autoSelectBestServer) {
            enabledServers.minByOrNull { it.pingMs ?: Int.MAX_VALUE }
        } else {
            enabledServers.firstOrNull { it.id == core.settings.selectedServerId }
                ?: enabledServers.firstOrNull()
        }

        FptnUiState(
            profiles = core.profiles,
            enabledServers = enabledServers,
            activeServer = activeServer,
            settings = core.settings,
            connectionState = core.connectionState,
            trafficStats = core.trafficStats,
            isRootAvailable = meta.rootAvailable,
            areRootRulesActive = meta.rootRulesActive,
            rootProbeDetail = meta.rootProbeDetail,
            rootBoostTier = meta.rootBoostTier,
            isRootActionBusy = dnsWeather.isRootActionBusy,
            permissionHealth = dnsWeather.permissionHealth,
            isPinging = meta.isPinging,
            isPingingDns = dnsWeather.isPingingDns,
            dnsPingResults = dnsWeather.dnsPingResults,
            sniCatalog = sp.sniCatalog,
            sniScanProgress = sp.sniScanProgress,
            sniScanResults = sp.sniScanResults,
            logs = meta.logs,
            installedApps = aux.installedApps,
            isLoadingApps = aux.isLoadingApps,
            isSavingToken = aux.isSavingToken,
            projectMeta = sp.projectMeta,
            isLoadingProjectMeta = sp.isLoadingProjectMeta,
            liveWeather = dnsWeather.liveWeather,
            snackbarMessage = aux.snackbarMessage,
            tokenErrorMessage = aux.tokenErrorMessage,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = FptnUiState(),
    )

    init {
        viewModelScope.launch(Dispatchers.IO) {
            repository.ensureInstallSeedInitialized()
        }
        viewModelScope.launch {
            var prevState: VpnConnectionState = VpnStateHolder.connectionState.value
            VpnStateHolder.connectionState.collect { nextState ->
                if (nextState is VpnConnectionState.Disconnected && prevState !is VpnConnectionState.Disconnected) {
                    lastDisconnectedAtElapsedMs = SystemClock.elapsedRealtime()
                }
                prevState = nextState
            }
        }
        loadOfficialSniCatalogAsync()
        startAutoPingScheduler()
        startBackgroundHealthMonitor()
        refreshProjectMetaIfNeeded()
        checkForAppUpdates(manual = false)
    }

    private fun loadOfficialSniCatalogAsync(region: BypassRegion = BypassRegion.RUSSIA) {
        viewModelScope.launch(Dispatchers.IO) {
            val catalog = SniAutoScanner.loadCatalogForRegion(getApplication(), region)
            sniCatalogFlow.value = catalog
        }
    }

    private fun startBackgroundHealthMonitor() {
        viewModelScope.launch {
            RootShellManager.rootRevokedEvents.collect {
                handleRootRevokedTransition()
            }
        }
        viewModelScope.launch {
            // Check ONLY non-Root system permissions periodically (Notifications, Battery, VPN consent).
            // NEVER poll `su -c id` on a timer — prevents Magisk superuser toast spam!
            while (isActive) {
                refreshPermissionsAndRootHealth(verifyRoot = false)
                delay(15_000L)
            }
        }
        viewModelScope.launch {
            val s = repository.getSettings()
            if (s.weatherEffectMode == WeatherEffectMode.AUTO_LOCATION && weatherRepository.hasCoarseLocationPermission()) {
                refreshLiveWeather(force = false)
            }
        }
    }

    fun refreshPermissionsAndRootHealth(verifyRoot: Boolean = false) {
        viewModelScope.launch(Dispatchers.IO) {
            val app = getApplication<Application>()
            val settings = repository.getSettings()
            val missingNotif = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                ContextCompat.checkSelfPermission(app, Manifest.permission.POST_NOTIFICATIONS) !=
                    PackageManager.PERMISSION_GRANTED
            } else {
                false
            }
            val missingBattery = runCatching {
                val pm = app.getSystemService(PowerManager::class.java)
                pm != null && !pm.isIgnoringBatteryOptimizations(app.packageName)
            }.getOrDefault(false)
            val missingVpn = if (settings.useRootMode && RootShellManager.isRootAvailable.value) {
                false
            } else {
                runCatching { VpnService.prepare(app) != null }.getOrDefault(false)
            }

            permissionHealthFlow.update {
                it.copy(
                    missingNotifications = missingNotif,
                    missingBatteryUnrestricted = missingBattery,
                    missingVpnConsent = missingVpn,
                )
            }

            // Only invoke `su` when explicitly requested by user action (toggle/button)
            if (verifyRoot) {
                val alive = RootShellManager.verifyRootNow()
                if (alive) {
                    permissionHealthFlow.update { it.copy(rootRevokedWarning = false) }
                } else if (settings.useRootMode) {
                    handleRootRevokedTransition()
                }
            }
        }
    }

    private suspend fun handleRootRevokedTransition() {
        val currentSettings = repository.getSettings()
        if (currentSettings.useRootMode || currentSettings.unlockProtectedRuWithRoot || currentSettings.bypassRegion == BypassRegion.NONE || !currentSettings.smartRfRouting) {
            repository.updateSettings {
                it.copy(
                    useRootMode = false,
                    unlockProtectedRuWithRoot = false,
                    smartRfRouting = true,
                    bypassRegion = BypassRegion.sanitize(it.bypassRegion),
                )
            }
        }
        val activeConn = VpnStateHolder.connectionState.value
        if (activeConn is VpnConnectionState.Connected && activeConn.isRootMode) {
            FptnVpnService.stopDisconnect(getApplication())
        }
        permissionHealthFlow.update { it.copy(rootRevokedWarning = true) }
        snackbarFlow.value = "Root-доступ отключён в Magisk/KernelSU — скрытый туннель деактивирован"
    }

    fun dismissRootRevokedWarning() {
        permissionHealthFlow.update { it.copy(rootRevokedWarning = false) }
    }

    fun onAppForegroundChanged(inForeground: Boolean) {
        isAppInForeground = inForeground
    }

    private fun startAutoPingScheduler() {
        viewModelScope.launch {
            while (isActive) {
                val intervalSec = uiState.value.settings.autoPingIntervalSec
                if (isAppInForeground && intervalSec > 0 && uiState.value.enabledServers.isNotEmpty() && !isPingingFlow.value) {
                    refreshPings(onSonarPulse = null)
                }
                delay((intervalSec.coerceAtLeast(15)) * 1000L)
            }
        }
    }

    fun refreshProjectMetaIfNeeded(force: Boolean = false) {
        if (isLoadingProjectMetaFlow.value) return
        if (!force && projectMetaFlow.value.isLiveLoaded) return
        viewModelScope.launch {
            isLoadingProjectMetaFlow.value = true
            try {
                projectMetaFlow.value = SponsorsFetcher.fetchLive()
            } finally {
                isLoadingProjectMetaFlow.value = false
            }
        }
    }

    fun checkForAppUpdates(manual: Boolean = false) {
        if (appUpdateStatusFlow.value is AppUpdateStatus.Checking) return
        viewModelScope.launch {
            if (manual) {
                appUpdateStatusFlow.value = AppUpdateStatus.Checking
            }
            val result = AppUpdateChecker.checkLatestRelease()
            appUpdateStatusFlow.value = result
            if (manual) {
                val isRu = when (uiState.value.settings.appLanguage) {
                    AppLanguage.RU -> true
                    AppLanguage.EN -> false
                    AppLanguage.SYSTEM -> Locale.getDefault().language.equals("ru", ignoreCase = true)
                }
                when (result) {
                    is AppUpdateStatus.UpToDate -> showMessage(
                        tr(isRu, "Установлена актуальная версия v${result.currentVersion}", "You are on the latest version v${result.currentVersion}"),
                    )
                    is AppUpdateStatus.NoReleasesYet -> showMessage(
                        tr(isRu, "На GitHub пока нет опубликованных релизов", "No GitHub releases published yet"),
                    )
                    is AppUpdateStatus.Error -> showMessage(
                        tr(isRu, "Не удалось проверить обновления", "Could not check for updates"),
                    )
                    else -> Unit
                }
            }
        }
    }

    fun refreshPings(onSonarPulse: (() -> Unit)? = null) {
        val servers = uiState.value.enabledServers
        if (servers.isEmpty() || isPingingFlow.value) return
        viewModelScope.launch {
            isPingingFlow.value = true
            try {
                val results = repository.pingServers(servers)
                val reachableCount = results.values.count { it != null }
                AppLogStore.info("Ping", "Проверка завершена: доступно $reachableCount из ${servers.size} узлов")
                onSonarPulse?.invoke()
            } finally {
                isPingingFlow.value = false
            }
        }
    }

    fun startSniAutoScan(onDone: (String?) -> Unit = {}) {
        if (sniScanProgressFlow.value.isScanning) return
        val region = uiState.value.settings.bypassRegion
        val activeServer = uiState.value.activeServer
        val strategy = uiState.value.settings.effectiveCensorshipStrategyName()

        sniScanJob?.cancel()
        sniScanJob = viewModelScope.launch {
            val catalog = withContext(Dispatchers.IO) {
                SniAutoScanner.loadCatalogForRegion(getApplication(), region)
            }
            sniCatalogFlow.value = catalog
            sniScanProgressFlow.value = SniScanProgress(
                isScanning = true,
                checkedCount = 0,
                totalCount = catalog.size,
                currentDomain = catalog.firstOrNull().orEmpty(),
            )
            try {
                val results = SniAutoScanner.autoScanForWorkingSni(
                    domains = catalog,
                    server = activeServer,
                    censorshipStrategyName = strategy,
                    onProgress = { checked, total, current ->
                        sniScanProgressFlow.value = SniScanProgress(
                            isScanning = true,
                            checkedCount = checked,
                            totalCount = total,
                            currentDomain = current,
                        )
                    },
                )
                sniScanResultsFlow.value = results
                val best = results.firstOrNull { it.reachable }
                if (best != null) {
                    repository.updateSettings { it.copy(customSni = best.domain) }
                    sniScanProgressFlow.value = SniScanProgress(
                        isScanning = false,
                        checkedCount = sniScanProgressFlow.value.checkedCount,
                        totalCount = sniScanProgressFlow.value.totalCount,
                        currentDomain = best.domain,
                        foundDomain = best.domain,
                    )
                    snackbarFlow.value = "Рабочий SNI найден и применён: ${best.domain} (${best.rttMs} мс)"
                    onDone(best.domain)
                } else {
                    sniScanProgressFlow.value = SniScanProgress(isScanning = false)
                    snackbarFlow.value = "Рабочий SNI не найден. Попробуйте метод «Маскировка трафика» или другой сервер."
                    onDone(null)
                }
            } catch (ce: CancellationException) {
                sniScanProgressFlow.value = SniScanProgress(isScanning = false)
                throw ce
            } catch (ex: Exception) {
                sniScanProgressFlow.value = SniScanProgress(isScanning = false)
                snackbarFlow.value = "Ошибка автоподбора SNI: ${ex.message}"
            }
        }
    }

    fun stopSniAutoScan() {
        sniScanJob?.cancel()
        sniScanJob = null
        sniScanProgressFlow.value = SniScanProgress(isScanning = false)
        snackbarFlow.value = "Автоподбор SNI остановлен"
    }

    fun addTokenProfile(name: String, rawToken: String, onSuccess: () -> Unit = {}) {
        if (isSavingTokenFlow.value) return
        viewModelScope.launch {
            isSavingTokenFlow.value = true
            tokenErrorFlow.value = null
            try {
                val res = repository.addTokenProfile(name, rawToken)
                res.onSuccess { created ->
                    onSuccess()
                    snackbarFlow.value = "Профиль «${created.name}» добавлен (${created.servers.size} серв.)"
                    refreshPings()
                }.onFailure { err ->
                    tokenErrorFlow.value = err.message ?: "Не удалось распознать токен."
                }
            } finally {
                isSavingTokenFlow.value = false
            }
        }
    }

    fun clearTokenError() {
        tokenErrorFlow.value = null
    }

    fun dismissConnectionError() {
        if (VpnStateHolder.connectionState.value is VpnConnectionState.Error) {
            VpnStateHolder.updateState(VpnConnectionState.Disconnected)
        }
    }

    fun renameTokenProfile(profileId: Long, newName: String) {
        viewModelScope.launch {
            repository.renameProfile(profileId, newName)
            snackbarFlow.value = "Название профиля обновлено"
        }
    }

    fun setTokenProfileEnabled(profileId: Long, enabled: Boolean) {
        viewModelScope.launch {
            repository.setProfileEnabled(profileId, enabled)
        }
    }

    fun deleteTokenProfile(profileId: Long) {
        viewModelScope.launch {
            repository.deleteProfile(profileId)
            snackbarFlow.value = "Профиль удалён"
        }
    }

    private fun triggerLiveReconnectIfVpnActive(
        isServerSwitch: Boolean = false,
        debounceMs: Long = 650L,
    ): Boolean {
        val currentState = VpnStateHolder.connectionState.value
        if (!currentState.isTunnelBusyOrActive || currentState is VpnConnectionState.Disconnecting) {
            return false
        }
        val targetServerId = (currentState as? VpnConnectionState.Connected)?.server?.id
            ?: (currentState as? VpnConnectionState.Connecting)?.server?.id
            ?: (currentState as? VpnConnectionState.Reconnecting)?.server?.id
            ?: (currentState as? VpnConnectionState.SwitchingServer)?.toServer?.id
            ?: uiState.value.activeServer?.id
            ?: return false

        liveReconnectDebounceJob?.cancel()
        if (debounceMs <= 0L) {
            FptnVpnService.startConnect(getApplication(), targetServerId, isServerSwitch = isServerSwitch)
        } else {
            liveReconnectDebounceJob = viewModelScope.launch {
                delay(debounceMs)
                val latest = VpnStateHolder.connectionState.value
                if (latest.isTunnelBusyOrActive && latest !is VpnConnectionState.Disconnecting) {
                    val freshTargetId = (latest as? VpnConnectionState.Connected)?.server?.id
                        ?: uiState.value.activeServer?.id
                        ?: targetServerId
                    FptnVpnService.startConnect(getApplication(), freshTargetId, isServerSwitch = isServerSwitch)
                }
            }
        }
        return true
    }

    fun selectAutoServer() {
        val wasActive = uiState.value.isVpnActive
        val bestServer = uiState.value.enabledServers.minByOrNull { it.pingMs ?: Int.MAX_VALUE }
        viewModelScope.launch {
            repository.updateSettings {
                it.copy(
                    autoSelectBestServer = true,
                    selectedServerId = null,
                    manualOverrides = it.manualOverrides - ManualOverrideKey.SERVER,
                )
            }
            if (wasActive && bestServer != null) {
                snackbarFlow.value = "Переключение на лучший узел: ${bestServer.cleanName}…"
                FptnVpnService.startConnect(getApplication(), bestServer.id, isServerSwitch = true)
            }
        }
    }

    fun selectServer(server: FptnServer) {
        val wasActive = uiState.value.isVpnActive
        val currentConnectedId = (uiState.value.connectionState as? VpnConnectionState.Connected)?.server?.id
        viewModelScope.launch {
            repository.updateSettings {
                it.copy(
                    autoSelectBestServer = false,
                    selectedServerId = server.id,
                    manualOverrides = it.manualOverrides + ManualOverrideKey.SERVER,
                )
            }
            if (wasActive && currentConnectedId != server.id) {
                snackbarFlow.value = "Переключение на ${server.cleanName}…"
                FptnVpnService.startConnect(getApplication(), server.id, isServerSwitch = true)
            }
        }
    }

    fun updateServerSortOrder(order: com.example.domain.model.ServerSortOrder) {
        viewModelScope.launch {
            repository.updateSettings { it.copy(serverSortOrder = order) }
        }
    }

    fun applyNetworkPreset(presetId: String) {
        viewModelScope.launch {
            var appliedName: String? = null
            var changedServer = false
            repository.updateSettings { current ->
                val preset = current.networkPresets.firstOrNull { it.id == presetId }
                if (preset != null) {
                    appliedName = preset.name
                    val applied = current.applyPreset(
                        preset = preset,
                        respectManualOverrides = !preset.forceOverrideManualLocks,
                    )
                    changedServer = applied.selectedServerId != current.selectedServerId ||
                        applied.autoSelectBestServer != current.autoSelectBestServer
                    val nextOverrides = if (preset.forceOverrideManualLocks) {
                        emptySet()
                    } else {
                        current.manualOverrides
                    }
                    applied.copy(manualOverrides = nextOverrides)
                } else {
                    current
                }
            }
            appliedName?.let { name ->
                val reconnected = triggerLiveReconnectIfVpnActive(isServerSwitch = changedServer, debounceMs = 0L)
                AppLogStore.info("Preset", "Применён пресет «$name»${if (reconnected) " (переподключение)" else ""}")
                snackbarFlow.value = if (reconnected) {
                    "Пресет «$name» применён — переподключение…"
                } else {
                    "Пресет «$name» применён (триггеры сети активны)"
                }
            }
        }
    }

    fun duplicateNetworkPreset(presetId: String) {
        viewModelScope.launch {
            var clonedName: String? = null
            repository.updateSettings { current ->
                val source = current.networkPresets.firstOrNull { it.id == presetId } ?: return@updateSettings current
                val copyName = "${source.name} (копия)"
                clonedName = copyName
                val copyPreset = source.copy(
                    id = "preset_${UUID.randomUUID().toString().take(8)}",
                    name = copyName,
                    trigger = NetworkPresetTrigger.MANUAL,
                    isBuiltIn = false,
                )
                current.copy(networkPresets = current.networkPresets + copyPreset)
            }
            clonedName?.let { name ->
                snackbarFlow.value = "Создана копия пресета: «$name»"
            }
        }
    }

    fun saveNetworkPreset(preset: com.example.domain.model.NetworkPreset) {
        val cleanedName = preset.name.trim()
        if (cleanedName.isEmpty()) {
            snackbarFlow.value = "Введите название пресета"
            return
        }
        val normalized = preset.copy(
            id = preset.id.ifBlank { "preset_${UUID.randomUUID().toString().take(8)}" },
            name = cleanedName,
            customSni = preset.customSni.trim().ifBlank { VpnSettings.DEFAULT_SNI_DOMAIN },
        )
        viewModelScope.launch {
            var editedActivePreset = false
            repository.updateSettings { current ->
                val existingIdx = current.networkPresets.indexOfFirst { it.id == normalized.id }
                val nextList = if (existingIdx >= 0) {
                    current.networkPresets.toMutableList().apply { set(existingIdx, normalized) }
                } else {
                    current.networkPresets + normalized
                }
                val updated = current.copy(networkPresets = nextList)
                editedActivePreset = current.activePresetId == normalized.id
                if (editedActivePreset) updated.applyPreset(normalized) else updated
            }
            val reconnected = if (editedActivePreset) triggerLiveReconnectIfVpnActive() else false
            snackbarFlow.value = if (reconnected) {
                "Пресет «${normalized.name}» сохранён • Применяется в туннеле…"
            } else {
                "Пресет «${normalized.name}» сохранён"
            }
        }
    }

    fun deleteNetworkPreset(presetId: String) {
        viewModelScope.launch {
            var deletedName = ""
            repository.updateSettings { current ->
                val target = current.networkPresets.firstOrNull { it.id == presetId }
                deletedName = target?.name.orEmpty()
                val remaining = current.networkPresets.filterNot { it.id == presetId }
                current.copy(
                    networkPresets = remaining,
                    activePresetId = if (current.activePresetId == presetId) null else current.activePresetId,
                )
            }
            if (deletedName.isNotEmpty()) {
                snackbarFlow.value = "Пресет «$deletedName» удалён"
            }
        }
    }

    fun restoreDefaultNetworkPresets() {
        viewModelScope.launch {
            val catalog = sniCatalogFlow.value
            val defaults = VpnSettings.generateRandomizedDefaultPresets(
                sniPool = catalog,
                seed = System.nanoTime(),
            )
            repository.updateSettings { current ->
                val existingIds = current.networkPresets.map { it.id }.toSet()
                val missing = defaults.filterNot { it.id in existingIds }
                current.copy(networkPresets = missing + current.networkPresets)
            }
            snackbarFlow.value = "Базовые пресеты восстановлены с индивидуальными SNI"
        }
    }

    fun regenerateRandomizedPresetSnis() {
        viewModelScope.launch {
            val catalog = sniCatalogFlow.value
            val fresh = VpnSettings.generateRandomizedDefaultPresets(
                sniPool = catalog,
                seed = System.nanoTime(),
            )
            val byId = fresh.associateBy { it.id }
            repository.updateSettings { current ->
                val updated = current.networkPresets.map { preset ->
                    byId[preset.id]?.let { sample ->
                        preset.copy(
                            customSni = sample.customSni,
                            sniSpoofingMode = sample.sniSpoofingMode,
                        )
                    } ?: preset
                }
                current.copy(networkPresets = updated)
            }
            snackbarFlow.value = "Для базовых пресетов подобраны новые случайные SNI и TLS-профили"
        }
    }

    fun setAutoSwitchPresetsByNetwork(enabled: Boolean) {
        viewModelScope.launch {
            repository.updateSettings {
                it.copy(
                    autoSwitchPresetsByNetwork = enabled,
                    manualOverrides = if (enabled) it.manualOverrides - ManualOverrideKey.PRESET else it.manualOverrides,
                )
            }
        }
    }

    fun startVpnConnection() {
        val currentState = VpnStateHolder.connectionState.value
        val nowMs = SystemClock.elapsedRealtime()
        // Prevent accidental double-tap on Disconnect from triggering an unwanted reconnect!
        if (isConnectBlockedByDisconnectCooldown(
                state = currentState,
                nowElapsedMs = nowMs,
                lastDisconnectRequestElapsedMs = lastDisconnectRequestElapsedMs,
                lastDisconnectedAtElapsedMs = lastDisconnectedAtElapsedMs,
            )
        ) {
            return
        }
        val server = uiState.value.activeServer
        if (server == null) {
            snackbarFlow.value = "Сначала добавьте реальный токен FPTN"
            return
        }
        liveReconnectDebounceJob?.cancel()
        FptnVpnService.startConnect(getApplication(), server.id)
    }

    fun stopVpnConnection() {
        val currentState = VpnStateHolder.connectionState.value
        if (currentState is VpnConnectionState.Disconnecting || currentState is VpnConnectionState.Disconnected) {
            return
        }
        lastDisconnectRequestElapsedMs = SystemClock.elapsedRealtime()
        liveReconnectDebounceJob?.cancel()
        liveReconnectDebounceJob = null
        // Emit Disconnecting synchronously BEFORE starting service teardown so the UI & haptics
        // respond on the very next frame (<16ms) with zero perceived lag!
        VpnStateHolder.updateState(VpnConnectionState.Disconnecting("Отключение туннеля…"))
        FptnVpnService.stopDisconnect(getApplication())
    }

    fun updateBypassMethod(method: BypassMethod) {
        viewModelScope.launch {
            repository.updateSettings {
                it.copy(
                    bypassMethod = method,
                    manualOverrides = it.manualOverrides + ManualOverrideKey.BYPASS_METHOD,
                )
            }
            if (triggerLiveReconnectIfVpnActive()) {
                snackbarFlow.value = "Метод «${method.shortTitle}» применён — переподключение…"
            }
        }
    }

    fun updateSniSpoofingMode(mode: SniSpoofingMode) {
        viewModelScope.launch {
            repository.updateSettings {
                it.copy(
                    bypassMethod = BypassMethod.SNI_REALITY,
                    sniSpoofingMode = mode,
                    manualOverrides = it.manualOverrides + ManualOverrideKey.BYPASS_METHOD,
                )
            }
            if (triggerLiveReconnectIfVpnActive()) {
                snackbarFlow.value = "Профиль ${mode.title} применён — переподключение…"
            }
        }
    }

    fun updateSlidingTunnelMode(mode: SlidingTunnelMode) {
        viewModelScope.launch {
            repository.updateSettings {
                it.copy(
                    slidingTunnelMode = mode,
                    manualOverrides = it.manualOverrides + ManualOverrideKey.SLIDING_TUNNEL,
                )
            }
            if (triggerLiveReconnectIfVpnActive()) {
                snackbarFlow.value = "Туннель «${mode.shortTitle}» применён — переподключение…"
            }
        }
    }

    fun cycleSlidingTunnelQuick() {
        viewModelScope.launch {
            var nextMode = SlidingTunnelMode.ROLLING_TUNNEL
            repository.updateSettings { current ->
                val modes = SlidingTunnelMode.entries
                val nextIndex = (modes.indexOf(current.slidingTunnelMode) + 1) % modes.size
                nextMode = modes[nextIndex]
                current.copy(
                    slidingTunnelMode = nextMode,
                    manualOverrides = current.manualOverrides + ManualOverrideKey.SLIDING_TUNNEL,
                )
            }
            if (triggerLiveReconnectIfVpnActive()) {
                snackbarFlow.value = "Туннель «${nextMode.shortTitle}» — переподключение…"
            }
        }
    }

    fun updateCustomSni(sni: String) {
        val cleaned = sni.trim().ifBlank { VpnSettings.DEFAULT_SNI_DOMAIN }
        viewModelScope.launch {
            repository.updateSettings {
                it.copy(
                    customSni = cleaned,
                    manualOverrides = it.manualOverrides + ManualOverrideKey.CUSTOM_SNI,
                )
            }
            if (triggerLiveReconnectIfVpnActive()) {
                snackbarFlow.value = "SNI $cleaned применён — переподключение…"
            }
        }
    }

    fun resetDefaultSni() {
        viewModelScope.launch {
            repository.updateSettings {
                it.copy(
                    customSni = VpnSettings.DEFAULT_SNI_DOMAIN,
                    manualOverrides = it.manualOverrides - ManualOverrideKey.CUSTOM_SNI,
                )
            }
            val reconnected = triggerLiveReconnectIfVpnActive()
            snackbarFlow.value = if (reconnected) {
                "SNI сброшен на автомат (${VpnSettings.DEFAULT_SNI_DOMAIN}) — переподключение…"
            } else {
                "SNI сброшен на официальный (${VpnSettings.DEFAULT_SNI_DOMAIN})"
            }
        }
    }

    /**
     * Rolls a fresh random SNI distinct from the current one — either from the active region's
     * priority list or from the full official SNI catalog.
     */
    fun pickRandomSni(regionOnly: Boolean = true) {
        viewModelScope.launch {
            val current = repository.getSettings()
            val pool = if (regionOnly && current.bypassRegion.recommendedSnis.isNotEmpty()) {
                current.bypassRegion.recommendedSnis
            } else {
                (current.bypassRegion.recommendedSnis + sniCatalogFlow.value)
            }.map { it.trim().lowercase() }
                .filter { it.contains('.') && !it.contains(' ') }
                .distinct()
            val candidates = pool.filterNot { it.equals(current.customSni, ignoreCase = true) }
                .ifEmpty { pool }
            val chosen = candidates.randomOrNull() ?: VpnSettings.DEFAULT_SNI_DOMAIN
            repository.updateSettings {
                it.copy(
                    customSni = chosen,
                    manualOverrides = it.manualOverrides + ManualOverrideKey.CUSTOM_SNI,
                )
            }
            val reconnected = triggerLiveReconnectIfVpnActive()
            snackbarFlow.value = if (reconnected) {
                "Выбран SNI: $chosen — переподключение…"
            } else {
                "Выбран случайный SNI: $chosen"
            }
        }
    }

    /**
     * One-tap smart connection doctor invoked from the Error diagnostic card:
     * rotates SNI to a fresh regional domain, switches strategy between OBFUSCATION/SNI,
     * selects an alternative healthy server if available, and reconnects immediately.
     */
    fun autoHealConnection() {
        viewModelScope.launch {
            val state = uiState.value
            val current = state.settings
            val failedServerId = (state.connectionState as? VpnConnectionState.Error)?.server?.id
                ?: state.activeServer?.id

            val nextMethod = if (current.bypassMethod == BypassMethod.SNI_REALITY) {
                BypassMethod.TLS_OBFUSCATION
            } else {
                BypassMethod.SNI_REALITY
            }
            val nextSpoof = if (nextMethod == BypassMethod.SNI_REALITY) SniSpoofingMode.SNI else current.sniSpoofingMode
            val regionalPool = (current.bypassRegion.recommendedSnis + sniCatalogFlow.value)
                .map { it.trim().lowercase() }
                .filter { it.contains('.') && !it.equals(current.customSni, ignoreCase = true) }
                .distinct()
            val nextSni = regionalPool.randomOrNull() ?: current.customSni

            val alternativeServer = state.enabledServers
                .filter { it.id != failedServerId }
                .minByOrNull { it.pingMs ?: Int.MAX_VALUE }
                ?: state.activeServer

            repository.updateSettings {
                it.copy(
                    bypassMethod = nextMethod,
                    sniSpoofingMode = nextSpoof,
                    customSni = nextSni,
                    activePresetId = null,
                    autoSelectBestServer = if (alternativeServer != null && alternativeServer.id != failedServerId) false else it.autoSelectBestServer,
                    selectedServerId = alternativeServer?.id ?: it.selectedServerId,
                )
            }
            if (alternativeServer != null) {
                snackbarFlow.value = "Авто-починка: ${nextMethod.shortTitle} • $nextSni → ${alternativeServer.cleanName}"
                FptnVpnService.startConnect(getApplication(), alternativeServer.id, isServerSwitch = false)
            } else {
                snackbarFlow.value = "Авто-починка: обновлён метод и SNI ($nextSni)"
            }
        }
    }

    fun setRootModeEnabled(enabled: Boolean) {
        viewModelScope.launch {
            if (enabled) {
                val alive = RootShellManager.isCachedRootSessionAlive() || RootShellManager.verifyRootNow()
                if (!alive) {
                    repository.updateSettings { it.copy(useRootMode = false, unlockProtectedRuWithRoot = false) }
                    snackbarFlow.value = "Root-права отозваны или недоступны — выдайте доступ в Magisk/KernelSU"
                    return@launch
                }
                permissionHealthFlow.update { it.copy(rootRevokedWarning = false) }
            }
            repository.updateSettings { it.copy(useRootMode = enabled) }
            AppLogStore.info("RootMode", "Безопасный Root-режим ${if (enabled) "включён" else "выключен"}")
            if (triggerLiveReconnectIfVpnActive()) {
                snackbarFlow.value = "Root Stealth ${if (enabled) "включён" else "выключен"} — переподключение…"
            }
        }
    }

    fun setAllowSecondaryVpnChaining(enabled: Boolean) {
        viewModelScope.launch {
            repository.updateSettings { it.copy(allowSecondaryVpnChaining = enabled) }
            AppLogStore.info(
                "RootMode",
                "Совместимость с другим VPN (chaining поверх Stealth Root): ${if (enabled) "включена" else "выключена"}",
            )
            if (uiState.value.settings.useRootMode && triggerLiveReconnectIfVpnActive()) {
                snackbarFlow.value = "Цепочка VPN обновлена — переподключение Root-туннеля…"
            }
        }
    }

    fun updateRootTunInterfaceName(rawName: String) {
        viewModelScope.launch {
            val sanitized = com.example.domain.root.SafeRootRulePlanner.sanitizeTunName(rawName)
            repository.updateSettings { it.copy(rootTunInterfaceName = sanitized) }
            AppLogStore.info("RootMode", "Имя скрытого TUN-интерфейса задано: $sanitized")
            if (uiState.value.settings.useRootMode && triggerLiveReconnectIfVpnActive()) {
                snackbarFlow.value = "Интерфейс $sanitized сохранён — переподключение…"
            } else {
                snackbarFlow.value = "Интерфейс скрытого TUN: $sanitized"
            }
        }
    }

    fun setRootAdaptiveBoostEnabled(enabled: Boolean) {
        viewModelScope.launch {
            repository.updateSettings { it.copy(rootAdaptiveBoostEnabled = enabled) }
            AppLogStore.info(
                "RootBoost",
                "Адаптивное ускорение Root TUN: ${if (enabled) "включено" else "выключено"}",
            )
            if (uiState.value.settings.useRootMode && triggerLiveReconnectIfVpnActive()) {
                snackbarFlow.value = if (enabled) {
                    "Адаптивное ускорение включено — применяется к Root-туннелю…"
                } else {
                    "Адаптивное ускорение выключено — стандартный режим"
                }
            } else {
                snackbarFlow.value = if (enabled) {
                    "Адаптивное ускорение Root TUN включено (уступает ресурсы играм)"
                } else {
                    "Адаптивное ускорение Root TUN выключено"
                }
            }
        }
    }

    /**
     * Explicit user-initiated Root re-verification: ALWAYS runs a fresh `su -c id` check off the
     * Main thread with single-flight deduplication so rapid taps never block navigation or UI.
     */
    fun verifyRootAccessLive() {
        if (!rootActionInFlight.compareAndSet(false, true)) return
        isRootActionBusyFlow.value = true
        viewModelScope.launch {
            try {
                val (granted, detail) = withContext(Dispatchers.IO) {
                    RootShellManager.requestRootAccessExplicitly()
                }
                if (granted) {
                    permissionHealthFlow.update { it.copy(rootRevokedWarning = false) }
                    refreshPermissionsAndRootHealth(verifyRoot = false)
                    snackbarFlow.value = "Root-доступ подтверждён! Раздел и быстрая плитка Root Stealth доступны."
                } else {
                    snackbarFlow.value = detail
                }
            } finally {
                isRootActionBusyFlow.value = false
                rootActionInFlight.set(false)
            }
        }
    }

    @Suppress("UNUSED_PARAMETER")
    fun setUnlockProtectedRuWithRoot(enabled: Boolean) {
        viewModelScope.launch {
            repository.updateSettings {
                it.copy(
                    unlockProtectedRuWithRoot = false,
                    smartRfRouting = true,
                    bypassRegion = BypassRegion.sanitize(it.bypassRegion),
                )
            }
            snackbarFlow.value = "Защита РФ (507 правил и .ru/.рф) закреплена постоянно даже в Root-режиме для сохранности серверов FPTN"
        }
    }

    fun requestRootAccessManually() {
        verifyRootAccessLive()
    }

    fun cleanupRootRulesNow() {
        if (!rootActionInFlight.compareAndSet(false, true)) return
        isRootActionBusyFlow.value = true
        viewModelScope.launch {
            try {
                val ok = withContext(Dispatchers.IO) {
                    AdaptiveRootBoostController.stop()
                    RootShellManager.cleanupRulesSynchronously()
                }
                snackbarFlow.value = if (ok) {
                    "Все изолированные Root-правила очищены"
                } else {
                    "Очистка завершена (нет активного Root-сеанса)"
                }
            } finally {
                isRootActionBusyFlow.value = false
                rootActionInFlight.set(false)
            }
        }
    }

    fun updateSplitTunnelMode(mode: SplitTunnelMode) {
        viewModelScope.launch {
            repository.updateSettings { it.copy(splitTunnelMode = mode) }
            if (triggerLiveReconnectIfVpnActive()) {
                snackbarFlow.value = "Режим приложений «${mode.title}» сохранён • Применяется в туннеле…"
            }
        }
    }

    fun togglePackageSelection(packageName: String) {
        if (ProtectedRuApps.isProtected(getApplication(), packageName)) {
            snackbarFlow.value = "Защищённое РФ-приложение всегда работает напрямую (закреплено постоянно даже с Root для защиты серверов FPTN)"
            return
        }
        viewModelScope.launch {
            repository.updateSettings { current ->
                val next = current.selectedPackages.toMutableSet()
                if (!next.add(packageName)) {
                    next.remove(packageName)
                }
                current.copy(selectedPackages = next)
            }
            if (triggerLiveReconnectIfVpnActive(debounceMs = 900L)) {
                snackbarFlow.value = "Список приложений сохранён • Применяется в туннеле…"
            }
        }
    }

    fun addManualPackage(packageName: String) {
        val cleaned = packageName.trim()
        if (cleaned.isEmpty() || !cleaned.contains('.')) {
            snackbarFlow.value = "Введите корректное имя пакета (например, org.telegram.messenger)"
            return
        }
        if (ProtectedRuApps.isProtected(getApplication(), cleaned)) {
            snackbarFlow.value = "$cleaned входит в постоянную защиту РФ (507 правил) и не может идти через VPN даже с Root"
            return
        }
        viewModelScope.launch {
            repository.updateSettings { current ->
                current.copy(selectedPackages = current.selectedPackages + cleaned)
            }
            val reconnected = triggerLiveReconnectIfVpnActive(debounceMs = 800L)
            snackbarFlow.value = if (reconnected) {
                "Пакет $cleaned добавлен • Применяется в туннеле…"
            } else {
                "Пакет $cleaned добавлен"
            }
        }
    }

    fun updateBypassRegion(region: BypassRegion) {
        viewModelScope.launch {
            if (region == BypassRegion.NONE) {
                snackbarFlow.value = "Отключение защиты РФ заблокировано постоянно (включая Root) для сохранности серверов FPTN"
                return@launch
            }
            repository.updateSettings {
                it.copy(
                    bypassRegion = region,
                    smartRfRouting = true,
                    unlockProtectedRuWithRoot = false,
                    manualOverrides = it.manualOverrides + ManualOverrideKey.ROUTING_REGION,
                )
            }
            loadOfficialSniCatalogAsync(region)
            val reconnected = triggerLiveReconnectIfVpnActive()
            if (region.recommendedSnis.isNotEmpty() || reconnected) {
                snackbarFlow.value = if (reconnected) {
                    "Регион «${region.title}» применён — переподключение…"
                } else {
                    "Регион «${region.title}»: база GeoIP и приоритетные SNI обновлены"
                }
            }
        }
    }

    fun addCustomDirectDomain(raw: String) {
        val domain = SmartRfRouter.normalizeDomainRule(raw)
        if (domain == null) {
            snackbarFlow.value = "Введите корректный домен (например, example.com)"
            return
        }
        if (SmartRfRouter.isTelegramDomain(domain)) {
            snackbarFlow.value = "Домены Telegram всегда идут через VPN для стабильной работы"
            return
        }
        viewModelScope.launch {
            repository.updateSettings { current ->
                current.copy(
                    customDirectDomains = current.customDirectDomains + domain,
                    customVpnDomains = current.customVpnDomains - domain,
                )
            }
            val reconnected = triggerLiveReconnectIfVpnActive(debounceMs = 800L)
            snackbarFlow.value = if (reconnected) {
                "Домен $domain добавлен (напрямую) • Применяется в туннеле…"
            } else {
                "Домен $domain добавлен в «Напрямую без VPN»"
            }
        }
    }

    fun removeCustomDirectDomain(domain: String) {
        viewModelScope.launch {
            repository.updateSettings { current ->
                current.copy(customDirectDomains = current.customDirectDomains - domain)
            }
            if (triggerLiveReconnectIfVpnActive(debounceMs = 800L)) {
                snackbarFlow.value = "Правило для $domain удалено • Применяется в туннеле…"
            }
        }
    }

    fun addCustomVpnDomain(raw: String) {
        val domain = SmartRfRouter.normalizeDomainRule(raw)
        if (domain == null) {
            snackbarFlow.value = "Введите корректный домен (например, chatgpt.com)"
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            val currentSettings = repository.getSettings()
            // Strictly bounded DNS probe so adding a domain NEVER stalls while VPN is active
            val resolvedIps = withTimeoutOrNull(1_800L) {
                SmartRfRouter.resolveDomainsToIpv4s(setOf(domain), maxDomains = 1)
            } ?: emptySet()
            val blockError = SmartRfRouter.checkDomainVpnOverrideError(
                rawDomain = domain,
                bypassRegion = currentSettings.bypassRegion,
                resolvedIpv4s = resolvedIps,
                allowRootOverride = false,
            )
            if (blockError != null) {
                snackbarFlow.value = blockError
                return@launch
            }
            repository.updateSettings { current ->
                current.copy(
                    customVpnDomains = current.customVpnDomains + domain,
                    customDirectDomains = current.customDirectDomains - domain,
                )
            }
            val reconnected = triggerLiveReconnectIfVpnActive(debounceMs = 800L)
            snackbarFlow.value = if (reconnected) {
                "Домен $domain добавлен (через VPN) • Применяется в туннеле…"
            } else {
                "Домен $domain добавлен в «Принудительно через VPN»"
            }
        }
    }

    fun removeCustomVpnDomain(domain: String) {
        viewModelScope.launch {
            repository.updateSettings { current ->
                current.copy(customVpnDomains = current.customVpnDomains - domain)
            }
            if (triggerLiveReconnectIfVpnActive(debounceMs = 800L)) {
                snackbarFlow.value = "Правило для $domain удалено • Применяется в туннеле…"
            }
        }
    }

    fun loadInstalledAppsIfNeeded() {
        if (installedAppsFlow.value.isNotEmpty() || isLoadingAppsFlow.value) return
        viewModelScope.launch {
            isLoadingAppsFlow.value = true
            try {
                installedAppsFlow.value = repository.loadLaunchableApps()
            } finally {
                isLoadingAppsFlow.value = false
            }
        }
    }

    fun selectDnsMode(mode: DnsMode) {
        viewModelScope.launch {
            repository.updateSettings { current ->
                val targetUrl = if (mode == DnsMode.SMART_DNS_DOH && current.customDnsUrl.isBlank()) {
                    current.savedDnsEntries.firstOrNull()?.urlOrHost ?: VpnSettings.DEFAULT_SMART_DNS_URL
                } else {
                    current.customDnsUrl
                }
                val nextOverrides = if (mode == DnsMode.AUTO_VPN) {
                    current.manualOverrides - ManualOverrideKey.DNS
                } else {
                    current.manualOverrides + ManualOverrideKey.DNS
                }
                current.withDnsMode(mode, smartDnsUrl = targetUrl).copy(
                    manualOverrides = nextOverrides,
                )
            }
            val reconnected = triggerLiveReconnectIfVpnActive()
            snackbarFlow.value = if (reconnected) {
                "DNS: ${mode.shortTitle} — переподключение…"
            } else {
                "Режим DNS: ${mode.shortTitle}${if (mode != DnsMode.AUTO_VPN) " (зафиксирован вручную)" else ""}"
            }
        }
    }

    fun cycleDnsModeQuick() {
        val current = uiState.value.settings.dnsMode
        val next = when (current) {
            DnsMode.AUTO_VPN -> DnsMode.SMART_DNS_DOH
            DnsMode.SMART_DNS_DOH -> DnsMode.ADBLOCK_DNS
            DnsMode.ADBLOCK_DNS -> DnsMode.AUTO_VPN
            DnsMode.CUSTOM_IPV4 -> DnsMode.AUTO_VPN
        }
        selectDnsMode(next)
    }

    fun pingSmartDnsEntries(autoPickFastest: Boolean = false) {
        if (isPingingDnsFlow.value) return
        val entries = uiState.value.settings.savedDnsEntries
        if (entries.isEmpty()) return
        viewModelScope.launch {
            isPingingDnsFlow.value = true
            try {
                val results = repository.pingDnsEntries(entries)
                dnsPingResultsFlow.value = results
                val fastest = entries
                    .filter { it.category == DnsCategory.INTERNATIONAL_AI || it.category == DnsCategory.RU_ZONE_AI }
                    .ifEmpty { entries }
                    .mapNotNull { entry -> results[entry.id]?.let { rtt -> entry to rtt } }
                    .minByOrNull { it.second }
                if (autoPickFastest && fastest != null) {
                    selectDnsEntry(fastest.first)
                    snackbarFlow.value = "Выбран самый быстрый Smart DNS: ${fastest.first.title} (${fastest.second} мс)"
                } else if (fastest != null) {
                    snackbarFlow.value = "Проверка DNS завершена. Быстрее всех: ${fastest.first.title} (${fastest.second} мс)"
                } else {
                    snackbarFlow.value = "Не удалось измерить пинг Smart DNS — проверьте интернет"
                }
            } finally {
                isPingingDnsFlow.value = false
            }
        }
    }

    fun updateDnsServers(primary: String, secondary: String) {
        viewModelScope.launch {
            repository.updateSettings {
                it.withDnsMode(DnsMode.CUSTOM_IPV4).copy(
                    dnsPrimary = primary.trim().ifBlank { "1.1.1.1" },
                    dnsSecondary = secondary.trim().ifBlank { "8.8.8.8" },
                    manualOverrides = it.manualOverrides + ManualOverrideKey.DNS,
                )
            }
            val reconnected = triggerLiveReconnectIfVpnActive()
            snackbarFlow.value = if (reconnected) {
                "Ручной IPv4 DNS применён — переподключение…"
            } else {
                "Ручной IPv4 DNS сохранён и зафиксирован"
            }
        }
    }

    fun addCustomDnsEntry(title: String, urlOrHost: String) {
        val trimmedUrl = urlOrHost.trim()
        val parsed = DnsEndpointParser.parse(trimmedUrl)
        if (parsed.isFailure) {
            snackbarFlow.value = parsed.exceptionOrNull()?.message ?: "Некорректный адрес DNS"
            return
        }
        val endpoint = parsed.getOrThrow()
        val canonicalUrl = endpoint.dohUrl ?: endpoint.rawInput
        val displayTitle = title.trim().ifBlank { "DNS ${endpoint.host}" }
        val newEntry = SavedDnsEntry(
            id = "custom_${UUID.randomUUID().toString().take(8)}",
            title = displayTitle,
            urlOrHost = canonicalUrl,
            subtitle = when {
                endpoint.dohUrl != null -> "Пользовательский DoH / DoT внутри VPN"
                else -> "Пользовательский IPv4 DNS внутри VPN"
            },
            fallbackIpv4 = endpoint.ipv4Fallback,
            isBuiltIn = false,
            category = DnsCategory.CUSTOM,
        )
        viewModelScope.launch {
            repository.updateSettings { current ->
                val withoutDup = current.savedDnsEntries.filterNot {
                    it.urlOrHost.equals(canonicalUrl, ignoreCase = true)
                }
                current.withDnsMode(DnsMode.SMART_DNS_DOH, smartDnsUrl = canonicalUrl).copy(
                    savedDnsEntries = withoutDup + newEntry,
                    dnsPrimary = endpoint.ipv4Fallback ?: current.dnsPrimary,
                    manualOverrides = current.manualOverrides + ManualOverrideKey.DNS,
                )
            }
            val reconnected = triggerLiveReconnectIfVpnActive()
            snackbarFlow.value = if (reconnected) {
                "Smart DNS «$displayTitle» активирован — переподключение…"
            } else {
                "DNS «$displayTitle» добавлен и зафиксирован (работает только внутри VPN)"
            }
        }
    }

    fun selectDnsEntry(entry: SavedDnsEntry) {
        viewModelScope.launch {
            repository.updateSettings { current ->
                current.withDnsMode(DnsMode.SMART_DNS_DOH, smartDnsUrl = entry.urlOrHost).copy(
                    dnsPrimary = entry.fallbackIpv4 ?: current.dnsPrimary,
                    manualOverrides = current.manualOverrides + ManualOverrideKey.DNS,
                )
            }
            val reconnected = triggerLiveReconnectIfVpnActive()
            snackbarFlow.value = if (reconnected) {
                "Smart DNS «${entry.title}» применён — переподключение…"
            } else {
                "Выбран и зафиксирован Smart DNS: ${entry.title}"
            }
        }
    }

    fun deleteDnsEntry(entryId: String) {
        viewModelScope.launch {
            var deletedTitle = ""
            repository.updateSettings { current ->
                val target = current.savedDnsEntries.firstOrNull { it.id == entryId }
                deletedTitle = target?.title.orEmpty()
                val remaining = current.savedDnsEntries.filterNot { it.id == entryId }
                val wasActive = target != null && current.customDnsUrl.equals(target.urlOrHost, ignoreCase = true)
                val nextMode = if (wasActive && remaining.isEmpty()) DnsMode.AUTO_VPN else current.dnsMode
                current.withDnsMode(
                    nextMode,
                    smartDnsUrl = if (wasActive) remaining.firstOrNull()?.urlOrHost.orEmpty() else current.customDnsUrl,
                ).copy(savedDnsEntries = remaining)
            }
            if (deletedTitle.isNotEmpty()) {
                snackbarFlow.value = "DNS «$deletedTitle» удалён из списка"
            }
        }
    }

    fun restoreDefaultDnsEntries() {
        viewModelScope.launch {
            repository.updateSettings { current ->
                val existingUrls = current.savedDnsEntries.map { it.urlOrHost.lowercase() }.toSet()
                val missingDefaults = VpnSettings.DEFAULT_DNS_ENTRIES.filterNot {
                    it.urlOrHost.lowercase() in existingUrls
                }
                current.copy(savedDnsEntries = missingDefaults + current.savedDnsEntries)
            }
            snackbarFlow.value = "Полная база Smart DNS (17 серверов) восстановлена"
        }
    }

    fun setCustomDnsUrlEnabled(enabled: Boolean) {
        selectDnsMode(if (enabled) DnsMode.SMART_DNS_DOH else DnsMode.AUTO_VPN)
    }

    fun setBlockAdsDns(enabled: Boolean) {
        selectDnsMode(if (enabled) DnsMode.ADBLOCK_DNS else DnsMode.AUTO_VPN)
    }

    fun setAllowIpv6(enabled: Boolean) {
        viewModelScope.launch {
            repository.updateSettings { it.copy(allowIpv6 = enabled) }
            if (triggerLiveReconnectIfVpnActive()) {
                snackbarFlow.value = "IPv6 ${if (enabled) "включён" else "отключён"} • Применяется в туннеле…"
            }
        }
    }

    fun setSmartRfRouting(enabled: Boolean) {
        viewModelScope.launch {
            if (!enabled) {
                snackbarFlow.value = "Умный обход РФ закреплён постоянно (включая Root) для защиты серверов FPTN"
                return@launch
            }
            repository.updateSettings {
                it.copy(
                    smartRfRouting = true,
                    unlockProtectedRuWithRoot = false,
                    bypassRegion = BypassRegion.sanitize(it.bypassRegion),
                    manualOverrides = it.manualOverrides + ManualOverrideKey.ROUTING_REGION,
                )
            }
            if (triggerLiveReconnectIfVpnActive()) {
                snackbarFlow.value = "Маршрутизация обновлена — переподключение…"
            }
        }
    }

    fun updateTunMtu(mtu: Int) {
        val clamped = mtu.coerceIn(1280, 1500)
        viewModelScope.launch {
            repository.updateSettings {
                it.copy(
                    tunMtu = clamped,
                    manualOverrides = it.manualOverrides + ManualOverrideKey.TUN_MTU,
                )
            }
            if (triggerLiveReconnectIfVpnActive()) {
                snackbarFlow.value = "MTU $clamped применён — переподключение…"
            }
        }
    }

    fun updateAutoPingInterval(seconds: Int) {
        viewModelScope.launch { repository.updateSettings { it.copy(autoPingIntervalSec = seconds) } }
    }

    fun setShowTrafficNotification(enabled: Boolean) {
        viewModelScope.launch { repository.updateSettings { it.copy(showTrafficInNotification = enabled) } }
    }

    fun setEnableHaptics(enabled: Boolean) {
        viewModelScope.launch { repository.updateSettings { it.copy(enableHaptics = enabled) } }
    }

    fun setEnableWaterWaveEffect(enabled: Boolean) {
        viewModelScope.launch { repository.updateSettings { it.copy(enableWaterWaveEffect = enabled) } }
    }

    fun updateWaveSpeed(speed: Float) {
        viewModelScope.launch { repository.updateSettings { it.copy(waveSpeed = speed.coerceIn(0.35f, 1.8f)) } }
    }

    fun updateWaveStrength(strength: Float) {
        viewModelScope.launch { repository.updateSettings { it.copy(waveStrength = strength.coerceIn(0.25f, 1.8f)) } }
    }

    fun updateWaveSmoothness(smoothness: Float) {
        viewModelScope.launch { repository.updateSettings { it.copy(waveSmoothness = smoothness.coerceIn(0.25f, 1.6f)) } }
    }

    fun updateWaveElasticity(elasticity: Float) {
        viewModelScope.launch { repository.updateSettings { it.copy(waveElasticity = elasticity.coerceIn(0.15f, 1.5f)) } }
    }

    fun updateWaveTintStrength(tint: Float) {
        viewModelScope.launch { repository.updateSettings { it.copy(waveTintStrength = tint.coerceIn(0.05f, 1.0f)) } }
    }

    fun updateWaveDispersion(dispersion: Float) {
        viewModelScope.launch { repository.updateSettings { it.copy(waveDispersion = dispersion.coerceIn(0.0f, 1.8f)) } }
    }

    fun updateWaveRingWidth(ringWidth: Float) {
        viewModelScope.launch { repository.updateSettings { it.copy(waveRingWidth = ringWidth.coerceIn(0.55f, 1.65f)) } }
    }

    fun applyWavePreset(preset: WavePhysicsPreset) {
        viewModelScope.launch {
            val c = preset.config
            repository.updateSettings {
                it.copy(
                    waveSpeed = c.speed,
                    waveStrength = c.strength,
                    waveSmoothness = c.smoothness,
                    waveElasticity = c.elasticity,
                    waveTintStrength = c.tintStrength,
                    waveDispersion = c.dispersion,
                    waveRingWidth = c.ringWidth,
                )
            }
            snackbarFlow.value = "Пресет волны «${preset.title}» применён"
        }
    }

    fun resetWaveDefaults() {
        viewModelScope.launch {
            repository.updateSettings {
                it.copy(
                    waveSpeed = 0.72f,
                    waveStrength = 0.85f,
                    waveSmoothness = 0.75f,
                    waveElasticity = 0.65f,
                    waveTintStrength = 0.30f,
                    waveDispersion = 0.85f,
                    waveRingWidth = 1.0f,
                )
            }
            snackbarFlow.value = "Параметры стеклянной волны сброшены по умолчанию"
        }
    }

    fun setEnableFlagBackdrop(enabled: Boolean) {
        viewModelScope.launch { repository.updateSettings { it.copy(enableFlagBackdrop = enabled) } }
    }

    fun setEnableFlagMatrixTransition(enabled: Boolean) {
        viewModelScope.launch { repository.updateSettings { it.copy(enableFlagMatrixTransition = enabled) } }
    }

    fun updateFlagMatrixDirection(direction: FlagMatrixDirection) {
        viewModelScope.launch {
            repository.updateSettings {
                it.copy(
                    flagMatrixDirection = direction,
                    flagMatrixAngleDegrees = direction.toClockDegrees(),
                    flagMatrixRandomAngle = direction == FlagMatrixDirection.RANDOM_EACH_TIME,
                )
            }
        }
    }

    fun updateFlagTiltDegrees(degrees: Float) {
        val norm = com.example.data.local.SettingsDataStore.normalizeAngle360(degrees)
        viewModelScope.launch { repository.updateSettings { it.copy(flagTiltDegrees = norm) } }
    }

    fun updateFlagMatrixAngleDegrees(degrees: Float) {
        val norm = com.example.data.local.SettingsDataStore.normalizeAngle360(degrees)
        viewModelScope.launch {
            repository.updateSettings {
                it.copy(
                    flagMatrixAngleDegrees = norm,
                    flagMatrixRandomAngle = false,
                )
            }
        }
    }

    fun setFlagMatrixRandomAngle(enabled: Boolean) {
        viewModelScope.launch { repository.updateSettings { it.copy(flagMatrixRandomAngle = enabled) } }
    }

    fun setFlagAngleSnapToTicks(enabled: Boolean) {
        viewModelScope.launch { repository.updateSettings { it.copy(flagAngleSnapToTicks = enabled) } }
    }

    fun updateFlagMatrixSpeed(speed: Float) {
        viewModelScope.launch { repository.updateSettings { it.copy(flagMatrixSpeed = speed.coerceIn(0.40f, 2.20f)) } }
    }

    fun updateFlagMatrixZoom(zoom: Float) {
        viewModelScope.launch { repository.updateSettings { it.copy(flagMatrixZoom = zoom.coerceIn(0.65f, 1.45f)) } }
    }

    fun setFlagMatrixHighlightServers(enabled: Boolean) {
        viewModelScope.launch { repository.updateSettings { it.copy(flagMatrixHighlightServers = enabled) } }
    }

    fun updateFlagBackdropScale(scale: Float) {
        viewModelScope.launch { repository.updateSettings { it.copy(flagBackdropScale = scale.coerceIn(0.85f, 1.55f)) } }
    }

    fun updateFlagBackdropDim(dim: Float) {
        viewModelScope.launch { repository.updateSettings { it.copy(flagBackdropDim = dim.coerceIn(0.10f, 0.78f)) } }
    }

    fun setFlagShowCoatOfArms(enabled: Boolean) {
        viewModelScope.launch { repository.updateSettings { it.copy(flagShowCoatOfArms = enabled) } }
    }

    fun updateFlagPhysicsIntensity(intensity: Float) {
        viewModelScope.launch { repository.updateSettings { it.copy(flagPhysicsIntensity = intensity.coerceIn(0.0f, 1.6f)) } }
    }

    fun updateBackgroundGradientStyle(style: BackgroundGradientStyle) {
        viewModelScope.launch { repository.updateSettings { it.copy(backgroundGradientStyle = style) } }
    }

    fun setEnableSolarAtmosphere(enabled: Boolean) {
        viewModelScope.launch { repository.updateSettings { it.copy(enableSolarAtmosphere = enabled) } }
    }

    fun updateSolarAtmosphereIntensity(intensity: Float) {
        viewModelScope.launch {
            repository.updateSettings {
                it.copy(solarAtmosphereIntensity = intensity.coerceIn(0.25f, 2.0f))
            }
        }
    }

    fun setWeatherEffectMode(mode: WeatherEffectMode) {
        viewModelScope.launch {
            repository.updateSettings { it.copy(weatherEffectMode = mode) }
            if (mode == WeatherEffectMode.AUTO_LOCATION) {
                refreshLiveWeather(force = true)
            }
        }
    }

    fun setEnableWeatherHaptics(enabled: Boolean) {
        viewModelScope.launch { repository.updateSettings { it.copy(enableWeatherHaptics = enabled) } }
    }

    fun updateWeatherIntensity(intensity: Float) {
        viewModelScope.launch { repository.updateSettings { it.copy(weatherIntensity = intensity.coerceIn(0.25f, 1.5f)) } }
    }

    fun updateWeatherWindStrength(windStrength: Float) {
        viewModelScope.launch { repository.updateSettings { it.copy(weatherWindStrength = windStrength.coerceIn(0.0f, 1.5f)) } }
    }

    fun updateWeatherFogDepth(fogDepth: Float) {
        viewModelScope.launch { repository.updateSettings { it.copy(weatherFogDepth = fogDepth.coerceIn(0.25f, 1.5f)) } }
    }

    fun refreshLiveWeather(force: Boolean = true) {
        viewModelScope.launch {
            val res = weatherRepository.fetchLiveWeather(forceRefresh = force)
            res.onSuccess { live ->
                liveWeatherFlow.value = live
                if (force) {
                    val tempStr = live.temperatureC?.let { " • %.1f°C".format(it) }.orEmpty()
                    snackbarFlow.value = "Погода (${live.locationLabel}): ${live.condition.shortTitle}$tempStr"
                }
            }.onFailure { err ->
                if (force) {
                    snackbarFlow.value = err.message ?: "Не удалось получить погоду"
                }
            }
        }
    }

    fun setDynamicColor(enabled: Boolean) {
        viewModelScope.launch { repository.updateSettings { it.copy(dynamicColor = enabled) } }
    }

    fun updateThemeMode(mode: ThemeMode) {
        viewModelScope.launch { repository.updateSettings { it.copy(themeMode = mode) } }
    }

    fun updateAppLanguage(language: com.example.domain.model.AppLanguage) {
        viewModelScope.launch { repository.updateSettings { it.copy(appLanguage = language) } }
    }

    fun setReconnectOnFailure(enabled: Boolean) {
        viewModelScope.launch {
            repository.updateSettings {
                it.copy(
                    reconnectOnFailure = enabled,
                    manualOverrides = it.manualOverrides + ManualOverrideKey.RECONNECT,
                )
            }
        }
    }

    fun setReconnectOnNetworkChange(enabled: Boolean) {
        viewModelScope.launch {
            repository.updateSettings {
                it.copy(
                    reconnectOnNetworkChange = enabled,
                    manualOverrides = it.manualOverrides + ManualOverrideKey.RECONNECT,
                )
            }
        }
    }

    fun setAutoRotateSniOnFail(enabled: Boolean) {
        viewModelScope.launch { repository.updateSettings { it.copy(autoRotateSniOnFail = enabled) } }
    }

    fun setAutoFailoverServer(enabled: Boolean) {
        viewModelScope.launch { repository.updateSettings { it.copy(autoFailoverServer = enabled) } }
    }

    fun updateMaxReconnectAttempts(attempts: Int) {
        viewModelScope.launch {
            repository.updateSettings {
                it.copy(
                    maxReconnectAttempts = attempts.coerceIn(3, 50),
                    manualOverrides = it.manualOverrides + ManualOverrideKey.RECONNECT,
                )
            }
        }
    }

    fun updateReconnectDelaySec(seconds: Int) {
        viewModelScope.launch {
            repository.updateSettings {
                it.copy(
                    reconnectDelaySec = seconds.coerceIn(1, 10),
                    manualOverrides = it.manualOverrides + ManualOverrideKey.RECONNECT,
                )
            }
        }
    }

    fun updateHandshakeTimeoutSec(seconds: Int) {
        viewModelScope.launch {
            repository.updateSettings {
                it.copy(
                    handshakeTimeoutSec = seconds.coerceIn(4, 20),
                    manualOverrides = it.manualOverrides + ManualOverrideKey.RECONNECT,
                )
            }
        }
    }

    fun updateWsOpenTimeoutSec(seconds: Int) {
        viewModelScope.launch {
            repository.updateSettings {
                it.copy(
                    wsOpenTimeoutSec = seconds.coerceIn(5, 30),
                    manualOverrides = it.manualOverrides + ManualOverrideKey.RECONNECT,
                )
            }
        }
    }

    fun updateKeepaliveIntervalSec(seconds: Int) {
        viewModelScope.launch {
            repository.updateSettings {
                it.copy(
                    keepaliveIntervalSec = seconds.coerceIn(0, 60),
                    manualOverrides = it.manualOverrides + ManualOverrideKey.RECONNECT,
                )
            }
        }
    }

    fun updateNetworkSwitchDebounceSec(seconds: Int) {
        viewModelScope.launch {
            repository.updateSettings {
                it.copy(
                    networkSwitchDebounceSec = seconds.coerceIn(1, 10),
                    manualOverrides = it.manualOverrides + ManualOverrideKey.RECONNECT,
                )
            }
        }
    }

    fun setStallWatchdogEnabled(enabled: Boolean) {
        viewModelScope.launch {
            repository.updateSettings {
                it.copy(
                    enableStallWatchdog = enabled,
                    manualOverrides = it.manualOverrides + ManualOverrideKey.WATCHDOG,
                )
            }
        }
    }

    fun updateWatchdogSensitivity(sensitivity: WatchdogSensitivity) {
        viewModelScope.launch {
            val (defaultProbe, defaultStall, defaultCooldown) = when (sensitivity) {
                WatchdogSensitivity.GENTLE -> Triple(10, 24, 35)
                WatchdogSensitivity.BALANCED -> Triple(7, 16, 30)
                WatchdogSensitivity.AGGRESSIVE -> Triple(4, 10, 20)
            }
            repository.updateSettings {
                it.copy(
                    watchdogSensitivity = sensitivity,
                    watchdogProbeSec = defaultProbe,
                    watchdogStallSec = defaultStall,
                    watchdogCooldownSec = defaultCooldown,
                    manualOverrides = it.manualOverrides + ManualOverrideKey.WATCHDOG,
                )
            }
        }
    }

    fun updateWatchdogProbeSec(seconds: Int) {
        viewModelScope.launch {
            repository.updateSettings { current ->
                val probe = seconds.coerceIn(2, 25)
                val stall = current.watchdogStallSec.coerceAtLeast(probe + 2)
                current.copy(
                    watchdogProbeSec = probe,
                    watchdogStallSec = stall,
                    manualOverrides = current.manualOverrides + ManualOverrideKey.WATCHDOG,
                )
            }
        }
    }

    fun updateWatchdogStallSec(seconds: Int) {
        viewModelScope.launch {
            repository.updateSettings { current ->
                val stall = seconds.coerceIn(4, 55)
                val probe = current.watchdogProbeSec.coerceAtMost((stall - 2).coerceAtLeast(2))
                current.copy(
                    watchdogProbeSec = probe,
                    watchdogStallSec = stall,
                    manualOverrides = current.manualOverrides + ManualOverrideKey.WATCHDOG,
                )
            }
        }
    }

    fun updateWatchdogCooldownSec(seconds: Int) {
        viewModelScope.launch {
            repository.updateSettings {
                it.copy(
                    watchdogCooldownSec = seconds.coerceIn(10, 90),
                    manualOverrides = it.manualOverrides + ManualOverrideKey.WATCHDOG,
                )
            }
        }
    }

    /**
     * Restores a single manually forced setting back to Automatic / active-preset behavior.
     */
    fun resetManualOverride(key: ManualOverrideKey) {
        viewModelScope.launch {
            repository.updateSettings { cur ->
                val nextOverrides = cur.manualOverrides - key
                val activePreset = cur.networkPresets.firstOrNull { it.id == cur.activePresetId }
                when (key) {
                    ManualOverrideKey.SERVER -> cur.copy(
                        autoSelectBestServer = true,
                        selectedServerId = null,
                        manualOverrides = nextOverrides,
                    )
                    ManualOverrideKey.PRESET -> cur.copy(
                        activePresetId = null,
                        manualOverrides = nextOverrides,
                    )
                    ManualOverrideKey.DNS -> cur.withDnsMode(DnsMode.AUTO_VPN).copy(
                        manualOverrides = nextOverrides,
                    )
                    ManualOverrideKey.BYPASS_METHOD -> cur.copy(
                        bypassMethod = activePreset?.bypassMethod ?: BypassMethod.SNI_REALITY,
                        sniSpoofingMode = activePreset?.sniSpoofingMode ?: SniSpoofingMode.SNI,
                        manualOverrides = nextOverrides,
                    )
                    ManualOverrideKey.CUSTOM_SNI -> cur.copy(
                        customSni = activePreset?.customSni ?: VpnSettings.DEFAULT_SNI_DOMAIN,
                        manualOverrides = nextOverrides,
                    )
                    ManualOverrideKey.SLIDING_TUNNEL -> cur.copy(
                        slidingTunnelMode = activePreset?.slidingTunnelMode ?: SlidingTunnelMode.ROLLING_TUNNEL,
                        manualOverrides = nextOverrides,
                    )
                    ManualOverrideKey.TUN_MTU -> cur.copy(
                        tunMtu = activePreset?.tunMtu ?: 1400,
                        manualOverrides = nextOverrides,
                    )
                    ManualOverrideKey.WATCHDOG -> cur.copy(
                        enableStallWatchdog = activePreset?.enableStallWatchdog ?: true,
                        watchdogSensitivity = activePreset?.watchdogSensitivity ?: WatchdogSensitivity.GENTLE,
                        watchdogProbeSec = activePreset?.watchdogProbeSec ?: 10,
                        watchdogStallSec = activePreset?.watchdogStallSec ?: 24,
                        watchdogCooldownSec = activePreset?.watchdogCooldownSec ?: 35,
                        manualOverrides = nextOverrides,
                    )
                    ManualOverrideKey.RECONNECT -> cur.copy(
                        reconnectOnFailure = activePreset?.reconnectOnFailure ?: true,
                        reconnectOnNetworkChange = activePreset?.reconnectOnNetworkChange ?: true,
                        maxReconnectAttempts = activePreset?.maxReconnectAttempts ?: 15,
                        reconnectDelaySec = activePreset?.reconnectDelaySec ?: 2,
                        handshakeTimeoutSec = activePreset?.handshakeTimeoutSec ?: 8,
                        wsOpenTimeoutSec = activePreset?.wsOpenTimeoutSec ?: 15,
                        keepaliveIntervalSec = activePreset?.keepaliveIntervalSecOverride ?: 18,
                        networkSwitchDebounceSec = activePreset?.networkSwitchDebounceSecOverride ?: 3,
                        manualOverrides = nextOverrides,
                    )
                    ManualOverrideKey.ROUTING_REGION -> cur.copy(
                        bypassRegion = activePreset?.bypassRegionOverride ?: BypassRegion.RUSSIA,
                        smartRfRouting = activePreset?.smartRfRoutingOverride ?: true,
                        manualOverrides = nextOverrides,
                    )
                }
            }
            val needsReconnect = key in setOf(
                ManualOverrideKey.SERVER,
                ManualOverrideKey.PRESET,
                ManualOverrideKey.DNS,
                ManualOverrideKey.BYPASS_METHOD,
                ManualOverrideKey.CUSTOM_SNI,
                ManualOverrideKey.SLIDING_TUNNEL,
                ManualOverrideKey.TUN_MTU,
                ManualOverrideKey.ROUTING_REGION,
            )
            val reconnected = if (needsReconnect) {
                triggerLiveReconnectIfVpnActive(isServerSwitch = key == ManualOverrideKey.SERVER)
            } else {
                false
            }
            snackbarFlow.value = if (reconnected) {
                "«${key.title}» возвращено на автомат — переподключение…"
            } else {
                "«${key.title}» возвращено на автомат"
            }
        }
    }

    /**
     * Resets ALL manually forced overrides back to automatic mode in a single tap.
     */
    fun resetAllManualOverrides() {
        viewModelScope.launch {
            repository.updateSettings { cur ->
                cur.withDnsMode(DnsMode.AUTO_VPN).copy(
                    autoSelectBestServer = true,
                    selectedServerId = null,
                    activePresetId = null,
                    bypassMethod = BypassMethod.SNI_REALITY,
                    sniSpoofingMode = SniSpoofingMode.SNI,
                    customSni = VpnSettings.DEFAULT_SNI_DOMAIN,
                    slidingTunnelMode = SlidingTunnelMode.ROLLING_TUNNEL,
                    tunMtu = 1400,
                    enableStallWatchdog = true,
                    watchdogSensitivity = WatchdogSensitivity.GENTLE,
                    watchdogProbeSec = 10,
                    watchdogStallSec = 24,
                    watchdogCooldownSec = 35,
                    reconnectOnFailure = true,
                    reconnectOnNetworkChange = true,
                    maxReconnectAttempts = 15,
                    reconnectDelaySec = 2,
                    handshakeTimeoutSec = 8,
                    wsOpenTimeoutSec = 15,
                    keepaliveIntervalSec = 18,
                    networkSwitchDebounceSec = 3,
                    bypassRegion = BypassRegion.RUSSIA,
                    smartRfRouting = true,
                    manualOverrides = emptySet(),
                )
            }
            val reconnected = triggerLiveReconnectIfVpnActive()
            snackbarFlow.value = if (reconnected) {
                "Все принудительные настройки возвращены на автомат — переподключение…"
            } else {
                "Все принудительные настройки возвращены на автомат"
            }
        }
    }

    fun addHomeQuickTile(tileId: com.example.domain.model.HomeQuickTileId) {
        viewModelScope.launch {
            repository.updateSettings { current ->
                if (tileId in current.homeQuickTiles) current
                else current.copy(homeQuickTiles = current.homeQuickTiles + tileId)
            }
        }
    }

    fun removeHomeQuickTile(tileId: com.example.domain.model.HomeQuickTileId) {
        viewModelScope.launch {
            repository.updateSettings { current ->
                current.copy(homeQuickTiles = current.homeQuickTiles.filterNot { it == tileId })
            }
        }
    }

    fun moveHomeQuickTile(tileId: com.example.domain.model.HomeQuickTileId, delta: Int) {
        viewModelScope.launch {
            repository.updateSettings { current ->
                val list = current.homeQuickTiles.toMutableList()
                val idx = list.indexOf(tileId)
                val target = (idx + delta).coerceIn(0, list.lastIndex)
                if (idx >= 0 && target != idx) {
                    val item = list.removeAt(idx)
                    list.add(target, item)
                }
                current.copy(homeQuickTiles = list)
            }
        }
    }

    fun resetHomeQuickTiles() {
        viewModelScope.launch {
            repository.updateSettings { it.copy(homeQuickTiles = VpnSettings.DEFAULT_HOME_QUICK_TILES) }
            snackbarFlow.value = "Быстрые модули сброшены на стандартный набор"
        }
    }

    fun clearLogs() {
        AppLogStore.clear()
        snackbarFlow.value = "Журнал очищен"
    }

    fun exportLogsText(): String = AppLogStore.exportAsText()

    fun exportBackup(onReady: (String) -> Unit) {
        viewModelScope.launch {
            val json = repository.exportBackupJson()
            onReady(json)
        }
    }

    fun exportOfficialCompatibleBackup(onReady: (String) -> Unit) {
        viewModelScope.launch {
            val json = repository.exportOfficialCompatibleBackupJson()
            onReady(json)
        }
    }

    fun exportRawTokensText(onReady: (String) -> Unit) {
        viewModelScope.launch {
            val text = uiState.value.profiles
                .filter { it.rawToken.isNotBlank() }
                .joinToString("\n\n") { "# ${it.name}\n${it.rawToken.trim()}" }
            onReady(text)
        }
    }

    fun importBackup(jsonText: String) {
        viewModelScope.launch {
            val res = repository.importBackupJson(jsonText)
            res.onSuccess { (count, format) ->
                snackbarFlow.value = "${format.title}: импортировано профилей — $count"
            }.onFailure { err ->
                snackbarFlow.value = "Ошибка импорта бэкапа: ${err.message}"
            }
        }
    }

    fun safeShutdownBeforeExit() {
        val shouldCleanupRoot = RootShellManager.areRulesActive.value || uiState.value.isRootAvailable
        FptnVpnService.stopDisconnect(getApplication())
        if (shouldCleanupRoot) {
            Thread({
                RootShellManager.cleanupRulesSynchronously()
            }, "FptnExitRootCleanup").apply {
                isDaemon = true
                start()
            }
        }
    }

    fun showMessage(msg: String) {
        snackbarFlow.value = msg
    }

    fun consumeSnackbar() {
        snackbarFlow.value = null
    }

    private data class CoreBundle(
        val profiles: List<TokenProfile>,
        val settings: VpnSettings,
        val connectionState: VpnConnectionState,
        val trafficStats: TrafficStats,
    )

    private data class MetaBundle(
        val rootAvailable: Boolean,
        val rootRulesActive: Boolean,
        val rootProbeDetail: String,
        val isPinging: Boolean,
        val logs: List<LogEntry>,
        val rootBoostTier: AdaptiveBoostTier,
    )

    private data class ScanProjectBundle(
        val sniCatalog: List<String>,
        val sniScanProgress: SniScanProgress,
        val sniScanResults: List<SniScanResult>,
        val projectMeta: FptnProjectMeta,
        val isLoadingProjectMeta: Boolean,
    )

    private data class AuxBundle(
        val installedApps: List<InstalledAppInfo>,
        val isLoadingApps: Boolean,
        val isSavingToken: Boolean,
        val snackbarMessage: String?,
        val tokenErrorMessage: String?,
    )

    private data class DnsWeatherBundle(
        val isPingingDns: Boolean,
        val dnsPingResults: Map<String, Int?>,
        val permissionHealth: PermissionHealthState,
        val liveWeather: LiveWeatherState,
        val isRootActionBusy: Boolean,
    )

    companion object {
        const val DISCONNECT_RECONNECT_COOLDOWN_MS: Long = 950L

        /**
         * Guards against accidental double-taps on Disconnect immediately launching a fresh VPN
         * connection right as teardown finishes.
         */
        fun isConnectBlockedByDisconnectCooldown(
            state: VpnConnectionState,
            nowElapsedMs: Long,
            lastDisconnectRequestElapsedMs: Long,
            lastDisconnectedAtElapsedMs: Long,
            cooldownMs: Long = DISCONNECT_RECONNECT_COOLDOWN_MS,
        ): Boolean {
            if (state is VpnConnectionState.Disconnecting) return true
            if (lastDisconnectRequestElapsedMs > 0L && (nowElapsedMs - lastDisconnectRequestElapsedMs) in 0L until cooldownMs) {
                return true
            }
            if (lastDisconnectedAtElapsedMs > 0L && (nowElapsedMs - lastDisconnectedAtElapsedMs) in 0L until cooldownMs) {
                return true
            }
            return false
        }
    }
}
