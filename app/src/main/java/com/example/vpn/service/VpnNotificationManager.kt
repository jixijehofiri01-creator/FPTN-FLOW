package com.example.vpn.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.content.res.Resources
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.example.MainActivity
import com.example.R
import com.example.core.logging.AppLogStore
import com.example.domain.model.AppLanguage
import com.example.domain.model.FptnServer
import com.example.domain.model.VpnErrorInfo
import java.util.Locale

/**
 * Manages VPN foreground notifications, disconnect/reconnect alerts, and notification channels
 * using Android string resources localized to the user's chosen [AppLanguage].
 */
internal class VpnNotificationManager(
    private val context: Context,
    private val statusChannelId: String,
    private val alertChannelId: String,
    private val alertNotificationId: Int,
) {
    @Volatile
    private var appLanguage: AppLanguage = AppLanguage.SYSTEM

    fun setAppLanguage(language: AppLanguage) {
        appLanguage = language
    }

    fun localizedResources(): Resources {
        val baseConfig = context.resources.configuration
        val targetLocale = when (appLanguage) {
            AppLanguage.RU -> Locale("ru")
            AppLanguage.EN -> Locale.ENGLISH
            AppLanguage.SYSTEM -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && !baseConfig.locales.isEmpty) {
                    baseConfig.locales[0]
                } else {
                    Locale.getDefault()
                }
            }
        }
        val config = Configuration(baseConfig).apply { setLocale(targetLocale) }
        return context.createConfigurationContext(config).resources
    }

    fun getString(resId: Int, vararg formatArgs: Any): String {
        val res = localizedResources()
        return if (formatArgs.isEmpty()) res.getString(resId) else res.getString(resId, *formatArgs)
    }

    fun ensureChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = context.getSystemService(NotificationManager::class.java) ?: return
            val res = localizedResources()
            val statusChannel = NotificationChannel(
                statusChannelId,
                res.getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = res.getString(R.string.notification_channel_desc)
                setShowBadge(false)
            }
            val alertChannel = NotificationChannel(
                alertChannelId,
                res.getString(R.string.notification_alert_channel_name),
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = res.getString(R.string.notification_alert_channel_desc)
                enableVibration(true)
                setShowBadge(true)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }
            nm.createNotificationChannel(statusChannel)
            nm.createNotificationChannel(alertChannel)
        }
    }

    fun buildStatusNotification(
        title: String,
        subtitle: String?,
        showReconnectAction: Boolean = false,
        reconnectServerId: Long? = null,
    ): Notification {
        val res = localizedResources()
        val openAppIntent = buildOpenAppPendingIntent(100)
        val disconnectIntent = PendingIntent.getService(
            context,
            101,
            Intent(context, FptnVpnService::class.java).setAction(FptnVpnService.ACTION_DISCONNECT),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        val builder = NotificationCompat.Builder(context, statusChannelId)
            .setSmallIcon(R.drawable.ic_qs_fptn)
            .setContentTitle(title)
            .setContentText(subtitle ?: res.getString(R.string.vpn_notif_tunnel_active))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openAppIntent)

        if (showReconnectAction) {
            builder.addAction(
                0,
                res.getString(R.string.vpn_notif_action_reconnect),
                buildReconnectPendingIntent(102, reconnectServerId),
            )
        }
        builder.addAction(
            0,
            res.getString(R.string.vpn_notif_action_disconnect),
            disconnectIntent,
        )
        return builder.build()
    }

    fun showServerDropProgressAlert(server: FptnServer, reason: String) {
        ensureChannels()
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) {
            AppLogStore.warn("VpnService", "Уведомления отключены системой — пуш о потере связи с сервером не показан")
            return
        }
        val res = localizedResources()
        val title = res.getString(R.string.vpn_notif_server_unreachable_title, server.cleanName)
        val body = res.getString(R.string.vpn_notif_server_unreachable_body, reason)
        val notification = NotificationCompat.Builder(context, alertChannelId)
            .setSmallIcon(R.drawable.ic_qs_fptn)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(buildOpenAppPendingIntent(103))
            .addAction(
                0,
                res.getString(R.string.vpn_notif_action_reconnect),
                buildReconnectPendingIntent(104, server.id),
            )
            .addAction(
                0,
                res.getString(R.string.vpn_notif_action_auto_server),
                buildReconnectPendingIntent(105, -1L),
            )
            .build()

        runCatching {
            context.getSystemService(NotificationManager::class.java)?.notify(alertNotificationId, notification)
        }.onFailure { ex ->
            AppLogStore.warn("VpnService", "Не удалось показать пуш о потере связи: ${ex.message}")
        }
    }

    fun showDisconnectAlert(server: FptnServer?, errorInfo: VpnErrorInfo) {
        ensureChannels()
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) {
            AppLogStore.warn("VpnService", "Уведомления отключены системой — пуш об обрыве VPN не показан")
            return
        }
        val res = localizedResources()
        val title = if (server != null) {
            res.getString(R.string.vpn_notif_server_disconnected_title, server.cleanName)
        } else {
            res.getString(R.string.vpn_notif_vpn_disconnected_title, errorInfo.title)
        }
        val summary = errorInfo.details.ifBlank { errorInfo.title }
        val bigText = buildString {
            append(summary)
            if (errorInfo.hint.isNotBlank()) {
                append("\n")
                append(errorInfo.hint)
            }
        }
        val builder = NotificationCompat.Builder(context, alertChannelId)
            .setSmallIcon(R.drawable.ic_qs_fptn)
            .setContentTitle(title)
            .setContentText(summary)
            .setStyle(NotificationCompat.BigTextStyle().bigText(bigText))
            .setCategory(NotificationCompat.CATEGORY_ERROR)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setAutoCancel(true)
            .setContentIntent(buildOpenAppPendingIntent(106))
            .addAction(
                0,
                res.getString(R.string.vpn_notif_action_reconnect),
                buildReconnectPendingIntent(107, server?.id),
            )

        if (server != null) {
            builder.addAction(
                0,
                res.getString(R.string.vpn_notif_action_auto_server),
                buildReconnectPendingIntent(108, -1L),
            )
        }

        runCatching {
            context.getSystemService(NotificationManager::class.java)?.notify(alertNotificationId, builder.build())
        }.onFailure { ex ->
            AppLogStore.warn("VpnService", "Не удалось показать пуш об обрыве соединения: ${ex.message}")
        }
    }

    fun cancelDisconnectAlert() {
        runCatching {
            context.getSystemService(NotificationManager::class.java)?.cancel(alertNotificationId)
        }
    }

    private fun buildReconnectPendingIntent(requestCode: Int, serverId: Long?): PendingIntent {
        val intent = Intent(context, FptnVpnService::class.java).apply {
            action = FptnVpnService.ACTION_RECONNECT
            putExtra(FptnVpnService.EXTRA_SERVER_ID, serverId ?: -1L)
        }
        val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            PendingIntent.getForegroundService(context, requestCode, intent, flags)
        } else {
            PendingIntent.getService(context, requestCode, intent, flags)
        }
    }

    private fun buildOpenAppPendingIntent(requestCode: Int = 100): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        return PendingIntent.getActivity(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }
}
