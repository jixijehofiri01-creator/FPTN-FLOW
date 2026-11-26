package com.example.vpn.service

import com.example.domain.model.TrafficStats
import com.example.domain.model.VpnConnectionState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

object VpnStateHolder {

    private const val SPEED_HISTORY_POINTS = 24

    private val _connectionState = MutableStateFlow<VpnConnectionState>(VpnConnectionState.Disconnected)
    val connectionState: StateFlow<VpnConnectionState> = _connectionState.asStateFlow()

    private val _trafficStats = MutableStateFlow(TrafficStats())
    val trafficStats: StateFlow<TrafficStats> = _trafficStats.asStateFlow()

    fun updateState(newState: VpnConnectionState) {
        _connectionState.value = newState
        if (newState is VpnConnectionState.Disconnected || newState is VpnConnectionState.Error) {
            _trafficStats.value = TrafficStats()
        }
    }

    fun recordTrafficSample(
        downloadBytesPerSec: Long,
        uploadBytesPerSec: Long,
        totalDownloadBytes: Long,
        totalUploadBytes: Long,
    ) {
        _trafficStats.update { prev ->
            val downKb = (downloadBytesPerSec / 1024f).coerceAtLeast(0f)
            val upKb = (uploadBytesPerSec / 1024f).coerceAtLeast(0f)
            val nextDown = (prev.downloadHistory + downKb).takeLast(SPEED_HISTORY_POINTS)
            val nextUp = (prev.uploadHistory + upKb).takeLast(SPEED_HISTORY_POINTS)
            TrafficStats(
                downloadBytesPerSec = downloadBytesPerSec,
                uploadBytesPerSec = uploadBytesPerSec,
                totalDownloadBytes = totalDownloadBytes,
                totalUploadBytes = totalUploadBytes,
                downloadHistory = nextDown,
                uploadHistory = nextUp,
            )
        }
    }
}
