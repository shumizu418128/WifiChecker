package com.example.wifichecker

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/**
 * WiFi の接続状況を監視し、Webhook を送信するフォアグラウンドサービス
 */
class WifiMonitorService : Service() {

    private val TAG = "WifiMonitorService"
    // IDを変更することで、重要度(IMPORTANCE_LOW)を確実に再適用させる
    private val CHANNEL_ID = "wifi_monitor_channel_v3"
    private val NOTIFICATION_ID = 1

    private lateinit var connectivityManager: ConnectivityManager
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val client = OkHttpClient()
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    private val checkDebounceMs = 800L
    private var isWifiConnected: Boolean? = null
    private var currentSsid: String? = null
    private var checkJob: Job? = null

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = checkWifiStatus()
        override fun onLost(network: Network) = checkWifiStatus()
        override fun onCapabilitiesChanged(network: Network, cap: NetworkCapabilities) = checkWifiStatus()
    }

    override fun onCreate() {
        super.onCreate()
        connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        createNotificationChannel()
        
        // フォアグラウンドサービスとして開始
        startForeground(
            NOTIFICATION_ID, 
            createNotification("WiFi 監視を開始しました"),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        )
        
        val networkRequest = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .build()
        connectivityManager.registerNetworkCallback(networkRequest, networkCallback)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY // システムに強制終了されても自動再起動を試みる
    }

    override fun onDestroy() {
        super.onDestroy()
        connectivityManager.unregisterNetworkCallback(networkCallback)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun checkWifiStatus() {
        checkJob?.cancel()
        checkJob = serviceScope.launch {
            delay(checkDebounceMs)
            performWifiStatusCheck()
        }
    }

    private fun performWifiStatusCheck() {
        val activeNetwork = connectivityManager.activeNetwork
        val capabilities = connectivityManager.getNetworkCapabilities(activeNetwork)
        val connected = capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true

        var ssid: String? = null
        if (connected) {
            val transportInfo = capabilities?.transportInfo
            if (transportInfo is WifiInfo) {
                ssid = transportInfo.ssid?.replace("\"", "")
            }
            if (ssid == null || ssid == "<unknown ssid>") {
                val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
                @Suppress("DEPRECATION")
                val wifiInfo = wifiManager.connectionInfo
                ssid = wifiInfo.ssid?.replace("\"", "")
            }
        }

        val currentTime = ZonedDateTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss"))
        updateNotification("WiFi 監視中 (最終確認: $currentTime)")

        if (isWifiConnected != connected || (connected && currentSsid != ssid)) {
            val oldStatus = isWifiConnected
            isWifiConnected = connected
            currentSsid = ssid
            if (oldStatus != null) {
                sendWebhook(connected, ssid)
            }
        }
    }

    private fun sendWebhook(connected: Boolean, ssid: String?) {
        val event = if (connected) "wifi_connected" else "wifi_disconnected"
        val timestamp = ZonedDateTime.now().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)
        val json = if (connected) {
            """{"event": "$event", "ssid": "$ssid", "timestamp": "$timestamp"}"""
        } else {
            """{"event": "$event", "timestamp": "$timestamp"}"""
        }

        serviceScope.launch {
            try {
                val request = Request.Builder()
                    .url(AppSettings.WEBHOOK_URL)
                    .addHeader("X-API-Key", AppSettings.API_KEY)
                    .post(json.toRequestBody(jsonMediaType))
                    .build()
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) Log.e(TAG, "Webhook failed: ${response.code}")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Webhook error", e)
            }
        }
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "WiFi 監視サービス",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "WiFi の接続状況を常時監視します"
            setShowBadge(false)
        }
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
    }

    private fun createNotification(content: String): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("WiFi Checker")
            .setContentText(content)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            builder.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
        }

        return builder.build()
    }

    private fun updateNotification(content: String) {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, createNotification(content))
    }
}
