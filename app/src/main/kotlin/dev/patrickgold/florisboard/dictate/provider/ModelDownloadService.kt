/*
 * Copyright (C) 2026 DevEmperor (Dictate)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package dev.patrickgold.florisboard.dictate.provider

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import dev.patrickgold.florisboard.R
import dev.patrickgold.florisboard.app.FlorisAppActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * A foreground service that keeps on-device model downloads alive when the provider dialog is closed or
 * the app is left (issue #207), and shows an ongoing notification with live progress + a cancel action so
 * the user always knows a download is running in the background. The actual work lives in
 * [LocalModelDownloads]; this service just holds the process up and mirrors [LocalModelDownloads.state]
 * into the notification, stopping itself once no download is active.
 */
class ModelDownloadService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onCreate() {
        super.onCreate()
        createChannel()
        // Must call startForeground promptly after startForegroundService; seed with an initial notice.
        goForeground(NOTIF_ID, buildNotification(LocalModelDownloads.state.value))
        scope.launch {
            LocalModelDownloads.state.collectLatest { states ->
                val active = states.values.filter { it.error == null }
                if (active.isEmpty()) {
                    ServiceCompat.stopForeground(this@ModelDownloadService, ServiceCompat.STOP_FOREGROUND_REMOVE)
                    stopSelf()
                } else if (hasNotificationPermission()) {
                    NotificationManagerCompat.from(this@ModelDownloadService)
                        .notify(NOTIF_ID, buildNotification(states))
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CANCEL) {
            intent.getStringExtra(EXTRA_MODEL_ID)?.let { LocalModelDownloads.cancel(it) }
        }
        // Re-assert foreground (startForegroundService contract) on every start command.
        goForeground(NOTIF_ID, buildNotification(LocalModelDownloads.state.value))
        if (!LocalModelDownloads.hasActiveDownloads()) {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun goForeground(id: Int, notification: android.app.Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceCompat.startForeground(
                this, id, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        } else {
            ServiceCompat.startForeground(this, id, notification, 0)
        }
    }

    private fun buildNotification(states: Map<String, LocalModelDownloads.State>): android.app.Notification {
        val active = states.values.filter { it.error == null }
        val single = active.singleOrNull()
        val title = if (single != null) {
            val name = LocalModelCatalog.all.firstOrNull { it.id == single.modelId }?.displayName ?: single.modelId
            getString(R.string.dictate__local_model_notif_title).replace("{model}", name)
        } else {
            getString(R.string.dictate__local_model_notif_title_multi).replace("{count}", active.size.toString())
        }
        val percent = single?.percent ?: 0

        val contentIntent = PendingIntent.getActivity(
            this, 0,
            Intent(Intent.ACTION_VIEW, Uri.parse("ui://florisboard/settings/dictate/providers"), this, FlorisAppActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_app_icon_monochrome)
            .setContentTitle(title)
            .setContentText("$percent%")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setProgress(100, percent, single == null)
            .setContentIntent(contentIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)

        // A cancel action is only unambiguous with a single active download.
        if (single != null) {
            val cancelIntent = PendingIntent.getService(
                this, single.modelId.hashCode(),
                Intent(this, ModelDownloadService::class.java)
                    .setAction(ACTION_CANCEL)
                    .putExtra(EXTRA_MODEL_ID, single.modelId),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            builder.addAction(0, getString(R.string.dictate__local_model_action_cancel), cancelIntent)
        }
        return builder.build()
    }

    private fun createChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                getString(R.string.dictate__local_model_notif_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            ),
        )
    }

    private fun hasNotificationPermission(): Boolean =
        NotificationManagerCompat.from(this).areNotificationsEnabled()

    companion object {
        private const val CHANNEL_ID = "dictate_model_downloads"
        private const val NOTIF_ID = 4207
        private const val ACTION_CANCEL = "dev.devemperor.dictate.CANCEL_MODEL_DOWNLOAD"
        private const val EXTRA_MODEL_ID = "model_id"

        /** Starts the foreground service so an in-progress download keeps running in the background. */
        fun start(context: Context) {
            val intent = Intent(context, ModelDownloadService::class.java)
            ContextCompat.startForegroundService(context, intent)
        }
    }
}
