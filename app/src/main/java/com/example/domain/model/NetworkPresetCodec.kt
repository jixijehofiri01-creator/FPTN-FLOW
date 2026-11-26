package com.example.domain.model

import com.example.domain.vpn.WatchdogSensitivity
import org.json.JSONArray
import org.json.JSONObject

object NetworkPresetCodec {

    fun encode(presets: List<NetworkPreset>): String {
        val arr = JSONArray()
        presets.forEach { preset ->
            arr.put(
                JSONObject().apply {
                    put("id", preset.id)
                    put("name", preset.name)
                    put("trigger", preset.trigger.name)
                    put("override_bypass", preset.overrideBypass)
                    put("bypass_method", preset.bypassMethod.name)
                    put("sni_spoofing_mode", preset.sniSpoofingMode.name)
                    put("custom_sni", preset.customSni)
                    put("override_sliding_tunnel", preset.overrideSlidingTunnel)
                    put("sliding_tunnel_mode", preset.slidingTunnelMode.name)
                    if (preset.dnsModeOverride != null) {
                        put("dns_mode_override", preset.dnsModeOverride.name)
                    }
                    put("enable_custom_dns_url", preset.enableCustomDnsUrl)
                    put("custom_dns_url", preset.customDnsUrl)
                    put("dns_primary_override", preset.dnsPrimaryOverride)
                    put("dns_secondary_override", preset.dnsSecondaryOverride)
                    put("override_watchdog", preset.overrideWatchdog)
                    put("enable_stall_watchdog", preset.enableStallWatchdog)
                    if (preset.watchdogSensitivity != null) {
                        put("watchdog_sensitivity", preset.watchdogSensitivity.name)
                    }
                    put("watchdog_probe_sec", preset.watchdogProbeSec)
                    put("watchdog_stall_sec", preset.watchdogStallSec)
                    put("watchdog_cooldown_sec", preset.watchdogCooldownSec)
                    put("override_reconnect", preset.overrideReconnect)
                    put("reconnect_on_failure", preset.reconnectOnFailure)
                    put("reconnect_on_network_change", preset.reconnectOnNetworkChange)
                    put("max_reconnect_attempts", preset.maxReconnectAttempts)
                    put("reconnect_delay_sec", preset.reconnectDelaySec)
                    put("handshake_timeout_sec", preset.handshakeTimeoutSec)
                    put("ws_open_timeout_sec", preset.wsOpenTimeoutSec)
                    if (preset.keepaliveIntervalSecOverride != null) {
                        put("keepalive_interval_sec_override", preset.keepaliveIntervalSecOverride)
                    }
                    if (preset.networkSwitchDebounceSecOverride != null) {
                        put("network_switch_debounce_sec_override", preset.networkSwitchDebounceSecOverride)
                    }
                    if (preset.autoRotateSniOnFail != null) {
                        put("auto_rotate_sni_on_fail", preset.autoRotateSniOnFail)
                    }
                    if (preset.autoFailoverServer != null) {
                        put("auto_failover_server", preset.autoFailoverServer)
                    }
                    put("override_mtu", preset.overrideMtu)
                    put("tun_mtu", preset.tunMtu)
                    put("smart_rf_routing", preset.smartRfRouting)
                    if (preset.smartRfRoutingOverride != null) {
                        put("smart_rf_routing_override", preset.smartRfRoutingOverride)
                    }
                    if (preset.bypassRegionOverride != null) {
                        put("bypass_region_override", preset.bypassRegionOverride.name)
                    }
                    if (preset.splitTunnelModeOverride != null) {
                        put("split_tunnel_mode_override", preset.splitTunnelModeOverride.name)
                    }
                    if (preset.autoPingIntervalSecOverride != null) {
                        put("auto_ping_interval_sec_override", preset.autoPingIntervalSecOverride)
                    }
                    if (preset.serverSortOrderOverride != null) {
                        put("server_sort_order_override", preset.serverSortOrderOverride.name)
                    }
                    if (preset.allowIpv6Override != null) {
                        put("allow_ipv6_override", preset.allowIpv6Override)
                    }
                    if (preset.useRootModeOverride != null) {
                        put("use_root_mode_override", preset.useRootModeOverride)
                    }
                    if (preset.preferredServerId != null) {
                        put("preferred_server_id", preset.preferredServerId)
                    }
                    put("force_override_manual_locks", preset.forceOverrideManualLocks)
                    put("is_built_in", preset.isBuiltIn)
                },
            )
        }
        return arr.toString()
    }

    fun decode(json: String?): List<NetworkPreset>? {
        if (json.isNullOrBlank()) return null
        return runCatching {
            val arr = JSONArray(json)
            buildList {
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val id = o.optString("id", "").trim()
                    val name = o.optString("name", "").trim()
                    if (id.isEmpty() || name.isEmpty()) continue
                    val trigger = NetworkPresetTrigger.entries.firstOrNull {
                        it.name == o.optString("trigger")
                    } ?: NetworkPresetTrigger.MANUAL
                    val bypass = BypassMethod.entries.firstOrNull {
                        it.name == o.optString("bypass_method")
                    } ?: BypassMethod.SNI_REALITY
                    val spoofing = SniSpoofingMode.entries.firstOrNull {
                        it.name == o.optString("sni_spoofing_mode")
                    } ?: SniSpoofingMode.SNI
                    val sliding = SlidingTunnelMode.entries.firstOrNull {
                        it.name == o.optString("sliding_tunnel_mode")
                    } ?: SlidingTunnelMode.ROLLING_TUNNEL
                    val legacyEnableCustomDns = o.optBoolean("enable_custom_dns_url", false)
                    val isV21Schema = o.has("override_bypass")
                    val dnsModeOverride = if (o.has("dns_mode_override") && !o.isNull("dns_mode_override")) {
                        DnsMode.entries.firstOrNull { it.name == o.optString("dns_mode_override") }
                    } else if (!isV21Schema && legacyEnableCustomDns) {
                        DnsMode.SMART_DNS_DOH
                    } else {
                        null
                    }
                    val watchdogSensitivity = if (o.has("watchdog_sensitivity") && !o.isNull("watchdog_sensitivity")) {
                        WatchdogSensitivity.entries.firstOrNull { it.name == o.optString("watchdog_sensitivity") }
                    } else {
                        null
                    }
                    val keepaliveOverride = if (o.has("keepalive_interval_sec_override") && !o.isNull("keepalive_interval_sec_override")) {
                        o.optInt("keepalive_interval_sec_override").coerceIn(0, 60)
                    } else {
                        null
                    }
                    val debounceOverride = if (o.has("network_switch_debounce_sec_override") && !o.isNull("network_switch_debounce_sec_override")) {
                        o.optInt("network_switch_debounce_sec_override").coerceIn(1, 10)
                    } else {
                        null
                    }
                    val rootOverride = if (o.has("use_root_mode_override") && !o.isNull("use_root_mode_override")) {
                        o.optBoolean("use_root_mode_override")
                    } else {
                        null
                    }
                    val autoRotateSni = if (o.has("auto_rotate_sni_on_fail") && !o.isNull("auto_rotate_sni_on_fail")) {
                        o.optBoolean("auto_rotate_sni_on_fail")
                    } else {
                        null
                    }
                    val autoFailover = if (o.has("auto_failover_server") && !o.isNull("auto_failover_server")) {
                        o.optBoolean("auto_failover_server")
                    } else {
                        null
                    }
                    val smartRfOverride = if (o.has("smart_rf_routing_override") && !o.isNull("smart_rf_routing_override")) {
                        o.optBoolean("smart_rf_routing_override")
                    } else {
                        null
                    }
                    val bypassRegionOverride = if (o.has("bypass_region_override") && !o.isNull("bypass_region_override")) {
                        BypassRegion.entries.firstOrNull { it.name == o.optString("bypass_region_override") }
                    } else {
                        null
                    }
                    val splitTunnelOverride = if (o.has("split_tunnel_mode_override") && !o.isNull("split_tunnel_mode_override")) {
                        SplitTunnelMode.entries.firstOrNull { it.name == o.optString("split_tunnel_mode_override") }
                    } else {
                        null
                    }
                    val autoPingOverride = if (o.has("auto_ping_interval_sec_override") && !o.isNull("auto_ping_interval_sec_override")) {
                        o.optInt("auto_ping_interval_sec_override").coerceIn(0, 600)
                    } else {
                        null
                    }
                    val sortOrderOverride = if (o.has("server_sort_order_override") && !o.isNull("server_sort_order_override")) {
                        ServerSortOrder.entries.firstOrNull { it.name == o.optString("server_sort_order_override") }
                    } else {
                        null
                    }
                    val allowIpv6Override = if (o.has("allow_ipv6_override") && !o.isNull("allow_ipv6_override")) {
                        o.optBoolean("allow_ipv6_override")
                    } else {
                        null
                    }
                    val preferredServerId = if (o.has("preferred_server_id") && !o.isNull("preferred_server_id")) {
                        o.optLong("preferred_server_id")
                    } else {
                        null
                    }
                    add(
                        NetworkPreset(
                            id = id,
                            name = name,
                            trigger = trigger,
                            overrideBypass = o.optBoolean("override_bypass", true),
                            bypassMethod = bypass,
                            sniSpoofingMode = spoofing,
                            customSni = o.optString("custom_sni", VpnSettings.DEFAULT_SNI_DOMAIN)
                                .ifBlank { VpnSettings.DEFAULT_SNI_DOMAIN },
                            overrideSlidingTunnel = o.optBoolean("override_sliding_tunnel", true),
                            slidingTunnelMode = sliding,
                            dnsModeOverride = dnsModeOverride,
                            enableCustomDnsUrl = dnsModeOverride == DnsMode.SMART_DNS_DOH || legacyEnableCustomDns,
                            customDnsUrl = o.optString("custom_dns_url", ""),
                            dnsPrimaryOverride = o.optString("dns_primary_override", ""),
                            dnsSecondaryOverride = o.optString("dns_secondary_override", ""),
                            overrideWatchdog = o.optBoolean("override_watchdog", true),
                            enableStallWatchdog = o.optBoolean("enable_stall_watchdog", true),
                            watchdogSensitivity = watchdogSensitivity,
                            watchdogProbeSec = o.optInt("watchdog_probe_sec", 10).coerceIn(2, 25),
                            watchdogStallSec = o.optInt("watchdog_stall_sec", 24).coerceIn(4, 55),
                            watchdogCooldownSec = o.optInt("watchdog_cooldown_sec", 35).coerceIn(10, 90),
                            overrideReconnect = o.optBoolean("override_reconnect", true),
                            reconnectOnFailure = o.optBoolean("reconnect_on_failure", true),
                            reconnectOnNetworkChange = o.optBoolean("reconnect_on_network_change", true),
                            maxReconnectAttempts = o.optInt("max_reconnect_attempts", 15).coerceIn(3, 50),
                            reconnectDelaySec = o.optInt("reconnect_delay_sec", 2).coerceIn(1, 10),
                            handshakeTimeoutSec = o.optInt("handshake_timeout_sec", 8).coerceIn(4, 20),
                            wsOpenTimeoutSec = o.optInt("ws_open_timeout_sec", 15).coerceIn(5, 30),
                            keepaliveIntervalSecOverride = keepaliveOverride,
                            networkSwitchDebounceSecOverride = debounceOverride,
                            autoRotateSniOnFail = autoRotateSni,
                            autoFailoverServer = autoFailover,
                            overrideMtu = o.optBoolean("override_mtu", true),
                            tunMtu = o.optInt("tun_mtu", 1500).coerceIn(1280, 1500),
                            smartRfRouting = o.optBoolean("smart_rf_routing", true),
                            smartRfRoutingOverride = smartRfOverride,
                            bypassRegionOverride = bypassRegionOverride,
                            splitTunnelModeOverride = splitTunnelOverride,
                            autoPingIntervalSecOverride = autoPingOverride,
                            serverSortOrderOverride = sortOrderOverride,
                            allowIpv6Override = allowIpv6Override,
                            useRootModeOverride = rootOverride,
                            preferredServerId = preferredServerId,
                            forceOverrideManualLocks = o.optBoolean("force_override_manual_locks", false),
                            isBuiltIn = o.optBoolean("is_built_in", false),
                        ),
                    )
                }
            }
        }.getOrNull()
    }

    fun encodeQuickTiles(tiles: List<HomeQuickTileId>): String =
        tiles.joinToString(",") { it.name }

    fun decodeQuickTiles(raw: String?): List<HomeQuickTileId>? {
        if (raw == null) return null
        if (raw.isBlank()) return emptyList()
        return raw.split(',')
            .mapNotNull { token ->
                val trimmed = token.trim()
                HomeQuickTileId.entries.firstOrNull { it.name == trimmed }
            }
            .distinct()
    }
}
