package com.example.vpn.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.IpPrefix
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.PowerManager
import android.os.Process
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.example.FptnApplication
import com.example.MainActivity
import com.example.R
import com.example.core.logging.AppLogStore
import com.example.domain.dns.DnsEndpointParser
import com.example.domain.model.AppLanguage
import com.example.domain.model.BypassMethod
import com.example.domain.model.BypassRegion
import com.example.domain.model.DnsMode
import com.example.domain.model.FptnServer
import com.example.domain.model.ManualOverrideKey
import com.example.domain.model.NetworkPresetTrigger
import com.example.domain.model.SniSpoofingMode
import com.example.domain.model.SplitTunnelMode
import com.example.domain.model.TokenProfile
import com.example.domain.model.VpnConnectionState
import com.example.domain.model.VpnErrorInfo
import com.example.domain.model.VpnSettings
import com.example.domain.root.SafeRootRulePlanner
import com.example.domain.routing.ProtectedRuApps
import com.example.domain.routing.SmartRfRouter
import com.example.domain.vpn.StallWatchdogEvaluator
import com.example.domain.vpn.VpnErrorClassifier
import com.example.vpn.dns.TunnelDohResolver
import com.example.vpn.root.AdaptiveRootBoostController
import com.example.vpn.root.RootShellManager
import com.example.vpn.root.RootStealthTunOpener
import com.example.vpn.tunnel.FptnConnectionException
import com.example.vpn.tunnel.FptnProtocolClient
import com.example.vpn.tunnel.TunPacketLoop
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withTimeoutOrNull
import org.fptn.vpn.services.websocket.FptnNativeLoader
import org.fptn.vpn.services.websocket.NativeHttpsClientImpl
import org.fptn.vpn.services.websocket.NativeWebSocketClientImpl
import org.json.JSONObject
import java.net.Inet4Address
import java.net.InetAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * FPTN Android VPN Service powered directly by the official C++ BoringSSL engine
 * (`libfptn_native_lib.so` 1.3.28.0) via exact JNI bindings (`NativeHttpsClientImpl` +
 * `NativeWebSocketClientImpl`):
 * - Real BoringSSL SNI / Reality browser fingerprint spoofing & OBFUSCATION
 * - Native Rolling / Dual / Triple Sliding Tunnel
 * - True Stealth Root Mode (kernel `fptn0` via ip tuntap + JNA TUNSETIFF — NO `VpnService`, NO VPN key icon!)
 * - Automatic Reconnect on failure & network switch (`WIFI <-> CELLULAR` with optional Network Preset auto-switch)
 * - Active Stall Watchdog (heals silent TSPU/NAT 0 B/s blackholes via in-place soft WebSocket reconnect)
 * - Exact 507-rule official Russian apps protection (`always_excluded_apps.xml` incl. 2GIS)
 * - Optional in-tunnel Smart DNS (DoH) active strictly inside VPN
 */
class FptnVpnService : VpnService() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile
    private var dohInFlightSemaphore = Semaphore(MAX_CONCURRENT_DOH_PACKETS)
    @Volatile
    private var connectJob: Job? = null
    @Volatile
    private var reconnectJob: Job? = null
    @Volatile
    private var telemetryJob: Job? = null
    @Volatile
    private var shutdownJob: Job? = null

    private val activeNativeWsRef = AtomicReference<NativeWebSocketClientImpl?>(null)
    private val activeSessionGeneration = AtomicLong(0L)
    @Volatile
    private var tunLoop: TunPacketLoop? = null
    @Volatile
    private var dohResolver: TunnelDohResolver? = null

    @Volatile
    private var activeServer: FptnServer? = null
    @Volatile
    private var activeOwnerProfile: TokenProfile? = null
    @Volatile
    private var activeServerDnsIpv4: String = "1.1.1.1"
    @Volatile
    private var activeDohUrl: String? = null
    @Volatile
    private var connectedAtMillis: Long = 0L
    @Volatile
    private var isRootSessionActive: Boolean = false
    @Volatile
    private var activeSessionMtu: Int = OFFICIAL_MTU

    // Official WebSocketClientWrapper-style token cache for instant soft reconnects without extra /api/v1/login round-trips
    @Volatile
    private var cachedAccessToken: String? = null
    @Volatile
    private var cachedTokenServerId: Long = -1L
    @Volatile
    private var cachedTokenSni: String = ""
    @Volatile
    private var cachedTokenStrategy: String = ""

    private val isShuttingDown = AtomicBoolean(false)
    private val isReconnectingFlag = AtomicBoolean(false)
    @Volatile
    private var stallWatchdog = StallWatchdogEvaluator()

    // Separate counters strictly for the real Native WebSocket channel (unpolluted by local DoH replies)
    private val nativeWsRxBytes = AtomicLong(0L)
    private val nativeWsTxBytes = AtomicLong(0L)
    private val nativeWsTxPackets = AtomicLong(0L)
    private val nativeWsSendFailures = AtomicLong(0L)
    private val icmpProbeSeq = AtomicLong(1L)

    @Volatile
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    @Volatile
    private var networkDebounceJob: Job? = null
    @Volatile
    private var linkChangeDebounceJob: Job? = null
    private val activePhysicalNetworks = ConcurrentHashMap<Network, NetworkPresetTrigger>()
    private val activePhysicalLinkSignatures = ConcurrentHashMap<Network, String>()
    private val lastConnectedOrReconnectedAtMillis = AtomicLong(0L)
    @Volatile
    private var lastTransportType: NetworkPresetTrigger? = null
    // Session-learned direct IPv4s from live Split-DNS queries for .ru / custom-direct domains
    private val learnedDirectBypassIps = java.util.Collections.synchronizedSet(linkedSetOf<String>())

    private val powerLockCoordinator by lazy {
        PowerLockCoordinator(AndroidWakeLockHandle(applicationContext, WAKE_LOCK_TAG))
    }
    private val notificationManager by lazy {
        VpnNotificationManager(
            context = applicationContext,
            statusChannelId = CHANNEL_ID,
            alertChannelId = ALERT_CHANNEL_ID,
            alertNotificationId = ALERT_NOTIFICATION_ID,
        )
    }

    private fun updateNotificationLocale(appLanguage: AppLanguage) {
        notificationManager.setAppLanguage(appLanguage)
    }

    private fun launchBoundedDnsTask(
        dispatcher: CoroutineDispatcher = Dispatchers.IO,
        onOverload: () -> Unit,
        block: suspend () -> Unit,
    ) {
        val semaphore = dohInFlightSemaphore
        if (!semaphore.tryAcquire()) {
            onOverload()
            return
        }
        val released = AtomicBoolean(false)
        val releaseOnce = {
            if (released.compareAndSet(false, true)) {
                semaphore.release()
            }
        }
        try {
            val job = serviceScope.launch(dispatcher) {
                try {
                    block()
                } finally {
                    releaseOnce()
                }
            }
            job.invokeOnCompletion { releaseOnce() }
        } catch (t: Throwable) {
            releaseOnce()
            throw t
        }
    }

    override fun onCreate() {
        super.onCreate()
        ensureNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_DISCONNECT -> {
                cancelDisconnectAlertNotification()
                stopTunnelAndService("Отключено пользователем", preserveError = false, asyncCleanup = true)
                return START_NOT_STICKY
            }
            ACTION_CONNECT, ACTION_RECONNECT -> {
                val serverId = intent.getLongExtra(EXTRA_SERVER_ID, -1L)
                val isServerSwitch = intent.getBooleanExtra(EXTRA_IS_SERVER_SWITCH, false)
                val isReconnectAction = intent.action == ACTION_RECONNECT
                cancelDisconnectAlertNotification()
                isShuttingDown.set(false)
                startForegroundSafe(
                    buildNotification(
                        title = when {
                            isServerSwitch -> notificationManager.getString(R.string.vpn_notif_switching)
                            isReconnectAction -> notificationManager.getString(R.string.vpn_notif_reconnecting)
                            else -> notificationManager.getString(R.string.vpn_notif_connecting)
                        },
                        subtitle = null,
                    ),
                )
                beginConnect(serverId, isServerSwitch)
                return START_NOT_STICKY
            }
            VpnService.SERVICE_INTERFACE -> {
                // Triggered explicitly by Android's Always-On VPN framework.
                cancelDisconnectAlertNotification()
                isShuttingDown.set(false)
                startForegroundSafe(
                    buildNotification(
                        notificationManager.getString(R.string.vpn_notif_auto_connecting),
                        null,
                    ),
                )
                beginConnect(-1L, isServerSwitch = false)
                return START_NOT_STICKY
            }
            else -> {
                stopSelf(startId)
                return START_NOT_STICKY
            }
        }
    }

    private fun beginConnect(requestedServerId: Long, isServerSwitch: Boolean = false) {
        connectJob?.cancel()
        reconnectJob?.cancel()
        isReconnectingFlag.set(false)
        dohInFlightSemaphore = Semaphore(MAX_CONCURRENT_DOH_PACKETS)

        connectJob = serviceScope.launch {
            var connectLockToken = 0L
            try {
            // Wait cleanly if a prior async disconnect cleanup is still flushing iptables / fptn0
            shutdownJob?.join()
            if (!isActive || isShuttingDown.get()) return@launch
            val container = (application as FptnApplication).container
            var settings = container.repository.getSettings()
            updateNotificationLocale(settings.appLanguage)
            val profiles = container.repository.getAllProfiles()
            val enabledProfiles = profiles.filter { it.enabled }
            val allServers = enabledProfiles.flatMap { it.servers }

            var targetServer: FptnServer = allServers.firstOrNull { it.id == requestedServerId }
                ?: allServers.firstOrNull { it.id == settings.selectedServerId }
                ?: allServers.minByOrNull { it.pingMs ?: Int.MAX_VALUE }
                ?: run {
                    failAndStop(
                        server = null,
                        errorInfo = VpnErrorInfo(
                            title = "Нет доступных серверов",
                            details = "В списке нет активных серверов FPTN.",
                            hint = "Добавьте токен FPTN в разделе «Управление токенами».",
                        ),
                    )
                    return@launch
                }

            var ownerProfile: TokenProfile = enabledProfiles.firstOrNull { it.id == targetServer.profileId }
                ?: run {
                    failAndStop(
                        server = targetServer,
                        errorInfo = VpnErrorInfo(
                            title = "Профиль токена отключён",
                            details = "Не найден активный профиль для сервера ${targetServer.displayTitle}.",
                            hint = "Включите профиль в «Управлении токенами».",
                        ),
                    )
                    return@launch
                }

            if (!FptnNativeLoader.ensureLoaded()) {
                failAndStop(
                    server = targetServer,
                    errorInfo = VpnErrorInfo(
                        title = "Нативное ядро FPTN недоступно",
                        details = FptnNativeLoader.getLastLoadError() ?: "libfptn_native_lib.so не загружена",
                        hint = "Убедитесь, что приложение установлено на поддерживаемую архитектуру (arm64-v8a / armeabi-v7a / x86_64).",
                    ),
                )
                return@launch
            }

            // Detect current physical transport and apply matching Network Preset whenever
            // autoSwitchPresetsByNetwork is enabled. Manual edits to individual parameters remain
            // untouchable (respectManualOverrides = true), while Wi-Fi <-> Mobile triggers stay active!
            val prevTransport = lastTransportType
            val currentTransport = detectCurrentPhysicalTransport()
            lastTransportType = currentTransport
            if (settings.autoSwitchPresetsByNetwork && currentTransport != null) {
                val matchingPreset = settings.networkPresets.firstOrNull { it.trigger == currentTransport }
                val activePreset = settings.networkPresets.firstOrNull { it.id == settings.activePresetId }
                val transportChanged = prevTransport != null && prevTransport != currentTransport
                val activePresetMismatchesTransport = activePreset == null ||
                    (activePreset.trigger != NetworkPresetTrigger.MANUAL && activePreset.trigger != currentTransport)
                if (matchingPreset != null && (transportChanged || activePresetMismatchesTransport) && matchingPreset.id != settings.activePresetId) {
                    settings = settings.applyPreset(matchingPreset, respectManualOverrides = true)
                    container.repository.updateSettings {
                        it.applyPreset(matchingPreset, respectManualOverrides = true)
                    }
                    AppLogStore.info(
                        "Preset",
                        "Автоприменение пресета «${matchingPreset.name}» для сети ${currentTransport.title} (ручные параметры сохранены)",
                    )
                }
            }

            val previousServer = activeServer
            val switchingActiveNode = isServerSwitch || (previousServer != null && previousServer.id != targetServer.id)

            // Cleanly tear down any prior active session (e.g. seamless server switch while VPN is active!)
            telemetryJob?.cancel()
            telemetryJob = null
            activeSessionGeneration.incrementAndGet()
            activeNativeWsRef.getAndSet(null)?.let { oldWs -> releaseNativeWsAsync(oldWs) }
            dohResolver?.close()
            dohResolver = null
            val hadRootSession = isRootSessionActive || RootShellManager.areRulesActive.value
            if (hadRootSession) {
                RootShellManager.cleanupRulesOnlySynchronously()
                isRootSessionActive = false
            }
            tunLoop?.stop()
            tunLoop = null
            if (hadRootSession) {
                RootShellManager.cleanupTunOnlySynchronously()
            }

            // RU protection is permanently active even with Root (protects FPTN servers from abuse/blocking).
            val sanitizedRegion = BypassRegion.sanitize(settings.bypassRegion)
            if (settings.unlockProtectedRuWithRoot || !settings.smartRfRouting || settings.bypassRegion == BypassRegion.NONE) {
                settings = settings.copy(
                    unlockProtectedRuWithRoot = false,
                    smartRfRouting = true,
                    bypassRegion = sanitizedRegion,
                )
                container.repository.updateSettings {
                    it.copy(
                        unlockProtectedRuWithRoot = false,
                        smartRfRouting = true,
                        bypassRegion = BypassRegion.sanitize(it.bypassRegion),
                    )
                }
            }

            // Zero-spam Root check: reuse live cached libsu root shell if already verified!
            // Only invoke fresh `su -c id` when Root hasn't been confirmed yet (eliminates Magisk toast spam & 3s delay).
            val needsRootCheck = settings.useRootMode
            val liveRootGranted = if (needsRootCheck) {
                RootShellManager.isCachedRootSessionAlive() || RootShellManager.verifyRootNow()
            } else {
                RootShellManager.isRootAvailable.value
            }

            if (!liveRootGranted && needsRootCheck) {
                AppLogStore.warn(
                    "RootMode",
                    "Root не подтверждён (отозван в Magisk/KernelSU) — отключаем Root Stealth и возвращаемся к стандартному VPN",
                )
                settings = settings.copy(
                    useRootMode = false,
                    unlockProtectedRuWithRoot = false,
                    smartRfRouting = true,
                    bypassRegion = sanitizedRegion,
                )
                container.repository.updateSettings {
                    it.copy(
                        useRootMode = false,
                        unlockProtectedRuWithRoot = false,
                        smartRfRouting = true,
                        bypassRegion = BypassRegion.sanitize(it.bypassRegion),
                    )
                }
            }

            val rootTunName = SafeRootRulePlanner.sanitizeTunName(settings.rootTunInterfaceName)
            if (settings.useRootMode && liveRootGranted) {
                val hasResidualRootState = RootShellManager.areRulesActive.value ||
                    java.io.File("/sys/class/net/$rootTunName").exists() ||
                    java.io.File("/sys/class/net/${RootShellManager.currentActiveTunName()}").exists()
                if (hasResidualRootState) {
                    RootShellManager.cleanupRulesSynchronously(rootTunName)
                }
            }

            var sniHost = settings.customSni.trim().ifBlank { VpnSettings.DEFAULT_SNI_DOMAIN }
            var censorshipStrategy = settings.effectiveCensorshipStrategyName()
            val connectionStrategy = settings.slidingTunnelMode.nativeStrategyName
            val rawMtu = settings.tunMtu.coerceIn(1280, 1500)
            // Official FPTN server default MTU_SIZE is 1400 (1360 on LTE/5G CGNAT avoids PMTUD blackholing on YouTube/QUIC).
            // Auto-clamp non-manual MTU so existing v2.2 users with 1500 heal automatically without manual intervention.
            val effectiveMtu = if (ManualOverrideKey.TUN_MTU in settings.manualOverrides) {
                rawMtu
            } else if (currentTransport == NetworkPresetTrigger.MOBILE) {
                minOf(rawMtu, 1360)
            } else {
                minOf(rawMtu, OFFICIAL_MTU)
            }
            activeSessionMtu = effectiveMtu
            val allowIpv6Session = settings.allowIpv6
            val baseHandshakeTimeout = settings.handshakeTimeoutSec.coerceIn(4, 20)
            val handshakeTimeout = if (currentTransport == NetworkPresetTrigger.MOBILE) {
                baseHandshakeTimeout.coerceAtLeast(10)
            } else {
                baseHandshakeTimeout
            }
            val wsTimeout = settings.wsOpenTimeoutSec.coerceIn(5, 30).toLong()

            fun emitProgressStage(stage: String) {
                if (switchingActiveNode) {
                    VpnStateHolder.updateState(
                        VpnConnectionState.SwitchingServer(
                            fromServer = previousServer,
                            toServer = targetServer,
                            stage = stage,
                        ),
                    )
                } else {
                    VpnStateHolder.updateState(VpnConnectionState.Connecting(targetServer, stage))
                }
            }

            emitProgressStage(
                if (switchingActiveNode) {
                    "Переключение на ${targetServer.cleanName} ($censorshipStrategy • $sniHost)…"
                } else {
                    "Авторизация BoringSSL ($censorshipStrategy • SNI $sniHost)…"
                },
            )
            AppLogStore.info(
                "VpnService",
                "Подключение (native 1.3.28.0) к ${targetServer.cleanDisplayTitle} (${targetServer.endpoint}) | strategy=$censorshipStrategy | tunnel=$connectionStrategy | SNI=$sniHost | MTU=$effectiveMtu | DNS=${settings.dnsMode.shortTitle}",
            )

            // Fast client-side network check so offline devices report an honest local network error
            // instead of falsely blaming the FPTN server with "Port not reachable (609)".
            if (!hasActivePhysicalNetwork()) {
                failAndStop(
                    server = targetServer,
                    errorInfo = VpnErrorInfo(
                        title = "Нет доступа к сети на устройстве",
                        details = "Wi-Fi и мобильная передача данных недоступны при подключении к ${targetServer.endpoint}.",
                        hint = "Включите интернет на устройстве (Wi-Fi или 4G/5G) и повторите подключение.",
                    ),
                )
                return@launch
            }

            // Resolve mutually exclusive DNS strategy for this session:
            // - AUTO_VPN: internal server DNS IPv4/IPv6 returned by /api/v1/dns.
            // - ADBLOCK_DNS: AdGuard UDP:53 (94.140.14.14 / 94.140.15.15) routed strictly inside the tunnel.
            // - CUSTOM_IPV4: user-configured primary/secondary IPv4 DNS routed /32 inside the tunnel.
            // - SMART_DNS_DOH: uses internal server DNS on TUN so Private DNS does not bypass via DoT:853,
            //   while UDP:53 queries are intercepted in-tunnel and forwarded over DoH.
            val parsedSmartDns = when (settings.dnsMode) {
                DnsMode.SMART_DNS_DOH -> {
                    val targetUrl = settings.customDnsUrl.ifBlank { VpnSettings.DEFAULT_SMART_DNS_URL }
                    DnsEndpointParser.parse(targetUrl).getOrNull()
                }
                DnsMode.AUTO_VPN, DnsMode.ADBLOCK_DNS, DnsMode.CUSTOM_IPV4 -> null
            }
            val dohUrl = if (parsedSmartDns?.type != com.example.domain.dns.DnsEndpointType.IPV4) {
                parsedSmartDns?.dohUrl
            } else {
                null
            }
            val matchingSavedDns = if (settings.dnsMode == DnsMode.SMART_DNS_DOH) {
                settings.savedDnsEntries.firstOrNull {
                    it.urlOrHost.equals(settings.customDnsUrl, ignoreCase = true)
                }
            } else {
                null
            }
            val dohBootstrapIpv4 = if (settings.dnsMode == DnsMode.SMART_DNS_DOH) {
                matchingSavedDns?.fallbackIpv4 ?: parsedSmartDns?.ipv4Fallback
            } else {
                null
            }
            val fallbackDnsIpv4 = when (settings.dnsMode) {
                DnsMode.AUTO_VPN -> "1.1.1.1"
                DnsMode.ADBLOCK_DNS -> VpnSettings.ADGUARD_IPV4
                DnsMode.CUSTOM_IPV4 -> settings.dnsPrimary.ifBlank { "1.1.1.1" }
                DnsMode.SMART_DNS_DOH -> if (parsedSmartDns?.type == com.example.domain.dns.DnsEndpointType.IPV4) {
                    parsedSmartDns.host
                } else {
                    "1.1.1.1"
                }
            }

            // Acquire generation-scoped partial WakeLock during handshake/connect
            connectLockToken = powerLockCoordinator.acquire(12_000L)

            // Authenticate via native HTTPS and fetch /api/v1/dns with automatic SNI rotation (600) and node failover (609).
            var handshake = performNativeLoginWithSelfHealing(
                server = targetServer,
                ownerProfile = ownerProfile,
                initialSni = sniHost,
                initialStrategy = censorshipStrategy,
                settings = settings,
                fallbackDnsIpv4 = fallbackDnsIpv4,
                preferServerDns = true,
                timeoutSec = handshakeTimeout,
                onStageUpdate = { stage -> emitProgressStage(stage) },
            )
            if (handshake.isFailure && settings.autoFailoverServer && !isShuttingDown.get()) {
                val firstEx = handshake.exceptionOrNull() as? FptnConnectionException
                val shouldFailover = VpnErrorClassifier.shouldAutoFailoverServer(
                    code = firstEx?.nativeCode ?: -1,
                    errorMessage = firstEx?.rawError ?: firstEx?.errorInfo?.details,
                )
                if (shouldFailover && hasActivePhysicalNetwork()) {
                    val backupCandidates = allServers
                        .filter { it.id != targetServer.id }
                        .sortedBy { it.pingMs ?: Int.MAX_VALUE }
                        .take(2)
                    for (backupSrv in backupCandidates) {
                        if (!isActive || isShuttingDown.get()) break
                        val backupOwner = enabledProfiles.firstOrNull { it.id == backupSrv.profileId } ?: continue
                        emitProgressStage("Узел недоступен — пробуем резервный ${backupSrv.cleanName}…")
                        AppLogStore.warn(
                            "Failover",
                            "Сервер ${targetServer.cleanName} недоступен (${firstEx?.nativeCode ?: -1}) — автопереход на ${backupSrv.cleanName} (${backupSrv.endpoint})",
                        )
                        val backupHandshake = performNativeLoginWithSelfHealing(
                            server = backupSrv,
                            ownerProfile = backupOwner,
                            initialSni = sniHost,
                            initialStrategy = censorshipStrategy,
                            settings = settings,
                            fallbackDnsIpv4 = fallbackDnsIpv4,
                            preferServerDns = true,
                            timeoutSec = handshakeTimeout.coerceAtMost(7),
                            onStageUpdate = { stage -> emitProgressStage(stage) },
                        )
                        if (backupHandshake.isSuccess) {
                            targetServer = backupSrv
                            ownerProfile = backupOwner
                            handshake = backupHandshake
                            break
                        }
                    }
                }
            }
            if (handshake.isFailure) {
                val errInfo = (handshake.exceptionOrNull() as? FptnConnectionException)?.errorInfo
                    ?: VpnErrorInfo(
                        title = "Сбой авторизации",
                        details = handshake.exceptionOrNull()?.message ?: "Неизвестная ошибка",
                        hint = "Попробуйте «Авто-починка (SNI + узел)» или откройте «Журнал».",
                    )
                failAndStop(targetServer, errInfo)
                return@launch
            }
            val loginResult = handshake.getOrThrow()

            // Effective primary DNS IPv4 configured on TUN / Root NAT:
            // - AUTO_VPN: exact FPTN server internal DNS (e.g. 172.20.0.1) — zero leak, Gemini works!
            // - ADBLOCK_DNS: AdGuard 94.140.14.14 inside VPN tunnel — zero leak, zero TSPU block!
            // - CUSTOM_IPV4: user's primary IPv4 inside VPN tunnel (with server DNS as secondary fallback)
            // - SMART_DNS_DOH: if plain IPv4 -> that IPv4; if DoH URL -> internal FPTN server DNS
            //   (private IP prevents Android Pixel Private DNS from bypassing UDP:53 via DoT TCP:853!)
            val effectiveSessionDnsIpv4 = when (settings.dnsMode) {
                DnsMode.AUTO_VPN -> loginResult.dnsIpv4
                DnsMode.ADBLOCK_DNS -> VpnSettings.ADGUARD_IPV4
                DnsMode.CUSTOM_IPV4 -> FptnProtocolClient.resolveSafeDns(
                    settings.dnsPrimary.ifBlank { loginResult.dnsIpv4 },
                    OFFICIAL_TUN_IPV4,
                )
                DnsMode.SMART_DNS_DOH -> if (parsedSmartDns?.type == com.example.domain.dns.DnsEndpointType.IPV4) {
                    FptnProtocolClient.resolveSafeDns(parsedSmartDns.host, OFFICIAL_TUN_IPV4)
                } else {
                    loginResult.dnsIpv4
                }
            }
            val effectiveSessionDnsIpv6 = when (settings.dnsMode) {
                DnsMode.AUTO_VPN -> loginResult.dnsIpv6
                DnsMode.ADBLOCK_DNS -> "2a10:50c0::ad1:ff"
                DnsMode.CUSTOM_IPV4, DnsMode.SMART_DNS_DOH -> null
            }
            if (loginResult.usedSni != sniHost || loginResult.usedStrategy != censorshipStrategy) {
                sniHost = loginResult.usedSni
                censorshipStrategy = loginResult.usedStrategy
                AppLogStore.info(
                    "SelfHeal",
                    "Авто-восстановление после сбоя TLS: стратегия=$censorshipStrategy, SNI=$sniHost",
                )
                container.repository.updateSettings { cur ->
                    val allowSniPersist = ManualOverrideKey.CUSTOM_SNI !in cur.manualOverrides
                    val allowBypassPersist = ManualOverrideKey.BYPASS_METHOD !in cur.manualOverrides
                    val nextMethod = if (censorshipStrategy.equals("OBFUSCATION", ignoreCase = true)) {
                        BypassMethod.TLS_OBFUSCATION
                    } else {
                        BypassMethod.SNI_REALITY
                    }
                    val nextSpoof = if (censorshipStrategy.equals("SNI", ignoreCase = true)) {
                        SniSpoofingMode.SNI
                    } else {
                        cur.sniSpoofingMode
                    }
                    cur.copy(
                        customSni = if (allowSniPersist) sniHost else cur.customSni,
                        bypassMethod = if (allowBypassPersist) nextMethod else cur.bypassMethod,
                        sniSpoofingMode = if (allowBypassPersist) nextSpoof else cur.sniSpoofingMode,
                    )
                }
            }

            if (!isActive || isShuttingDown.get()) return@launch

            val useStealthRoot = settings.useRootMode && liveRootGranted
            // RU protection is permanently locked even with Root to keep FPTN servers safe.
            val rootUnlockRu = false
            emitProgressStage(
                if (useStealthRoot) {
                    "Запуск скрытого Root TUN (fptn0, без иконки VPN)…"
                } else {
                    "Настройка TUN, маршрутов и запуск туннеля…"
                },
            )

            // STEP 2: Establish TUN interface (either True Stealth Root fptn0 OR standard VpnService)
            val physicalNetwork = resolvePhysicalNetwork()
            val physicalUpstreamDnsIpv4s = resolvePhysicalDnsIpv4s(physicalNetwork)
            val physicalHostResolver: (String) -> Array<InetAddress> = { host ->
                if (physicalNetwork != null) {
                    runCatching { physicalNetwork.getAllByName(host) }.getOrElse { InetAddress.getAllByName(host) }
                } else {
                    InetAddress.getAllByName(host)
                }
            }

            val resolvedServerIpv4 = SmartRfRouter.resolveHostToIpv4(targetServer.host)
            // Official FptnConnection parity: always exclude protected RU apps (except censored-zone entry servers)
            val protectedPackagesOnDevice = if (targetServer.isCensoredZone) {
                emptySet()
            } else {
                ProtectedRuApps.resolveProtectedPackagesOnDevice(this@FptnVpnService)
            }
            val effectiveRegion = SmartRfRouter.effectiveRegion(
                smartRfEnabled = true,
                bypassRegion = settings.bypassRegion,
                allowOverrideRu = false,
            )
            val regionBypassCidrs = (SmartRfRouter.russianBypassCidrs + SmartRfRouter.cidrsForRegion(effectiveRegion)).distinct()
            val permittedCustomVpnDomains = settings.customVpnDomains.filterNot {
                SmartRfRouter.isProtectedDomainForVpnOverride(it, effectiveRegion)
            }.toSet()

            // Pre-resolve BOTH user customDirectDomains AND core regional direct domains (with www. variants)
            // in parallel over the physical network so browsers immediately bypass VPN for .ru & custom sites!
            val domainsToPreResolveDirect = linkedSetOf<String>().apply {
                addAll(settings.customDirectDomains)
                addAll(effectiveRegion.directDomainRoots)
                if (effectiveRegion != BypassRegion.RUSSIA) {
                    addAll(BypassRegion.RUSSIA.directDomainRoots)
                }
            }
            val preResolvedDirectIpv4s = SmartRfRouter.resolveDomainsToIpv4sParallel(
                domains = domainsToPreResolveDirect,
                includeWww = true,
                maxDomains = 96,
                totalTimeoutMs = 1_500L,
                hostResolver = physicalHostResolver,
            )
            val extraDirectIpv4s = buildSet {
                addAll(preResolvedDirectIpv4s)
                synchronized(learnedDirectBypassIps) { addAll(learnedDirectBypassIps) }
            }
            val extraVpnIpv4s = SmartRfRouter.resolveDomainsToIpv4sParallel(
                domains = permittedCustomVpnDomains,
                includeWww = true,
                maxDomains = 32,
                totalTimeoutMs = 900L,
                hostResolver = physicalHostResolver,
            )
            AppLogStore.info(
                "AppExclusion",
                "Защита РФ-приложений активна (закреплена постоянно): исключено ${protectedPackagesOnDevice.size} пакетов (база ${ProtectedRuApps.getRuleCount(this@FptnVpnService)} правил)",
            )
            AppLogStore.info(
                "Routing",
                "Маршрутизация сайтов: регион=${effectiveRegion.title}, GeoIP=${regionBypassCidrs.size} подсетей, напрямую=${extraDirectIpv4s.size} IP, через VPN=${extraVpnIpv4s.size} IP, Split-DNS=${physicalUpstreamDnsIpv4s.joinToString(",")}",
            )

            val tunFd: ParcelFileDescriptor?
            if (useStealthRoot) {
                if (!isActive || isShuttingDown.get()) return@launch
                val stealthResult = RootStealthTunOpener.openStealthTun(
                    context = this@FptnVpnService,
                    appUid = Process.myUid(),
                    tunName = rootTunName,
                    clientIpv4 = OFFICIAL_TUN_IPV4,
                    mtu = effectiveMtu,
                )
                if (stealthResult.isFailure) {
                    if (!isActive || isShuttingDown.get()) return@launch
                    failAndStop(
                        server = targetServer,
                        errorInfo = VpnErrorInfo(
                            title = "Ошибка скрытого Root TUN ($rootTunName)",
                            details = stealthResult.exceptionOrNull()?.message ?: "Не удалось открыть /dev/tun",
                            hint = "Проверьте права Root в Magisk/KernelSU (не в DenyList) или отключите Root-режим для стандартного VpnService.",
                        ),
                    )
                    return@launch
                }
                val openedFd = stealthResult.getOrThrow()
                // Critical race guard: if user pressed Disconnect while openStealthTun was running,
                // close the freshly opened FD & tear down kernel TUN immediately so nothing leaks!
                if (!isActive || isShuttingDown.get()) {
                    runCatching { openedFd.close() }
                    RootShellManager.cleanupTunOnlySynchronously(rootTunName)
                    return@launch
                }
                val excludedUids = resolveExcludedUids(settings, protectedPackagesOnDevice)
                val systemBypassUids = resolveSystemConnectivityBypassUids()
                val rootRouteRes = RootShellManager.applyIsolatedRouting(
                    appUid = Process.myUid(),
                    vpnServerIpv4 = resolvedServerIpv4.orEmpty(),
                    tunInterfaceName = rootTunName,
                    clientTunIpv4 = OFFICIAL_TUN_IPV4,
                    dnsServerIpv4 = effectiveSessionDnsIpv4,
                    smartRfEnabled = true,
                    excludedUids = excludedUids,
                    systemBypassUids = systemBypassUids,
                    allowSecondaryVpnChaining = settings.allowSecondaryVpnChaining,
                    bypassCidrs = regionBypassCidrs,
                    extraDirectIpv4s = extraDirectIpv4s,
                    extraVpnIpv4s = extraVpnIpv4s,
                )
                if (!isActive || isShuttingDown.get()) {
                    runCatching { openedFd.close() }
                    RootShellManager.cleanupRulesSynchronously(rootTunName)
                    return@launch
                }
                if (rootRouteRes.isFailure) {
                    runCatching { openedFd.close() }
                    RootShellManager.cleanupRulesSynchronously(rootTunName)
                    failAndStop(
                        server = targetServer,
                        errorInfo = VpnErrorInfo(
                            title = "Ошибка Root-маршрутизации",
                            details = rootRouteRes.exceptionOrNull()?.message ?: "Сбой применения iptables/ip rule",
                            hint = "Все правила автоматически откатились. Проверьте права su в Magisk/KernelSU или отключите Root-режим.",
                        ),
                    )
                    return@launch
                }
                isRootSessionActive = true
                tunFd = openedFd
            } else {
                isRootSessionActive = false
                tunFd = establishTunInterface(
                    server = targetServer,
                    settings = settings,
                    effectiveMtu = effectiveMtu,
                    currentTransport = currentTransport,
                    clientTunIp = OFFICIAL_TUN_IPV4,
                    dnsServer = effectiveSessionDnsIpv4,
                    fallbackServerDnsIpv4 = loginResult.dnsIpv4,
                    dnsServerIpv6 = effectiveSessionDnsIpv6,
                    resolvedServerIpv4 = resolvedServerIpv4,
                    protectedPackages = protectedPackagesOnDevice,
                    extraDirectIpv4s = extraDirectIpv4s,
                    extraVpnIpv4s = extraVpnIpv4s,
                    allowOverrideRu = false,
                )
            }

            if (tunFd == null) {
                failAndStop(
                    server = targetServer,
                    errorInfo = VpnErrorInfo(
                        title = "Нет системного VPN-разрешения",
                        details = "Android не позволил создать виртуальный TUN-интерфейс (Builder.establish вернул null).",
                        hint = "Подтвердите системный запрос VPN в диалоге Android или в разделе «Разрешения».",
                    ),
                )
                return@launch
            }

            if (!isRootSessionActive) {
                runCatching { setUnderlyingNetworks(null) }
            }

            // STEP 3: Optional in-tunnel Smart DNS (DoH) active strictly inside active VPN
            dohResolver?.close()
            dohResolver = dohUrl?.let { url ->
                AppLogStore.info(
                    "SmartDNS",
                    "Внутритуннельный Smart DNS (DoH) активен: $url${dohBootstrapIpv4?.let { " (bootstrap $it)" }.orEmpty()}",
                )
                TunnelDohResolver(
                    dohUrl = url,
                    protectSocket = { socket -> if (isRootSessionActive) true else protect(socket) },
                    bootstrapIpv4 = dohBootstrapIpv4,
                    blockAaaa = !allowIpv6Session,
                )
            }

            // STEP 4: Wire TUN packet loop <-> Official Native WebSocket Engine
            lateinit var loop: TunPacketLoop
            loop = TunPacketLoop(
                tunInterface = tunFd,
                onOutboundPacket = { packet, len ->
                    val ipVersion = TunnelDohResolver.getIpVersion(packet, len)
                    if (ipVersion == 6 && !allowIpv6Session) {
                        // Reply with RFC-4443 ICMPv6 Admin Prohibited instead of blackholing so apps
                        // (GMS, Chrome) immediately fall back to IPv4 without waiting on connect timeouts.
                        TunnelDohResolver.trySynthesizeIcmpv6AdminProhibited(packet, len)?.let { icmpErr ->
                            loop.writeInboundPacket(icmpErr)
                        }
                        return@TunPacketLoop
                    }
                    val isDnsUdp = TunnelDohResolver.isIpv4UdpDnsQuery(packet, len)
                    if (isDnsUdp) {
                        val queriedDomain = TunnelDohResolver.extractDnsQueryDomain(packet, len)
                        val isDirectDomain = queriedDomain != null && SmartRfRouter.decideForDomain(
                            domain = queriedDomain,
                            smartRfEnabled = true,
                            bypassRegion = effectiveRegion,
                            customDirectDomains = settings.customDirectDomains,
                            customVpnDomains = permittedCustomVpnDomains,
                            allowOverrideRu = false,
                        ) == com.example.domain.routing.RouteDecision.DIRECT_RF

                        // For direct (.ru / regional / custom-direct) domains OR when IPv6 is disabled,
                        // return empty AAAA NODATA immediately so browsers use IPv4 bypass routes instead of ::/0 VPN!
                        if (isDirectDomain || !allowIpv6Session) {
                            val aaaaNodata = TunnelDohResolver.trySynthesizeEmptyAaaaReply(packet, len)
                            if (aaaaNodata != null) {
                                loop.writeInboundPacket(aaaaNodata)
                                return@TunPacketLoop
                            }
                        }

                        // Live Split-DNS for DIRECT_RF domains (.ru/.рф/RU banks/customDirectDomains):
                        // Resolve directly via physical network so Russian GeoDNS returns true local IPv4s,
                        // and in Root mode dynamically install `/32 RETURN` BEFORE replying to the browser!
                        if (isDirectDomain) {
                            val copy = packet.copyOf(len)
                            launchBoundedDnsTask(
                                dispatcher = Dispatchers.IO,
                                onOverload = { sendPacketToActiveWs(copy, len) },
                            ) {
                                val directRes = TunnelDohResolver.forwardUdpDnsDirectly(
                                    packet = copy,
                                    length = len,
                                    upstreamDnsIpv4s = physicalUpstreamDnsIpv4s,
                                    protectDatagramSocket = { sock ->
                                        if (isRootSessionActive) true else protect(sock)
                                    },
                                )
                                if (directRes != null) {
                                    val (replyPacket, resolvedIps) = directRes
                                    if (resolvedIps.isNotEmpty()) {
                                        synchronized(learnedDirectBypassIps) {
                                            if (learnedDirectBypassIps.size < 256) {
                                                learnedDirectBypassIps.addAll(resolvedIps)
                                            }
                                        }
                                        if (isRootSessionActive) {
                                            RootShellManager.addDynamicDirectBypassIps(resolvedIps)
                                        }
                                    }
                                    loop.writeInboundPacket(replyPacket)
                                } else {
                                    sendPacketToActiveWs(copy, len)
                                }
                            }
                            return@TunPacketLoop
                        }
                    }

                    val resolver = dohResolver
                    // When Smart DNS DoH is active, immediately RST opportunistic DoT (TCP:853) probes
                    // so Android Pixel Private DNS cannot silently bypass our Smart DNS resolver!
                    if (resolver != null && TunnelDohResolver.isIpv4TcpPort853(packet, len)) {
                        TunnelDohResolver.trySynthesizeIpv4TcpRst(packet, len)?.let { rst ->
                            loop.writeInboundPacket(rst)
                        }
                        return@TunPacketLoop
                    }
                    if (resolver != null && isDnsUdp) {
                        val copy = packet.copyOf(len)
                        launchBoundedDnsTask(
                            dispatcher = Dispatchers.IO,
                            onOverload = { sendPacketToActiveWs(copy, len) },
                        ) {
                            val reply = resolver.tryHandleDnsPacket(copy, len)
                            if (reply != null) {
                                loop.writeInboundPacket(reply)
                            } else {
                                sendPacketToActiveWs(copy, len)
                            }
                        }
                    } else {
                        sendPacketToActiveWs(packet, len)
                    }
                },
                onStopped = {
                    AppLogStore.info("TunLoop", "TUN-интерфейс закрыт и освобождён")
                },
                onTunWriteFailure = { ex ->
                    if (!isShuttingDown.get() && !isRootSessionActive) {
                        AppLogStore.warn("TunLoop", "Сбой записи в TUN (${ex.message}) — переподключение сессии…")
                        activeServer?.id?.let { srvId ->
                            serviceScope.launch { beginConnect(srvId, isServerSwitch = false) }
                        }
                    }
                },
            )
            tunLoop = loop

            activeServer = targetServer
            activeOwnerProfile = ownerProfile
            activeServerDnsIpv4 = effectiveSessionDnsIpv4
            activeDohUrl = dohUrl
            cacheAccessToken(
                token = loginResult.accessToken,
                serverId = targetServer.id,
                sni = sniHost,
                strategy = censorshipStrategy,
            )
            connectedAtMillis = System.currentTimeMillis()
            lastConnectedOrReconnectedAtMillis.set(connectedAtMillis)
            stallWatchdog = createWatchdogForSettings(settings, currentTransport)

            // STEP 5: Open initial Native WebSocket with automatic strategy fallback (Reality -> SNI -> OBFUSCATION)
            val openRes = openNativeWebSocketWithFallback(
                server = targetServer,
                accessToken = loginResult.accessToken,
                sniHost = sniHost,
                initialStrategy = censorshipStrategy,
                connectionStrategy = connectionStrategy,
                settings = settings,
                loop = loop,
                openTimeoutSec = wsTimeout,
                onStageUpdate = { stage -> emitProgressStage(stage) },
            )
            if (openRes.isFailure) {
                val errInfo = (openRes.exceptionOrNull() as? FptnConnectionException)?.errorInfo
                    ?: VpnErrorInfo(
                        title = "Туннель отклонён сервером",
                        details = openRes.exceptionOrNull()?.message ?: "Не удалось открыть нативный WebSocket",
                        hint = "Попробуйте «Автоподбор SNI», метод «Обфускация» или другой сервер.",
                    )
                failAndStop(targetServer, errInfo)
                return@launch
            }

            val (nativeWs, usedWsStrategy) = openRes.getOrThrow()
            if (usedWsStrategy != censorshipStrategy) {
                censorshipStrategy = usedWsStrategy
            }
            loop.start()
            if (isRootSessionActive) {
                AdaptiveRootBoostController.start(
                    scope = serviceScope,
                    context = applicationContext,
                    tunInterfaceName = SafeRootRulePlanner.sanitizeTunName(settings.rootTunInterfaceName),
                    tunLoop = loop,
                    enabled = settings.rootAdaptiveBoostEnabled,
                )
            } else {
                AdaptiveRootBoostController.stop()
            }

            val assignedIp = VpnErrorClassifier.normalizeAssignedIp(nativeWs.getIPv4Address(), OFFICIAL_TUN_IPV4)
            cancelDisconnectAlertNotification()
            VpnStateHolder.updateState(
                VpnConnectionState.Connected(
                    server = targetServer,
                    isRootMode = isRootSessionActive,
                    assignedIp = assignedIp,
                    dnsServer = loginResult.dnsIpv4,
                    connectedAtMillis = connectedAtMillis,
                    activeDohUrl = dohUrl,
                ),
            )
            AppLogStore.info(
                "VpnService",
                "Подключено к ${targetServer.displayTitle} | IP=$assignedIp | DNS=${dohUrl ?: loginResult.dnsIpv4} | $censorshipStrategy | $connectionStrategy | StealthRoot=$isRootSessionActive",
            )
            powerLockCoordinator.releaseIfOwner(connectLockToken)
            connectLockToken = 0L
            registerNetworkWatcher()
            startTelemetryAndWatchdogLoop(targetServer, settings)
            } finally {
                if (connectLockToken != 0L) {
                    powerLockCoordinator.releaseIfOwner(connectLockToken)
                }
            }
        }
    }

    private data class NativeLoginResult(
        val accessToken: String,
        val dnsIpv4: String,
        val dnsIpv6: String?,
        val usedSni: String,
        val usedStrategy: String,
    )

    private suspend fun performNativeLoginWithSelfHealing(
        server: FptnServer,
        ownerProfile: TokenProfile,
        initialSni: String,
        initialStrategy: String,
        settings: VpnSettings,
        fallbackDnsIpv4: String,
        preferServerDns: Boolean,
        timeoutSec: Int,
        onStageUpdate: (String) -> Unit,
    ): Result<NativeLoginResult> {
        val firstAttempt = performNativeLogin(
            server = server,
            ownerProfile = ownerProfile,
            sniHost = initialSni,
            censorshipStrategy = initialStrategy,
            fallbackDnsIpv4 = fallbackDnsIpv4,
            preferServerDns = preferServerDns,
            timeoutSec = timeoutSec,
        )
        if (firstAttempt.isSuccess || !settings.autoRotateSniOnFail || isShuttingDown.get()) {
            return firstAttempt
        }

        val firstEx = firstAttempt.exceptionOrNull() as? FptnConnectionException
        val firstDetails = firstEx?.errorInfo?.details.orEmpty()
        val firstTitle = firstEx?.errorInfo?.title.orEmpty()
        val canHeal = VpnErrorClassifier.isHealableConnectionError(
            code = firstEx?.nativeCode ?: -1,
            errorMessage = "${firstEx?.rawError.orEmpty()} $firstTitle $firstDetails",
        )
        if (!canHeal) return firstAttempt

        AppLogStore.warn(
            "SelfHeal",
            "TLS/сессия сброшена (code=${firstEx?.nativeCode ?: -1}, $firstTitle) — авто-восстановление стратегии/SNI…",
        )

        val universalSnis = listOf("ads.x5.ru", "ya.ru", "vk.com", "ozon.ru", "gosuslugi.ru")
        val regionalSnis = (settings.bypassRegion.recommendedSnis + universalSnis)
            .map { it.trim().lowercase() }
            .filter { it.isNotEmpty() && !it.equals(initialSni, ignoreCase = true) }
            .distinct()

        val fallbacks = buildList<Pair<String, String>> {
            if (initialStrategy.startsWith("SNI-REALITY", ignoreCase = true)) {
                add("SNI" to initialSni)
            }
            if (!initialStrategy.equals("OBFUSCATION", ignoreCase = true)) {
                add("OBFUSCATION" to initialSni)
            } else {
                add("SNI" to initialSni)
            }
            regionalSnis.take(3).forEachIndexed { index, altSni ->
                val strat = if (index % 2 == 0) "SNI" else "OBFUSCATION"
                add(strat to altSni)
            }
        }.distinct().take(5)

        val quickTimeout = timeoutSec.coerceAtMost(6)
        for ((fallbackStrategy, fallbackSni) in fallbacks) {
            if (isShuttingDown.get()) break
            onStageUpdate("Авто-починка TLS ($fallbackStrategy • $fallbackSni)…")
            AppLogStore.info("SelfHeal", "Попытка обхода: strategy=$fallbackStrategy | SNI=$fallbackSni")
            val attempt = performNativeLogin(
                server = server,
                ownerProfile = ownerProfile,
                sniHost = fallbackSni,
                censorshipStrategy = fallbackStrategy,
                fallbackDnsIpv4 = fallbackDnsIpv4,
                preferServerDns = preferServerDns,
                timeoutSec = quickTimeout,
            )
            if (attempt.isSuccess) {
                return attempt
            }
        }
        return firstAttempt
    }

    private fun createWatchdogForSettings(
        settings: VpnSettings,
        transport: NetworkPresetTrigger? = lastTransportType,
    ): StallWatchdogEvaluator {
        val isCellular = transport == NetworkPresetTrigger.MOBILE
        return StallWatchdogEvaluator.forSensitivity(
            sensitivity = settings.watchdogSensitivity,
            baseProbeSec = settings.watchdogProbeSec,
            baseStallSec = settings.watchdogStallSec,
            baseCooldownSec = settings.watchdogCooldownSec,
            isCellular = isCellular,
        )
    }

    private suspend fun performNativeLogin(
        server: FptnServer,
        ownerProfile: TokenProfile,
        sniHost: String,
        censorshipStrategy: String,
        fallbackDnsIpv4: String,
        preferServerDns: Boolean,
        timeoutSec: Int = HANDSHAKE_TIMEOUT_SEC,
    ): Result<NativeLoginResult> {
        var serverDnsIpv4 = FptnProtocolClient.resolveSafeDns(fallbackDnsIpv4, OFFICIAL_TUN_IPV4)
        var serverDnsIpv6: String? = null

        val httpsClient = try {
            NativeHttpsClientImpl(
                server.host,
                server.port,
                server.md5Fingerprint,
                sniHost,
                censorshipStrategy,
            )
        } catch (ex: Exception) {
            return Result.failure(
                FptnConnectionException(
                    VpnErrorInfo(
                        title = "Сбой инициализации нативного HTTPS",
                        details = ex.message ?: ex.javaClass.simpleName,
                        hint = "Проверьте параметры сервера и метода обхода.",
                    ),
                    ex,
                ),
            )
        }

        return try {
            val loginPayload = JSONObject().apply {
                put("username", ownerProfile.username)
                put("password", ownerProfile.password)
            }.toString()

            // Official WebSocketClientWrapper parity: retry transient non-401/403 login errors quickly
            var loginResp: org.fptn.vpn.services.websocket.NativeResponse? = null
            for (apiAttempt in 1..MAX_TRANSIENT_LOGIN_RETRIES) {
                if (isShuttingDown.get()) break
                loginResp = httpsClient.post("/api/v1/login", loginPayload, timeoutSec)
                val code = loginResp?.code ?: 0
                if (code == 200 || code == 401 || code == 403) break
                if (apiAttempt < MAX_TRANSIENT_LOGIN_RETRIES) {
                    AppLogStore.warn(
                        "VpnService",
                        "Временный сбой /api/v1/login (code=$code, попытка $apiAttempt/$MAX_TRANSIENT_LOGIN_RETRIES) — быстрый повтор…",
                    )
                    delay(300L)
                }
            }

            if (loginResp == null || loginResp.code != 200) {
                val nativeCode = loginResp?.code ?: 0
                val rawErr = loginResp?.errorMessage.orEmpty()
                val info = VpnErrorClassifier.classifyNativeHttpFailure(
                    code = nativeCode,
                    errorMessage = loginResp?.errorMessage,
                    body = loginResp?.body,
                    endpoint = server.endpoint,
                    censorshipStrategy = censorshipStrategy,
                    sniHost = sniHost,
                    hasPhysicalNetwork = hasActivePhysicalNetwork(),
                )
                return Result.failure(
                    FptnConnectionException(
                        errorInfo = info,
                        nativeCode = nativeCode,
                        rawError = rawErr,
                    ),
                )
            }

            val loginJson = runCatching { JSONObject(loginResp.body.orEmpty()) }.getOrNull()
            val accessToken = loginJson?.optString("access_token", "").orEmpty()
                .ifBlank { loginJson?.optString("token", "").orEmpty() }
            if (accessToken.isBlank()) {
                return Result.failure(
                    FptnConnectionException(
                        VpnErrorInfo(
                            title = "Сервер не выдал access_token",
                            details = "Ответ /api/v1/login (HTTP 200): ${loginResp.body?.take(140)}",
                            hint = "Проверьте актуальность токена или выберите другой сервер.",
                        ),
                    ),
                )
            }

            // Skip /api/v1/dns completely when server DNS is not needed (Smart DNS / AdGuard / Custom IPv4 / Soft Reconnect)
            if (preferServerDns) {
                AppLogStore.info("VpnService", "Авторизация успешна — запрос DNS сервера (/api/v1/dns)…")
                for (dnsAttempt in 1..2) {
                    if (isShuttingDown.get()) break
                    val dnsResp = runCatching { httpsClient.get("/api/v1/dns", timeoutSec) }.getOrNull()
                    if (dnsResp != null && dnsResp.code == 200 && !dnsResp.body.isNullOrBlank()) {
                        runCatching {
                            val dnsJson = JSONObject(dnsResp.body)
                            val d4 = dnsJson.optString("dns", "").trim()
                            val d6 = dnsJson.optString("dns_ipv6", "").trim()
                            if (d4.isNotEmpty()) {
                                serverDnsIpv4 = FptnProtocolClient.resolveSafeDns(d4, OFFICIAL_TUN_IPV4)
                            }
                            if (d6.isNotEmpty()) {
                                serverDnsIpv6 = d6
                            }
                        }
                        break
                    }
                    if (dnsAttempt < 2) {
                        delay(250L)
                    }
                }
            } else {
                AppLogStore.info("VpnService", "Авторизация успешна (используется заданный DNS без запроса /api/v1/dns)")
            }

            Result.success(
                NativeLoginResult(
                    accessToken = accessToken,
                    dnsIpv4 = serverDnsIpv4,
                    dnsIpv6 = serverDnsIpv6,
                    usedSni = sniHost,
                    usedStrategy = censorshipStrategy,
                ),
            )
        } finally {
            httpsClient.release()
        }
    }

    private suspend fun openNativeWebSocket(
        server: FptnServer,
        accessToken: String,
        sniHost: String,
        censorshipStrategy: String,
        connectionStrategy: String,
        loop: TunPacketLoop,
        openTimeoutSec: Long = WS_OPEN_TIMEOUT_SEC,
    ): Result<NativeWebSocketClientImpl> {
        val generation = activeSessionGeneration.incrementAndGet()
        val openOutcome = CompletableDeferred<Boolean>()
        val wsOpened = AtomicBoolean(false)
        val wsFailed = AtomicBoolean(false)

        val nativeWs = try {
            NativeWebSocketClientImpl(
                server.host,
                server.port,
                OFFICIAL_TUN_IPV4,
                OFFICIAL_TUN_IPV6,
                accessToken,
                server.md5Fingerprint,
                sniHost,
                OFFICIAL_CLIENT_VERSION,
                censorshipStrategy,
                connectionStrategy,
                object : NativeWebSocketClientImpl.Callbacks {
                    override fun onSocketOpened(fd: Int) {
                        if (!isRootSessionActive) {
                            val ok = protect(fd)
                            if (!ok) {
                                AppLogStore.warn("NativeWS", "protect(fd=$fd) вернул false")
                            }
                        }
                    }

                    override fun onOpen() {
                        AppLogStore.info("NativeWS", "Нативный туннель FPTN ($connectionStrategy) установлен!")
                        wsOpened.set(true)
                        openOutcome.complete(true)
                    }

                    override fun onMessageReceived(packets: Array<ByteArray>) {
                        if (activeSessionGeneration.get() != generation) return
                        val batchBytes = loop.writeInboundBatch(packets)
                        if (batchBytes > 0L) {
                            nativeWsRxBytes.addAndGet(batchBytes)
                        }
                    }

                    override fun onFailure() {
                        wsFailed.set(true)
                        openOutcome.complete(false)
                        // Ignore failures from superseded WebSocket instances during soft reconnect
                        if (activeSessionGeneration.get() != generation) return
                        if (wsOpened.get() && !isShuttingDown.get()) {
                            AppLogStore.warn("NativeWS", "Нативный канал FPTN оборвался — запуск автоматического переподключения…")
                            triggerSoftReconnect("Обрыв канала сервера")
                        }
                    }
                },
            )
        } catch (ex: Exception) {
            return Result.failure(
                FptnConnectionException(
                    VpnErrorInfo(
                        title = "Сбой запуска нативного туннеля",
                        details = ex.message ?: ex.javaClass.simpleName,
                        hint = "Откройте «Журнал» для подробностей.",
                    ),
                    ex,
                ),
            )
        }

        // Swap & clean up previous WebSocket instance asynchronously on bounded daemon executor
        val previousWs = activeNativeWsRef.getAndSet(nativeWs)
        if (previousWs != null) {
            releaseNativeWsAsync(previousWs)
        }

        nativeWs.start()

        val outcome = try {
            withTimeoutOrNull(openTimeoutSec * 1000L) {
                openOutcome.await()
            }
        } catch (ce: CancellationException) {
            activeNativeWsRef.compareAndSet(nativeWs, null)
            releaseNativeWsAsync(nativeWs)
            throw ce
        }
        val signaled = outcome != null
        if (!signaled || outcome != true || wsFailed.get() || !wsOpened.get()) {
            activeNativeWsRef.compareAndSet(nativeWs, null)
            releaseNativeWsAsync(nativeWs)
            return Result.failure(
                FptnConnectionException(
                    VpnErrorInfo(
                        title = if (!signaled) "Таймаут открытия туннеля" else "Туннель отклонён сервером",
                        details = "Не удалось открыть нативный WebSocket на ${server.endpoint} ($censorshipStrategy, SNI=$sniHost, $connectionStrategy).",
                        hint = "Попробуйте «Автоподбор SNI», переключите метод на «Обфускация» или выберите другой сервер.",
                    ),
                ),
            )
        }

        return Result.success(nativeWs)
    }

    /**
     * Opens the native FPTN WebSocket tunnel and automatically falls back from `SNI-REALITY-*`
     * to universally supported `SNI` and `OBFUSCATION` strategies using the already-acquired
     * access token if the initial strategy times out or is rejected at the WebSocket upgrade stage.
     */
    private suspend fun openNativeWebSocketWithFallback(
        server: FptnServer,
        accessToken: String,
        sniHost: String,
        initialStrategy: String,
        connectionStrategy: String,
        settings: VpnSettings,
        loop: TunPacketLoop,
        openTimeoutSec: Long,
        onStageUpdate: (String) -> Unit,
    ): Result<Pair<NativeWebSocketClientImpl, String>> {
        val first = openNativeWebSocket(
            server = server,
            accessToken = accessToken,
            sniHost = sniHost,
            censorshipStrategy = initialStrategy,
            connectionStrategy = connectionStrategy,
            loop = loop,
            openTimeoutSec = openTimeoutSec,
        )
        if (first.isSuccess || !settings.autoRotateSniOnFail || isShuttingDown.get()) {
            return first.map { it to initialStrategy }
        }

        val fallbacks = buildList {
            if (initialStrategy.startsWith("SNI-REALITY", ignoreCase = true)) {
                add("SNI")
            }
            if (!initialStrategy.equals("OBFUSCATION", ignoreCase = true)) {
                add("OBFUSCATION")
            } else {
                add("SNI")
            }
        }.distinct()

        val quickWsTimeout = openTimeoutSec.coerceAtMost(8L)
        for (fbStrategy in fallbacks) {
            if (isShuttingDown.get()) break
            onStageUpdate("Резервный канал WebSocket ($fbStrategy)…")
            AppLogStore.warn(
                "SelfHeal",
                "WebSocket не открылся на $initialStrategy — повтор с $fbStrategy (SNI=$sniHost)…",
            )
            val retry = openNativeWebSocket(
                server = server,
                accessToken = accessToken,
                sniHost = sniHost,
                censorshipStrategy = fbStrategy,
                connectionStrategy = connectionStrategy,
                loop = loop,
                openTimeoutSec = quickWsTimeout,
            )
            if (retry.isSuccess) {
                AppLogStore.info("SelfHeal", "WebSocket успешно открыт на резервной стратегии $fbStrategy")
                return retry.map { it to fbStrategy }
            }
        }
        return first.map { it to initialStrategy }
    }

    /**
     * In-place soft reconnect: keeps TUN (`tun0` or Stealth `fptn0`) alive while re-establishing
     * the BoringSSL HTTPS token + WebSocket tunnel (matching official FPTN reconnect behavior + Stall Watchdog healing).
     */
    private fun triggerSoftReconnect(reason: String) {
        if (isShuttingDown.get()) return
        val server = activeServer ?: return
        val ownerProfile = activeOwnerProfile ?: return
        val loop = tunLoop ?: return
        if (!isReconnectingFlag.compareAndSet(false, true)) return

        reconnectJob?.cancel()
        reconnectJob = serviceScope.launch {
            val reconnectLockToken = powerLockCoordinator.acquire(15_000L)
            try {
                val container = (application as FptnApplication).container
                val initialSettings = container.repository.getSettings()
                updateNotificationLocale(initialSettings.appLanguage)
                if (!initialSettings.reconnectOnFailure && !reason.startsWith("Смена сети")) {
                    failAndStop(
                        server = server,
                        errorInfo = VpnErrorInfo(
                            title = "Соединение с сервером разорвано",
                            details = "$reason (${server.endpoint})",
                            hint = "Включите «Автопереподключение при обрыве» в Дополнительных настройках.",
                        ),
                    )
                    return@launch
                }

                // Tear down dead WebSocket socket asynchronously so a frozen C++ socket NEVER blocks reconnect
                activeSessionGeneration.incrementAndGet()
                activeNativeWsRef.getAndSet(null)?.let { oldWs ->
                    releaseNativeWsAsync(oldWs)
                }

                // In Root Stealth mode, refresh kernel fptn0 table 198 & route cache immediately on handover/stall
                if (isRootSessionActive) {
                    val handoverTransport = detectCurrentPhysicalTransport()
                    val refreshMtu = if (handoverTransport == NetworkPresetTrigger.MOBILE) {
                        minOf(activeSessionMtu, 1360)
                    } else {
                        activeSessionMtu
                    }
                    RootShellManager.refreshRoutingOnNetworkChange(mtu = refreshMtu)
                }

                val maxAttempts = initialSettings.maxReconnectAttempts.coerceIn(3, 50)
                val baseDelayMs = initialSettings.reconnectDelaySec.coerceIn(1, 10) * 1000L

                var lastError: VpnErrorInfo? = null
                for (attempt in 1..maxAttempts) {
                    if (!isActive || isShuttingDown.get()) return@launch

                    // Wait cleanly up to 6s if physical cellular/Wi-Fi network is mid-handover before burning an attempt
                    if (!hasActivePhysicalNetwork()) {
                        var waitedMs = 0L
                        while (waitedMs < 6_000L && isActive && !isShuttingDown.get() && !hasActivePhysicalNetwork()) {
                            delay(500L)
                            waitedMs += 500L
                        }
                        if (!isActive || isShuttingDown.get()) return@launch
                    }

                    val currentSettings = container.repository.getSettings()
                    val currentTransport = detectCurrentPhysicalTransport()
                    val sniHost = currentSettings.customSni.trim().ifBlank { VpnSettings.DEFAULT_SNI_DOMAIN }
                    val censorshipStrategy = currentSettings.effectiveCensorshipStrategyName()
                    val connectionStrategy = currentSettings.slidingTunnelMode.nativeStrategyName
                    val baseHandshakeTimeout = currentSettings.handshakeTimeoutSec.coerceIn(4, 20)
                    val handshakeTimeout = if (currentTransport == NetworkPresetTrigger.MOBILE) {
                        baseHandshakeTimeout.coerceAtLeast(10)
                    } else {
                        baseHandshakeTimeout
                    }
                    val wsTimeout = currentSettings.wsOpenTimeoutSec.coerceIn(5, 30).toLong()

                    updateNotificationLocale(currentSettings.appLanguage)
                    VpnStateHolder.updateState(
                        VpnConnectionState.Reconnecting(
                            server = server,
                            attempt = attempt,
                            maxAttempts = maxAttempts,
                            reason = reason,
                        ),
                    )
                    getSystemService(NotificationManager::class.java)?.notify(
                        NOTIFICATION_ID,
                        buildNotification(
                            title = notificationManager.getString(
                                R.string.vpn_notif_reconnecting_attempt,
                                attempt,
                                maxAttempts,
                            ),
                            subtitle = reason,
                            showReconnectAction = true,
                            reconnectServerId = server.id,
                        ),
                    )
                    // Notify the user with a high-priority push alert once a real drop persists past attempt 1
                    if (attempt == 2) {
                        showServerDropProgressAlertNotification(server = server, reason = reason)
                    }
                    AppLogStore.info(
                        "Reconnect",
                        "Попытка $attempt/$maxAttempts ($reason) | $censorshipStrategy | SNI=$sniHost | $connectionStrategy",
                    )

                    // Official WebSocketClientWrapper optimization: on first attempt, reuse cached JWT token
                    // directly to open WebSocket immediately without a redundant /api/v1/login HTTPS round-trip!
                    val reusableToken = if (attempt == 1) {
                        getReusableAccessToken(server.id, sniHost, censorshipStrategy)
                    } else {
                        null
                    }
                    if (reusableToken != null) {
                        AppLogStore.info("Reconnect", "Быстрое восстановление по кэшированному access_token…")
                        val fastWsRes = openNativeWebSocket(
                            server = server,
                            accessToken = reusableToken,
                            sniHost = sniHost,
                            censorshipStrategy = censorshipStrategy,
                            connectionStrategy = connectionStrategy,
                            loop = loop,
                            openTimeoutSec = wsTimeout.coerceAtMost(8L),
                        )
                        if (fastWsRes.isSuccess) {
                            val reconnectedWs = fastWsRes.getOrThrow()
                            val assignedIp = VpnErrorClassifier.normalizeAssignedIp(
                                reconnectedWs.getIPv4Address(),
                                OFFICIAL_TUN_IPV4,
                            )
                            lastConnectedOrReconnectedAtMillis.set(System.currentTimeMillis())
                            stallWatchdog = createWatchdogForSettings(currentSettings, currentTransport)
                            cancelDisconnectAlertNotification()
                            VpnStateHolder.updateState(
                                VpnConnectionState.Connected(
                                    server = server,
                                    isRootMode = isRootSessionActive,
                                    assignedIp = assignedIp,
                                    dnsServer = activeServerDnsIpv4,
                                    connectedAtMillis = connectedAtMillis,
                                    activeDohUrl = activeDohUrl,
                                ),
                            )
                            AppLogStore.info(
                                "Reconnect",
                                "Туннель мгновенно восстановлен без повторного логина ($reason) | IP=$assignedIp",
                            )
                            return@launch
                        } else {
                            // Token may have expired or strategy needs healing — invalidate cache & proceed to full login
                            invalidateAccessTokenCache()
                        }
                    }

                    val loginRes = performNativeLoginWithSelfHealing(
                        server = server,
                        ownerProfile = ownerProfile,
                        initialSni = sniHost,
                        initialStrategy = censorshipStrategy,
                        settings = currentSettings,
                        fallbackDnsIpv4 = activeServerDnsIpv4,
                        preferServerDns = false,
                        timeoutSec = handshakeTimeout,
                        onStageUpdate = { },
                    )
                    if (loginRes.isSuccess) {
                        val lr = loginRes.getOrThrow()
                        cacheAccessToken(lr.accessToken, server.id, lr.usedSni, lr.usedStrategy)
                        val wsRes = openNativeWebSocketWithFallback(
                            server = server,
                            accessToken = lr.accessToken,
                            sniHost = lr.usedSni,
                            initialStrategy = lr.usedStrategy,
                            connectionStrategy = connectionStrategy,
                            settings = currentSettings,
                            loop = loop,
                            openTimeoutSec = wsTimeout,
                            onStageUpdate = { },
                        )
                        if (wsRes.isSuccess) {
                            val (reconnectedWs, _) = wsRes.getOrThrow()
                            val assignedIp = VpnErrorClassifier.normalizeAssignedIp(
                                reconnectedWs.getIPv4Address(),
                                OFFICIAL_TUN_IPV4,
                            )
                            lastConnectedOrReconnectedAtMillis.set(System.currentTimeMillis())
                            stallWatchdog = createWatchdogForSettings(currentSettings, currentTransport)
                            cancelDisconnectAlertNotification()
                            VpnStateHolder.updateState(
                                VpnConnectionState.Connected(
                                    server = server,
                                    isRootMode = isRootSessionActive,
                                    assignedIp = assignedIp,
                                    dnsServer = activeServerDnsIpv4,
                                    connectedAtMillis = connectedAtMillis,
                                    activeDohUrl = activeDohUrl,
                                ),
                            )
                            AppLogStore.info(
                                "Reconnect",
                                "Туннель восстановлен с попытки #$attempt ($reason) | IP=$assignedIp",
                            )
                            return@launch
                        } else {
                            invalidateAccessTokenCache()
                            lastError = (wsRes.exceptionOrNull() as? FptnConnectionException)?.errorInfo
                        }
                    } else {
                        invalidateAccessTokenCache()
                        lastError = (loginRes.exceptionOrNull() as? FptnConnectionException)?.errorInfo
                    }

                    // Optional Auto-Failover to the next healthy node after 3 consecutive failed attempts
                    if (attempt == 3 && currentSettings.autoFailoverServer && !isShuttingDown.get()) {
                        val allServers = container.repository.getAllProfiles()
                            .filter { it.enabled }
                            .flatMap { it.servers }
                        val altServer = allServers
                            .filter { it.id != server.id }
                            .minByOrNull { it.pingMs ?: Int.MAX_VALUE }
                        if (altServer != null) {
                            AppLogStore.warn(
                                "Failover",
                                "Узел ${server.cleanName} не отвечает 3 попытки — авто-переход на резервный узел ${altServer.cleanName}…",
                            )
                            isReconnectingFlag.set(false)
                            beginConnect(altServer.id, isServerSwitch = true)
                            return@launch
                        }
                    }

                    val backoffMs = (baseDelayMs + (attempt - 1) * 1100L + (attempt % 3) * 350L).coerceAtMost(10_000L)
                    delay(backoffMs)
                }

                if (!isShuttingDown.get()) {
                    failAndStop(
                        server = server,
                        errorInfo = lastError ?: VpnErrorInfo(
                            title = "Не удалось восстановить соединение",
                            details = "Исчерпаны все $maxAttempts попыток переподключения ($reason).",
                            hint = "Проверьте интернет, смените сервер или подберите рабочий SNI.",
                        ),
                    )
                }
            } finally {
                // Only releases if no newer connect/failover attempt has taken WakeLock ownership
                powerLockCoordinator.releaseIfOwner(reconnectLockToken)
                isReconnectingFlag.set(false)
            }
        }
    }

    private fun registerNetworkWatcher() {
        unregisterNetworkWatcher()
        val cm = getSystemService(ConnectivityManager::class.java) ?: return
        activePhysicalNetworks.clear()
        activePhysicalLinkSignatures.clear()

        // Official NetworkUtils parity + Pixel/Mobile tower handover resilience:
        // track physical Wi-Fi/Cellular/Ethernet networks AND their IPv4 LinkProperties explicitly
        // (excluding TRANSPORT_VPN!) so both Wi-Fi<->Mobile AND cell-tower CGNAT IP changes heal seamlessly.
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
            .addTransportType(NetworkCapabilities.TRANSPORT_ETHERNET)
            .build()

        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                if (isShuttingDown.get()) return
                val caps = runCatching { cm.getNetworkCapabilities(network) }.getOrNull() ?: return
                handlePhysicalNetworkUpdate(network, caps)
                runCatching { cm.getLinkProperties(network) }.getOrNull()?.let { lp ->
                    val sig = extractIpv4LinkSignature(lp)
                    if (sig.isNotEmpty()) {
                        activePhysicalLinkSignatures[network] = sig
                    }
                }
            }

            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                if (isShuttingDown.get()) return
                handlePhysicalNetworkUpdate(network, caps)
            }

            override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) {
                if (isShuttingDown.get()) return
                val transport = activePhysicalNetworks[network] ?: return
                val newSig = extractIpv4LinkSignature(linkProperties)
                if (newSig.isEmpty()) return
                val prevSig = activePhysicalLinkSignatures.put(network, newSig)
                if (prevSig != null && prevSig != newSig) {
                    val preferred = resolvePreferredPhysicalTransport()
                    if (preferred == null || preferred == transport) {
                        scheduleLinkChangeRefresh(transport, prevSig, newSig)
                    }
                }
            }

            override fun onLost(network: Network) {
                activePhysicalNetworks.remove(network)
                activePhysicalLinkSignatures.remove(network)
                if (isShuttingDown.get()) return
                val remaining = resolvePreferredPhysicalTransport()
                if (remaining != null && remaining != lastTransportType) {
                    scheduleTransportChangeDebounce(remaining)
                }
            }
        }
        runCatching {
            cm.registerNetworkCallback(request, callback)
            networkCallback = callback
        }
    }

    private fun extractIpv4LinkSignature(linkProperties: LinkProperties): String {
        return linkProperties.linkAddresses
            .mapNotNull { la ->
                val addr = la.address as? Inet4Address ?: return@mapNotNull null
                if (addr.isLoopbackAddress || addr.isLinkLocalAddress) return@mapNotNull null
                addr.hostAddress
            }
            .sorted()
            .joinToString(",")
    }

    private fun scheduleLinkChangeRefresh(
        transport: NetworkPresetTrigger,
        oldIpSig: String,
        newIpSig: String,
    ) {
        linkChangeDebounceJob?.cancel()
        linkChangeDebounceJob = serviceScope.launch {
            delay(1500L)
            if (isShuttingDown.get()) return@launch
            // Skip if a full transport-change debounce is already handling Wi-Fi <-> Mobile switch
            if (networkDebounceJob?.isActive == true) return@launch
            AppLogStore.info(
                "NetWatch",
                "Смена IP / базовой станции (${transport.title}: $oldIpSig → $newIpSig) — бесшовное обновление маршрута…",
            )
            if (isRootSessionActive) {
                val refreshMtu = if (transport == NetworkPresetTrigger.MOBILE) {
                    minOf(activeSessionMtu, 1360)
                } else {
                    activeSessionMtu
                }
                RootShellManager.refreshRoutingOnNetworkChange(mtu = refreshMtu)
            } else {
                runCatching { setUnderlyingNetworks(null) }
            }
            val currentSettings = (application as FptnApplication).container.repository.getSettings()
            if (currentSettings.reconnectOnNetworkChange) {
                triggerSoftReconnect("Смена базовой станции / IP (${transport.title})")
            }
        }
    }

    private fun handlePhysicalNetworkUpdate(network: Network, caps: NetworkCapabilities) {
        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) return
        val transport = when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> NetworkPresetTrigger.WIFI
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> NetworkPresetTrigger.MOBILE
            else -> return
        }
        activePhysicalNetworks[network] = transport
        val effective = resolvePreferredPhysicalTransport() ?: transport
        val prev = lastTransportType
        if (prev == null) {
            lastTransportType = effective
            return
        }
        if (prev != effective) {
            scheduleTransportChangeDebounce(effective)
        }
    }

    private fun resolvePreferredPhysicalTransport(): NetworkPresetTrigger? {
        if (activePhysicalNetworks.isEmpty()) return null
        val values = activePhysicalNetworks.values
        return when {
            NetworkPresetTrigger.WIFI in values -> NetworkPresetTrigger.WIFI
            NetworkPresetTrigger.MOBILE in values -> NetworkPresetTrigger.MOBILE
            else -> null
        }
    }

    private fun scheduleTransportChangeDebounce(candidateTransport: NetworkPresetTrigger) {
        // Debounce brief Wi-Fi <-> 4G/5G flickers (configurable settle window) so urban towers never thrash
        linkChangeDebounceJob?.cancel()
        networkDebounceJob?.cancel()
        networkDebounceJob = serviceScope.launch {
            val container = (application as FptnApplication).container
            val debounceSec = container.repository.getSettings().networkSwitchDebounceSec.coerceIn(1, 10)
            delay(debounceSec * 1000L)
            if (isShuttingDown.get()) return@launch
            val confirmed = detectCurrentPhysicalTransport() ?: candidateTransport
            if (confirmed == lastTransportType) return@launch
            val oldTransport = lastTransportType
            lastTransportType = confirmed
            AppLogStore.info("NetWatch", "Смена физической сети: ${oldTransport?.title ?: "?"} → ${confirmed.title} (выдержка ${debounceSec}с)")
            val currentSettings = container.repository.getSettings()
            var reason = "Смена сети (${confirmed.title})"
            var presetApplied = false
            // Always honor Wi-Fi <-> Mobile auto-switch triggers when enabled, while keeping
            // every manually edited parameter untouchable via respectManualOverrides = true!
            if (currentSettings.autoSwitchPresetsByNetwork) {
                val matchingPreset = currentSettings.networkPresets.firstOrNull {
                    it.trigger == confirmed
                }
                if (matchingPreset != null) {
                    container.repository.updateSettings {
                        it.applyPreset(matchingPreset, respectManualOverrides = true)
                    }
                    reason = "Смена сети → пресет «${matchingPreset.name}»"
                    presetApplied = true
                    AppLogStore.info("Preset", "$reason (ручные настройки сохранены)")
                }
            }
            if (isRootSessionActive) {
                val handoverMtu = if (confirmed == NetworkPresetTrigger.MOBILE) {
                    minOf(activeSessionMtu, 1360)
                } else {
                    activeSessionMtu
                }
                RootShellManager.refreshRoutingOnNetworkChange(mtu = handoverMtu)
            } else {
                runCatching { setUnderlyingNetworks(null) }
            }
            if (currentSettings.reconnectOnNetworkChange || presetApplied) {
                triggerSoftReconnect(reason)
            } else {
                // Even without reconnect, adapt watchdog thresholds to the new transport
                stallWatchdog = createWatchdogForSettings(currentSettings, confirmed)
            }
        }
    }

    private fun unregisterNetworkWatcher() {
        networkDebounceJob?.cancel()
        networkDebounceJob = null
        linkChangeDebounceJob?.cancel()
        linkChangeDebounceJob = null
        activePhysicalNetworks.clear()
        activePhysicalLinkSignatures.clear()
        val cb = networkCallback ?: return
        networkCallback = null
        runCatching {
            val cm = getSystemService(ConnectivityManager::class.java)
            cm?.unregisterNetworkCallback(cb)
        }
    }

    private fun hasActivePhysicalNetwork(): Boolean {
        if (networkCallback != null && activePhysicalNetworks.isNotEmpty()) {
            return true
        }
        return runCatching {
            val cm = getSystemService(ConnectivityManager::class.java) ?: return true
            val active = cm.activeNetwork
            val activeCaps = active?.let { cm.getNetworkCapabilities(it) }
            if (activeCaps != null && !activeCaps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) {
                return activeCaps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            }
            @Suppress("DEPRECATION")
            cm.allNetworks.any { net ->
                val caps = cm.getNetworkCapabilities(net) ?: return@any false
                !caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) &&
                    caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                    (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                        caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ||
                        caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET))
            }
        }.getOrDefault(true)
    }

    private fun detectCurrentPhysicalTransport(): NetworkPresetTrigger? {
        resolvePreferredPhysicalTransport()?.let { return it }
        return runCatching {
            val cm = getSystemService(ConnectivityManager::class.java) ?: return null
            val active = cm.activeNetwork
            val activeCaps = active?.let { cm.getNetworkCapabilities(it) }
            if (activeCaps != null && !activeCaps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) {
                return when {
                    activeCaps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                        activeCaps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> NetworkPresetTrigger.WIFI
                    activeCaps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> NetworkPresetTrigger.MOBILE
                    else -> null
                }
            }
            var sawCellular = false
            @Suppress("DEPRECATION")
            for (net in cm.allNetworks) {
                val caps = cm.getNetworkCapabilities(net) ?: continue
                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) continue
                if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) continue
                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
                ) {
                    return NetworkPresetTrigger.WIFI
                }
                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) {
                    sawCellular = true
                }
            }
            if (sawCellular) NetworkPresetTrigger.MOBILE else null
        }.getOrNull()
    }

    private fun failAndStop(server: FptnServer?, errorInfo: VpnErrorInfo) {
        AppLogStore.error("VpnService", "${errorInfo.title}: ${errorInfo.details}")
        VpnStateHolder.updateState(
            VpnConnectionState.Error(
                message = errorInfo.details,
                server = server,
                errorInfo = errorInfo,
            ),
        )
        showDisconnectAlertNotification(server = server, errorInfo = errorInfo)
        stopTunnelAndService(errorInfo.details, preserveError = true)
    }

    private fun establishTunInterface(
        server: FptnServer,
        settings: VpnSettings,
        effectiveMtu: Int,
        currentTransport: NetworkPresetTrigger?,
        clientTunIp: String,
        dnsServer: String,
        fallbackServerDnsIpv4: String? = null,
        dnsServerIpv6: String?,
        resolvedServerIpv4: String?,
        protectedPackages: Set<String>,
        extraDirectIpv4s: Set<String> = emptySet(),
        extraVpnIpv4s: Set<String> = emptySet(),
        allowOverrideRu: Boolean = false,
    ): ParcelFileDescriptor? {
        val builder = Builder()
            .setSession("FPTN • ${server.cleanName}")
            .setMtu(effectiveMtu.coerceIn(1280, 1500))
            .setBlocking(true)
            .addAddress(clientTunIp, 32)
            .addDnsServer(dnsServer)

        // Official FptnConnection.java:265 parity: setMetered(false) so Google Play Services,
        // Play Store, Telegram, and media streams are NEVER throttled or stalled on mobile VPN!
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            builder.setMetered(false)
        }

        val configuredDnsIpv4s = linkedSetOf(dnsServer)
        when (settings.dnsMode) {
            DnsMode.ADBLOCK_DNS -> {
                // AdGuard Secondary IPv4 DNS routed strictly inside the VPN tunnel
                val adguardSecondary = "94.140.15.15"
                if (adguardSecondary != dnsServer) {
                    builder.addDnsServer(adguardSecondary)
                    configuredDnsIpv4s.add(adguardSecondary)
                }
            }
            DnsMode.CUSTOM_IPV4 -> {
                val secondaryDns = FptnProtocolClient.resolveSafeDns(settings.dnsSecondary, clientTunIp)
                if (secondaryDns != dnsServer) {
                    builder.addDnsServer(secondaryDns)
                    configuredDnsIpv4s.add(secondaryDns)
                }
                val serverFallback = fallbackServerDnsIpv4?.takeIf { it.isNotBlank() && it !in configuredDnsIpv4s }
                if (serverFallback != null) {
                    builder.addDnsServer(serverFallback)
                    configuredDnsIpv4s.add(serverFallback)
                }
            }
            DnsMode.AUTO_VPN, DnsMode.SMART_DNS_DOH -> Unit
        }

        val activeRegion = SmartRfRouter.effectiveRegion(
            smartRfEnabled = true,
            bypassRegion = settings.bypassRegion,
            allowOverrideRu = false,
        )
        val regionBypassCidrs = (SmartRfRouter.russianBypassCidrs + SmartRfRouter.cidrsForRegion(activeRegion)).distinct()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            builder.addRoute("0.0.0.0", 0)
            // Official FptnConnection parity: ALWAYS exclude reserved IPv4 subnets (10.10.0.0/16 TUN,
            // 172.16.0.0/12 FPTN server NAT, 192.168.0.0/16 LAN/router, loopback, link-local, multicast).
            for (reserved in SmartRfRouter.officialReservedIpv4Cidrs) {
                runCatching {
                    val addr = InetAddress.getByName(reserved.addressString())
                    builder.excludeRoute(IpPrefix(addr, reserved.prefixLength))
                }
            }
            for (cidr in regionBypassCidrs) {
                runCatching {
                    val addr = InetAddress.getByName(cidr.addressString())
                    builder.excludeRoute(IpPrefix(addr, cidr.prefixLength))
                }
            }
            for (directIp in extraDirectIpv4s) {
                if (!SmartRfRouter.isTelegramIp(directIp) && directIp != resolvedServerIpv4) {
                    runCatching {
                        builder.excludeRoute(IpPrefix(InetAddress.getByName(directIp), 32))
                    }
                }
            }
            if (!resolvedServerIpv4.isNullOrBlank()) {
                runCatching {
                    builder.excludeRoute(IpPrefix(InetAddress.getByName(resolvedServerIpv4), 32))
                }
            }
            // Official FptnConnection parity: ALWAYS add explicit /32 route for each VPN DNS server
            // AFTER excluding 172.16.0.0/12 & 10.10.0.0/16 so internal FPTN server DNS stays reachable!
            for (dnsIp in configuredDnsIpv4s) {
                if (dnsIp != resolvedServerIpv4) {
                    runCatching { builder.addRoute(dnsIp, 32) }
                }
            }
            for (tg in SmartRfRouter.telegramDcCidrs) {
                builder.addRoute(tg.addressString(), tg.prefixLength)
            }
            for (vpnIp in extraVpnIpv4s) {
                if (vpnIp != resolvedServerIpv4) {
                    val ipLong = com.example.domain.routing.CidrBlock.parseIpv4(vpnIp)
                    val blockedGeo = !allowOverrideRu &&
                        ipLong != null &&
                        !SmartRfRouter.isTelegramIp(vpnIp) &&
                        regionBypassCidrs.any { it.contains(ipLong) }
                    if (!blockedGeo) {
                        runCatching { builder.addRoute(vpnIp, 32) }
                    }
                }
            }
        } else {
            val routes = SmartRfRouter.computeEffectiveVpnRoutes(
                smartRfEnabled = settings.smartRfRouting,
                vpnServerIpv4 = resolvedServerIpv4,
                bypassRegion = settings.bypassRegion,
                extraDirectIpv4s = extraDirectIpv4s,
                extraVpnIpv4s = extraVpnIpv4s,
                allowOverrideRu = allowOverrideRu,
                vpnDnsIpv4s = configuredDnsIpv4s,
            )
            for (route in routes) {
                builder.addRoute(route.addressString(), route.prefixLength)
            }
        }

        // ALWAYS capture ::/0 inside TUN to prevent IPv6 leaks (e.g., YouTube QUIC bypassing VPN straight into ISP/TSPU!),
        // while excluding official reserved IPv6 subnets (fd00::/64, fc00:1::/64, fe80::/10) on API 33+ and adding
        // an explicit /128 route for the server's IPv6 DNS when IPv6 is enabled.
        runCatching {
            builder.addAddress(OFFICIAL_TUN_IPV6, 128)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                for ((v6Addr, v6Prefix) in SmartRfRouter.officialReservedIpv6Cidrs) {
                    runCatching {
                        builder.excludeRoute(IpPrefix(InetAddress.getByName(v6Addr), v6Prefix))
                    }
                }
            }
            builder.addRoute("::", 0)
            if (settings.allowIpv6 && !dnsServerIpv6.isNullOrBlank()) {
                builder.addDnsServer(dnsServerIpv6)
                runCatching { builder.addRoute(dnsServerIpv6, 128) }
            }
        }

        val mandatoryBypass = protectedPackages + packageName
        when (settings.splitTunnelMode) {
            SplitTunnelMode.ALL_APPS -> {
                for (pkg in mandatoryBypass) {
                    runCatching { builder.addDisallowedApplication(pkg) }
                }
            }
            SplitTunnelMode.EXCLUDE_SELECTED -> {
                for (pkg in mandatoryBypass + settings.selectedPackages) {
                    runCatching { builder.addDisallowedApplication(pkg) }
                }
            }
            SplitTunnelMode.INCLUDE_SELECTED -> {
                val allowed = settings.selectedPackages.filterNot { it in mandatoryBypass }
                if (allowed.isNotEmpty()) {
                    // Official FptnConnection parity: implicitly include Google companion services
                    // so YouTube/Gmail/Google apps don't break in allowed-only mode.
                    for (gmsPkg in GOOGLE_SERVICE_PACKAGES) {
                        if (gmsPkg !in mandatoryBypass) {
                            runCatching { builder.addAllowedApplication(gmsPkg) }
                        }
                    }
                    for (pkg in allowed) {
                        runCatching { builder.addAllowedApplication(pkg) }
                    }
                } else {
                    for (pkg in mandatoryBypass) {
                        runCatching { builder.addDisallowedApplication(pkg) }
                    }
                }
            }
        }

        return builder.establish()
    }

    private fun resolveExcludedUids(settings: VpnSettings, protectedPackages: Set<String>): Set<Int> {
        val pkgs = buildSet {
            addAll(protectedPackages)
            if (settings.splitTunnelMode == SplitTunnelMode.EXCLUDE_SELECTED) {
                addAll(settings.selectedPackages)
            }
        }
        return pkgs.mapNotNull { pkg ->
            runCatching { packageManager.getApplicationInfo(pkg, 0).uid }.getOrNull()
        }.toSet()
    }

    /**
     * Resolves Android NetworkStack / CaptivePortalLogin UIDs (including AOSP AID_NETWORK_STACK=1073)
     * so Pixel/Android connectivity probes always reach connectivitycheck.gstatic.com cleanly and
     * never display a false "!" (No Internet) badge in Root Stealth mode.
     */
    private fun resolveSystemConnectivityBypassUids(): Set<Int> {
        val uids = LinkedHashSet<Int>(SafeRootRulePlanner.DEFAULT_SYSTEM_BYPASS_UIDS)
        val systemNetPkgs = listOf(
            "com.google.android.networkstack",
            "com.android.networkstack",
            "com.google.android.captiveportallogin",
            "com.android.captiveportallogin",
            "com.google.android.networkstack.tethering",
        )
        for (pkg in systemNetPkgs) {
            runCatching { packageManager.getApplicationInfo(pkg, 0).uid }.getOrNull()?.let { uid ->
                if (uid > 0) uids.add(uid)
            }
        }
        return uids
    }

    private fun sendPacketToActiveWs(packet: ByteArray, len: Int, countAsTraffic: Boolean = true) {
        val ws = activeNativeWsRef.get()
        if (ws == null) {
            if (countAsTraffic && !isReconnectingFlag.get()) {
                nativeWsSendFailures.incrementAndGet()
            }
            return
        }
        val sent = ws.send(packet, len.toLong())
        if (sent) {
            if (countAsTraffic) {
                nativeWsTxBytes.addAndGet(len.toLong())
                nativeWsTxPackets.incrementAndGet()
            }
        } else if (countAsTraffic && !isReconnectingFlag.get()) {
            nativeWsSendFailures.incrementAndGet()
        }
    }

    private fun releaseNativeWsAsync(ws: NativeWebSocketClientImpl) {
        wsCleanupExecutor.execute {
            runCatching { ws.stop() }
            runCatching { ws.release() }
        }
    }

    private fun startTelemetryAndWatchdogLoop(server: FptnServer, initialSettings: VpnSettings) {
        telemetryJob?.cancel()
        telemetryJob = serviceScope.launch {
            val container = (application as FptnApplication).container
            var prevRx = 0L
            var prevTx = 0L
            var prevWsRx = nativeWsRxBytes.get()
            var prevWsTx = nativeWsTxBytes.get()
            var prevWsTxPkts = nativeWsTxPackets.get()
            var prevWsFail = nativeWsSendFailures.get()
            var lastSensitivity = initialSettings.watchdogSensitivity
            var lastProbeSec = initialSettings.watchdogProbeSec
            var lastStallSec = initialSettings.watchdogStallSec
            var lastCooldownSec = initialSettings.watchdogCooldownSec
            var idleSecondsWithoutWsTraffic = 0
            var tickCounter = 0
            var liveSettings = initialSettings

            while (isActive && !isShuttingDown.get()) {
                delay(1000L)
                tickCounter++
                if (tickCounter % 3 == 0) {
                    liveSettings = container.repository.getSettings()
                    if (
                        liveSettings.watchdogSensitivity != lastSensitivity ||
                        liveSettings.watchdogProbeSec != lastProbeSec ||
                        liveSettings.watchdogStallSec != lastStallSec ||
                        liveSettings.watchdogCooldownSec != lastCooldownSec
                    ) {
                        lastSensitivity = liveSettings.watchdogSensitivity
                        lastProbeSec = liveSettings.watchdogProbeSec
                        lastStallSec = liveSettings.watchdogStallSec
                        lastCooldownSec = liveSettings.watchdogCooldownSec
                        stallWatchdog = createWatchdogForSettings(liveSettings)
                    }
                }

                // Zero-su lightweight check on kernel TUN interface (avoids Magisk superuser toast spam!).
                // Only invoke live su verification if the stealth interface unexpectedly disappeared.
                if (isRootSessionActive && tickCounter % 15 == 0) {
                    val activeTun = RootShellManager.currentActiveTunName()
                    val tunExists = java.io.File("/sys/class/net/$activeTun").exists()
                    if (!tunExists && !RootShellManager.verifyRootNow()) {
                        container.repository.updateSettings {
                            it.copy(
                                useRootMode = false,
                                unlockProtectedRuWithRoot = false,
                                smartRfRouting = true,
                                bypassRegion = BypassRegion.sanitize(it.bypassRegion),
                            )
                        }
                        failAndStop(
                            server = server,
                            errorInfo = VpnErrorInfo(
                                title = "Root-доступ отозван",
                                details = "Скрытый интерфейс $activeTun закрылся, а права Root недоступны в Magisk/KernelSU.",
                                hint = "Скрытый Root-режим автоматически отключён. Можно переподключиться в обычном VPN-режиме.",
                            ),
                        )
                        return@launch
                    }
                }

                val (rx, tx) = tunLoop?.snapshotCounters() ?: break
                val downBps = (rx - prevRx).coerceAtLeast(0L)
                val upBps = (tx - prevTx).coerceAtLeast(0L)
                prevRx = rx
                prevTx = tx

                val curWsRx = nativeWsRxBytes.get()
                val curWsTx = nativeWsTxBytes.get()
                val curWsTxPkts = nativeWsTxPackets.get()
                val curWsFail = nativeWsSendFailures.get()
                val wsRxDelta = (curWsRx - prevWsRx).coerceAtLeast(0L)
                val wsTxDelta = (curWsTx - prevWsTx).coerceAtLeast(0L)
                val wsTxPktsDelta = (curWsTxPkts - prevWsTxPkts).coerceAtLeast(0L).toInt()
                val wsFailDelta = (curWsFail - prevWsFail).coerceAtLeast(0L).toInt()
                prevWsRx = curWsRx
                prevWsTx = curWsTx
                prevWsTxPkts = curWsTxPkts
                prevWsFail = curWsFail

                VpnStateHolder.recordTrafficSample(
                    downloadBytesPerSec = downBps,
                    uploadBytesPerSec = upBps,
                    totalDownloadBytes = rx,
                    totalUploadBytes = tx,
                )

                val wsAlive = activeNativeWsRef.get()?.isStarted() == true
                val physicalOnline = hasActivePhysicalNetwork()
                val isConnectedState = !isReconnectingFlag.get() &&
                    VpnStateHolder.connectionState.value is VpnConnectionState.Connected

                // Periodic CGNAT Keepalive: prevents urban mobile operators (MTS/MegaFon/Beeline/T2)
                // from silently reaping idle WebSocket TCP NAT mappings during 15–30s quiet gaps.
                if (wsRxDelta > 0L || wsTxDelta > 0L) {
                    idleSecondsWithoutWsTraffic = 0
                } else if (isConnectedState && wsAlive && physicalOnline) {
                    idleSecondsWithoutWsTraffic++
                    val kaInterval = liveSettings.keepaliveIntervalSec
                    if (kaInterval > 0 && idleSecondsWithoutWsTraffic >= kaInterval) {
                        idleSecondsWithoutWsTraffic = 0
                        val seq = icmpProbeSeq.getAndIncrement().toInt() and 0xFFFF
                        val kaDest = selectProbeTargetIpv4(seq)
                        val dnsKa = StallWatchdogEvaluator.buildIpv4UdpDnsKeepalivePacket(
                            sourceIpv4 = OFFICIAL_TUN_IPV4,
                            destIpv4 = kaDest,
                            txId = seq,
                        )
                        if (dnsKa != null) {
                            // countAsTraffic = false so keepalive itself never trips stall watchdog
                            sendPacketToActiveWs(dnsKa, dnsKa.size, countAsTraffic = false)
                        }
                    }
                }

                // Active Stall Watchdog strictly on real Native WebSocket traffic (immune to local DoH noise)
                // Respect a generous post-connect settle grace window (10s on mobile LTE, 6s on Wi-Fi).
                val settleWindowMs = if (lastTransportType == NetworkPresetTrigger.MOBILE) 10_000L else 6_000L
                val settledAfterConnect =
                    (System.currentTimeMillis() - lastConnectedOrReconnectedAtMillis.get()) >= settleWindowMs
                if (settledAfterConnect && liveSettings.enableStallWatchdog && isConnectedState) {
                    when (
                        stallWatchdog.evaluateTick(
                            tunnelRxDeltaBytes = wsRxDelta,
                            tunnelTxDeltaBytes = wsTxDelta,
                            tunnelTxDeltaPackets = wsTxPktsDelta,
                            sendFailureDelta = wsFailDelta,
                            wsAlive = wsAlive,
                            hasPhysicalNetwork = physicalOnline,
                        )
                    ) {
                        com.example.domain.vpn.WatchdogAction.SEND_PROBE -> {
                            // Send BOTH UDP:53 DNS keepalive (to server DNS / 1.1.1.1) AND ICMP Echo so
                            // blocked ICMP on exit nodes/carriers never causes false disconnects!
                            val seq = icmpProbeSeq.getAndIncrement().toInt() and 0xFFFF
                            val probeDest = selectProbeTargetIpv4(seq)
                            val icmpPkt = StallWatchdogEvaluator.buildIpv4IcmpEchoRequest(
                                sourceIpv4 = OFFICIAL_TUN_IPV4,
                                destIpv4 = probeDest,
                                sequence = seq,
                            )
                            val dnsPkt = StallWatchdogEvaluator.buildIpv4UdpDnsKeepalivePacket(
                                sourceIpv4 = OFFICIAL_TUN_IPV4,
                                destIpv4 = probeDest,
                                txId = seq,
                            )
                            AppLogStore.info("Watchdog", "Нет входящего трафика — отправка UDP:53 + ICMP пробы ($probeDest)…")
                            if (dnsPkt != null) {
                                sendPacketToActiveWs(dnsPkt, dnsPkt.size, countAsTraffic = false)
                            }
                            if (icmpPkt != null) {
                                sendPacketToActiveWs(icmpPkt, icmpPkt.size, countAsTraffic = false)
                            }
                        }
                        com.example.domain.vpn.WatchdogAction.RECONNECT -> {
                            val reason = if (!wsAlive) {
                                "Канал FPTN остановился (Watchdog)"
                            } else {
                                "Зависание сессии без скорости (Watchdog)"
                            }
                            AppLogStore.warn(
                                "Watchdog",
                                "$reason — мягкий перезапуск WebSocket-сессии без сброса TUN…",
                            )
                            triggerSoftReconnect(reason)
                        }
                        com.example.domain.vpn.WatchdogAction.NONE -> Unit
                    }
                }

                if (liveSettings.showTrafficInNotification && !isReconnectingFlag.get()) {
                    val speedText = "↓ ${formatRate(downBps)} • ↑ ${formatRate(upBps)}"
                    val totalText = "Всего: ↓ ${formatBytes(rx)} • ↑ ${formatBytes(tx)}"
                    val text = "$speedText | $totalText"
                    val nm = getSystemService(NotificationManager::class.java)
                    val titlePrefix = if (isRootSessionActive) "Stealth Root" else "Защищено"
                    nm?.notify(NOTIFICATION_ID, buildNotification("$titlePrefix • ${server.cleanDisplayTitle}", text))
                }
            }
        }
    }

    private fun stopTunnelAndService(
        reason: String,
        preserveError: Boolean,
        asyncCleanup: Boolean = true,
    ) {
        if (!isShuttingDown.compareAndSet(false, true)) {
            if (!preserveError &&
                VpnStateHolder.connectionState.value !is VpnConnectionState.Error &&
                VpnStateHolder.connectionState.value !is VpnConnectionState.Disconnecting
            ) {
                VpnStateHolder.updateState(VpnConnectionState.Disconnected)
            }
            return
        }

        val keepError = preserveError || VpnStateHolder.connectionState.value is VpnConnectionState.Error
        if (!keepError) {
            VpnStateHolder.updateState(VpnConnectionState.Disconnecting("Отключение туннеля…"))
        }

        unregisterNetworkWatcher()
        activeSessionGeneration.incrementAndGet()

        // Cancel coroutine loops immediately on the caller thread (non-blocking)
        telemetryJob?.cancel()
        telemetryJob = null
        reconnectJob?.cancel()
        reconnectJob = null
        connectJob?.cancel()
        connectJob = null
        isReconnectingFlag.set(false)
        stallWatchdog.reset()

        val wasRoot = isRootSessionActive || RootShellManager.areRulesActive.value
        isRootSessionActive = false
        val localDoh = dohResolver
        dohResolver = null
        val localTun = tunLoop
        tunLoop = null
        val localWs = activeNativeWsRef.getAndSet(null)
        invalidateAccessTokenCache()
        activeServer = null
        activeOwnerProfile = null

        val performBlockingTeardown = {
            // STEP 0: Stop adaptive root resource boost & restore normal worker priority first
            runCatching { AdaptiveRootBoostController.stop() }

            // STEP 1: Remove iptables / policy routing rules FIRST so normal internet restores immediately
            if (wasRoot) {
                RootShellManager.cleanupRulesOnlySynchronously()
            }

            // STEP 2: Close Smart DNS pool & STOP TunPacketLoop so kernel TUN FD closes completely
            runCatching { localDoh?.close() }
            runCatching { localTun?.stop() }

            // STEP 3: Now that TUN FD is closed, destroy any residual kernel fptn0 interface cleanly (prevents EBUSY=16!)
            if (wasRoot) {
                RootShellManager.cleanupTunOnlySynchronously()
            }

            // STEP 4: Stop & release official native WebSocket engine cleanly (non-blocking)
            localWs?.let { ws -> releaseNativeWsAsync(ws) }

            powerLockCoordinator.forceRelease()

            AppLogStore.info("VpnService", "Туннель остановлен: $reason")
            if (!keepError) {
                VpnStateHolder.updateState(VpnConnectionState.Disconnected)
            }
            runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
            runCatching { stopSelf() }
        }

        if (asyncCleanup) {
            // Run blocking shell/TUN teardown off the Main thread on an independent executor
            // so serviceScope cancellation in onDestroy() never aborts iptables / fptn0 cleanup!
            shutdownJob = serviceScope.launch(Dispatchers.IO) {
                performBlockingTeardown()
            }
        } else {
            // Even in onDestroy(), never run blocking root shell/TUN teardown synchronously on Main Thread (ANR risk)
            teardownExecutor.execute {
                performBlockingTeardown()
            }
        }
    }

    private fun cacheAccessToken(token: String, serverId: Long, sni: String, strategy: String) {
        cachedAccessToken = token
        cachedTokenServerId = serverId
        cachedTokenSni = sni
        cachedTokenStrategy = strategy
    }

    private fun getReusableAccessToken(serverId: Long, sni: String, strategy: String): String? {
        val token = cachedAccessToken ?: return null
        return if (
            token.isNotBlank() &&
            cachedTokenServerId == serverId &&
            cachedTokenSni.equals(sni, ignoreCase = true) &&
            cachedTokenStrategy.equals(strategy, ignoreCase = true)
        ) {
            token
        } else {
            null
        }
    }

    private fun invalidateAccessTokenCache() {
        cachedAccessToken = null
        cachedTokenServerId = -1L
        cachedTokenSni = ""
        cachedTokenStrategy = ""
    }

    private fun selectProbeTargetIpv4(seq: Int): String {
        val serverDns = activeServerDnsIpv4.trim()
        val hasValidServerDns = serverDns.isNotEmpty() &&
            serverDns != OFFICIAL_TUN_IPV4 &&
            com.example.domain.routing.CidrBlock.parseIpv4(serverDns) != null
        return when {
            seq % 2 == 0 && hasValidServerDns -> serverDns
            seq % 3 == 0 -> "8.8.8.8"
            else -> "1.1.1.1"
        }
    }

    // Power lock ownership is delegated to PowerLockCoordinator above.

    override fun onRevoke() {
        stopTunnelAndService("Системный отзыв VPN-разрешения", preserveError = false)
        super.onRevoke()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Official FptnService parity: keep active foreground VPN tunnel running smoothly when
        // the user swipes the app UI away from Recents!
        AppLogStore.info("VpnService", "Окно приложения закрыто из недавних — VPN-туннель продолжает работу в фоне")
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        val isError = VpnStateHolder.connectionState.value is VpnConnectionState.Error
        stopTunnelAndService("Завершение сервиса", preserveError = isError, asyncCleanup = false)
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun startForegroundSafe(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun ensureNotificationChannel() {
        notificationManager.ensureChannels()
    }

    private fun buildNotification(
        title: String,
        subtitle: String?,
        showReconnectAction: Boolean = false,
        reconnectServerId: Long? = null,
    ): Notification = notificationManager.buildStatusNotification(
        title = title,
        subtitle = subtitle,
        showReconnectAction = showReconnectAction,
        reconnectServerId = reconnectServerId,
    )

    private fun showServerDropProgressAlertNotification(server: FptnServer, reason: String) {
        notificationManager.showServerDropProgressAlert(server, reason)
    }

    private fun showDisconnectAlertNotification(server: FptnServer?, errorInfo: VpnErrorInfo) {
        notificationManager.showDisconnectAlert(server, errorInfo)
    }

    private fun cancelDisconnectAlertNotification() {
        notificationManager.cancelDisconnectAlert()
    }

    private fun formatRate(bytesPerSec: Long): String = when {
        bytesPerSec >= 1_048_576L -> "%.1f МБ/с".format(bytesPerSec / 1_048_576f)
        bytesPerSec >= 1_024L -> "%.1f КБ/с".format(bytesPerSec / 1_024f)
        else -> "$bytesPerSec Б/с"
    }

    private fun formatBytes(bytes: Long): String = when {
        bytes >= 1_073_741_824L -> "%.2f ГБ".format(bytes / 1_073_741_824f)
        bytes >= 1_048_576L -> "%.1f МБ".format(bytes / 1_048_576f)
        bytes >= 1_024L -> "%.0f КБ".format(bytes / 1_024f)
        else -> "$bytes Б"
    }

    private fun resolvePhysicalNetwork(): Network? {
        return runCatching {
            val cm = getSystemService(ConnectivityManager::class.java) ?: return null
            // Prefer non-VPN active physical network (Wi-Fi or Cellular) so direct DNS/pre-resolution
            // always sees real Russian/local GeoDNS IPv4s even during live reconnects.
            val knownPhysical = activePhysicalNetworks.keys.firstOrNull { net ->
                val caps = cm.getNetworkCapabilities(net)
                caps != null &&
                    caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                    !caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
            }
            if (knownPhysical != null) return knownPhysical

            val active = cm.activeNetwork
            val activeCaps = active?.let { cm.getNetworkCapabilities(it) }
            if (active != null && activeCaps != null && !activeCaps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) {
                return active
            }
            cm.allNetworks.firstOrNull { net ->
                val caps = cm.getNetworkCapabilities(net)
                caps != null &&
                    caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                    !caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
            }
        }.getOrNull()
    }

    private fun resolvePhysicalDnsIpv4s(physicalNetwork: Network?): List<String> {
        val result = linkedSetOf<String>()
        runCatching {
            val cm = getSystemService(ConnectivityManager::class.java)
            val lp = physicalNetwork?.let { cm?.getLinkProperties(it) }
            lp?.dnsServers?.forEach { addr ->
                if (addr is java.net.Inet4Address) {
                    val host = addr.hostAddress
                    if (!host.isNullOrBlank() && !addr.isLoopbackAddress) {
                        result.add(host)
                    }
                }
            }
        }
        // Reliable domestic & global fallback DNS resolvers for direct Split-DNS queries
        result.add("77.88.8.8")
        result.add("1.1.1.1")
        return result.toList()
    }

    companion object {
        const val ACTION_CONNECT = "org.fptnflow.vpn.ACTION_CONNECT"
        const val ACTION_RECONNECT = "org.fptnflow.vpn.ACTION_RECONNECT"
        const val ACTION_DISCONNECT = "org.fptnflow.vpn.ACTION_DISCONNECT"
        const val EXTRA_SERVER_ID = "extra_server_id"
        const val EXTRA_IS_SERVER_SWITCH = "extra_is_server_switch"
        private const val CHANNEL_ID = "fptn_vpn_channel"
        private const val ALERT_CHANNEL_ID = "fptn_vpn_alerts"
        private const val NOTIFICATION_ID = 4650
        private const val ALERT_NOTIFICATION_ID = 4651

        private const val OFFICIAL_CLIENT_VERSION = "1.3.28.0"
        private const val OFFICIAL_TUN_IPV4 = "10.10.0.1"
        private const val OFFICIAL_TUN_IPV6 = "fd00::1"
        private const val OFFICIAL_MTU = 1400
        private const val HANDSHAKE_TIMEOUT_SEC = 8
        private const val WS_OPEN_TIMEOUT_SEC = 15L
        private const val MAX_TRANSIENT_LOGIN_RETRIES = 3
        private const val MAX_CONCURRENT_DOH_PACKETS = 32
        private const val WAKE_LOCK_TAG = "FptnVpnService::POWER_LOCK"

        private val wsCleanupExecutor = Executors.newSingleThreadExecutor { r ->
            Thread(r, "FptnWsCleanup").apply { isDaemon = true }
        }
        private val teardownExecutor = Executors.newSingleThreadExecutor { r ->
            Thread(r, "FptnVpnTeardown").apply { isDaemon = true }
        }

        private val GOOGLE_SERVICE_PACKAGES = listOf(
            "com.google.android.gms",
            "com.google.android.gsf",
            "com.android.vending",
        )

        fun startConnect(context: Context, serverId: Long?, isServerSwitch: Boolean = false) {
            val intent = Intent(context, FptnVpnService::class.java).apply {
                action = ACTION_CONNECT
                if (serverId != null) putExtra(EXTRA_SERVER_ID, serverId)
                putExtra(EXTRA_IS_SERVER_SWITCH, isServerSwitch)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stopDisconnect(context: Context) {
            val intent = Intent(context, FptnVpnService::class.java).setAction(ACTION_DISCONNECT)
            runCatching {
                context.startService(intent)
            }.onFailure { ex ->
                AppLogStore.warn("VpnService", "Не удалось отправить ACTION_DISCONNECT напрямую: ${ex.message}")
            }
        }
    }
}
