package io.github.clinal.cordis.runtime

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import io.github.clinal.cordis.CordisApplication
import io.github.clinal.cordis.MainActivity
import io.github.clinal.cordis.R
import io.github.clinal.cordis.data.InstanceRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class RuntimeService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var foregroundStarted = false
    private var hadActiveRuntime = false

    private val supervisor: RuntimeSupervisor
        get() = (application as CordisApplication).runtimeSupervisor

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        serviceScope.launch {
            supervisor.activeRuntimeCount.collectLatest { count ->
                if (count > 0) {
                    hadActiveRuntime = true
                } else if (foregroundStarted && hadActiveRuntime) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    foregroundStarted = false
                    hadActiveRuntime = false
                    stopSelf()
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) {
            val instanceIds = (application as CordisApplication).instanceRepository.autoStartInstanceIds()
            if (instanceIds.isEmpty()) {
                stopSelf(startId)
            } else {
                startInForeground()
                instanceIds.forEach(supervisor::start)
            }
            return START_STICKY
        }

        val instanceId = intent.getStringExtra(EXTRA_INSTANCE_ID) ?: InstanceRepository.DEFAULT_INSTANCE_ID
        when (intent.action) {
            ACTION_START -> {
                startInForeground()
                supervisor.start(instanceId)
            }
            ACTION_STOP -> supervisor.stop(instanceId)
            ACTION_REMOVE -> supervisor.remove(instanceId)
        }
        return START_STICKY
    }

    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun startInForeground() {
        if (foregroundStarted) return

        val notificationManager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            notificationManager.createNotificationChannel(
                NotificationChannel(
                    NOTIFICATION_CHANNEL_ID,
                    getString(R.string.runtime_notification_channel),
                    NotificationManager.IMPORTANCE_LOW,
                ),
            )
        }
        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            android.app.Notification.Builder(this, NOTIFICATION_CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            android.app.Notification.Builder(this)
        }
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle(getString(R.string.runtime_notification_title))
            .setContentText(getString(R.string.runtime_notification_text))
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .build()
        startForeground(NOTIFICATION_ID, notification)
        foregroundStarted = true
    }

    companion object {
        private const val NOTIFICATION_CHANNEL_ID = "cordis_runtime"
        private const val NOTIFICATION_ID = 1
        const val ACTION_START = "io.github.clinal.cordis.runtime.START"
        const val ACTION_STOP = "io.github.clinal.cordis.runtime.STOP"
        const val ACTION_REMOVE = "io.github.clinal.cordis.runtime.REMOVE"
        const val EXTRA_INSTANCE_ID = "instance_id"

        fun start(context: Context, instanceId: String) {
            val intent = Intent(context, RuntimeService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_INSTANCE_ID, instanceId)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }
}
