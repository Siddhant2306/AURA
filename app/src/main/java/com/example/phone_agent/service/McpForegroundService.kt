package com.example.phone_agent.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.example.phone_agent.MainActivity
import com.example.phone_agent.R
import com.example.phone_agent.mcp.McpHttpServer

class McpForegroundService : Service() {
    private var server: McpHttpServer? = null

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }

            ACTION_START, null -> startServer(intent)
        }
        return START_STICKY
    }

    override fun onDestroy() {
        server?.stop()
        server = null
        isRunning = false
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startServer(intent: Intent?) {
        val token = intent?.getStringExtra(EXTRA_TOKEN).orEmpty()
        val port = intent?.getIntExtra(EXTRA_PORT, McpHttpServer.DEFAULT_PORT) ?: McpHttpServer.DEFAULT_PORT
        if (token.isBlank()) {
            Log.e(TAG, "Refusing to start MCP server without a bearer token")
            stopSelf()
            return
        }

        createNotificationChannel()
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            createNotification(),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            } else {
                0
            },
        )

        if (server?.isRunning() == true) {
            isRunning = true
            return
        }

        server = McpHttpServer(bearerToken = token, port = port).also { it.start() }
        isRunning = true
        activePort = port
    }

    private fun createNotification(): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_phone_control_notification)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(getString(R.string.notification_text))
            .setStyle(
                NotificationCompat.BigTextStyle()
                    .bigText("${getString(R.string.notification_title)} - ${getString(R.string.notification_text)}"),
            )
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        )
        manager.createNotificationChannel(channel)
    }

    companion object {
        const val ACTION_START = "com.example.phone_agent.action.START_MCP"
        const val ACTION_STOP = "com.example.phone_agent.action.STOP_MCP"
        const val EXTRA_TOKEN = "com.example.phone_agent.extra.TOKEN"
        const val EXTRA_PORT = "com.example.phone_agent.extra.PORT"
        private const val CHANNEL_ID = "phone_control"
        private const val NOTIFICATION_ID = 41
        private const val TAG = "McpForegroundService"

        @Volatile
        var isRunning: Boolean = false
            private set

        @Volatile
        var activePort: Int = McpHttpServer.DEFAULT_PORT
            private set
    }
}
