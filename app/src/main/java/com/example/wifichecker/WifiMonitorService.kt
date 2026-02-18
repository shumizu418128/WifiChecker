package com.example.wifichecker

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
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
    private val CHANNEL_ID = "wifi_monitor_channel"
    private val NOTIFICATION_ID = 1

    private lateinit var connectivityManager: ConnectivityManager
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val client = OkHttpClient()
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    private var isWifiConnected: Boolean? = null
    private var currentSsid: String? = null
    private var checkJob: Job? = null

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            Log.d(TAG, "Network onAvailable")
            checkWifiStatus()
        }

        override fun onLost(network: Network) {
            Log.d(TAG, "Network onLost")
            checkWifiStatus()
        }

        override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
            Log.d(TAG, "Network onCapabilitiesChanged: $networkCapabilities")
            checkWifiStatus()
        }
    }

    override fun onCreate() {
        super.onCreate()
        connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, createNotification("WiFi 監視を開始しました"))
        
        val networkRequest = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .build()
        connectivityManager.registerNetworkCallback(networkRequest, networkCallback)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        connectivityManager.unregisterNetworkCallback(networkCallback)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    /**
     * WiFi の接続状態を確認し、変化があれば Webhook を送信する
     * ネットワークの切り替え待ちのため、少し遅延させてから確認を行う
     */
    private fun checkWifiStatus() {
        checkJob?.cancel()
        checkJob = serviceScope.launch {
            val activeNetwork = connectivityManager.activeNetwork
            val capabilities = connectivityManager.getNetworkCapabilities(activeNetwork)
            val connected = capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
            
            var ssid: String? = null
            if (connected) {
                // 1. NetworkCapabilities からの取得 (API 29+)
                val transportInfo = capabilities?.transportInfo
                if (transportInfo is WifiInfo) {
                    ssid = transportInfo.ssid?.replace("\"", "")
                }
                
                // 2. Android 12 (API 31) 以上での代替手段: activeNetwork から直接取得を試みる
                if (ssid == null || ssid == "<unknown ssid>") {
                    val currentNetwork = connectivityManager.activeNetwork
                    val networkCapabilities = connectivityManager.getNetworkCapabilities(currentNetwork)
                    val info = networkCapabilities?.transportInfo
                    if (info is WifiInfo) {
                        ssid = info.ssid?.replace("\"", "")
                    }
                }

                // 3. 従来の WifiManager からの取得 (フォールバック)
                if (ssid == null || ssid == "<unknown ssid>") {
                    val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
                    @Suppress("DEPRECATION")
                    val wifiInfo = wifiManager.connectionInfo
                    ssid = wifiInfo.ssid?.replace("\"", "")
                }

                // 最終的に取得できなかった場合のログ
                if (ssid == "<unknown ssid>") {
                    Log.w(TAG, "SSID could not be retrieved (still <unknown ssid>). Check location permissions.")
                }
            }

            if (isWifiConnected != connected || (connected && currentSsid != ssid)) {
                val oldStatus = isWifiConnected
                isWifiConnected = connected
                currentSsid = ssid
                
                // 初回起動時（oldStatus == null）は通知のみで Webhook は送らない
                if (oldStatus != null) {
                    sendWebhook(connected, ssid)
                }
            }
        }
    }

    /**
     * Webhook を送信する
     *
     * @param connected WiFi に接続されているかどうか
     * @param ssid 接続されている WiFi の SSID (接続時のみ)
     */
    private fun sendWebhook(connected: Boolean, ssid: String?) {
        val event = if (connected) "wifi_connected" else "wifi_disconnected"
        val timestamp = ZonedDateTime.now().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)
        
        val json = if (connected) {
            """
                {
                    "event": "$event",
                    "ssid": "$ssid",
                    "timestamp": "$timestamp"
                }
            """.trimIndent()
        } else {
            """
                {
                    "event": "$event",
                    "timestamp": "$timestamp"
                }
            """.trimIndent()
        }

        // ネットワークが一時的に切断されている可能性があるため、
        // 切断イベントの場合は少し待機するか、即座に送信を試みる。
        // WiFi切断時はモバイルデータ通信が生きていれば送信可能。
        serviceScope.launch {
            try {
                val request = Request.Builder()
                    .url(AppSettings.WEBHOOK_URL)
                    .post(json.toRequestBody(jsonMediaType))
                    .build()

                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        Log.e(TAG, "Webhook 送信失敗 ($event): ${response.code}")
                    } else {
                        Log.d(TAG, "Webhook 送信成功 ($event)")
                    }
                }
                Log.d(TAG, "送信内容: $json")
            } catch (e: Exception) {
                Log.e(TAG, "Webhook 送信エラー ($event)", e)
            }
        }
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "WiFi 監視サービス",
            NotificationManager.IMPORTANCE_MIN
        ).apply {
            description = "WiFi の接続状況を常時監視します"
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

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("WiFi Checker")
            .setContentText(content)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(content: String) {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, createNotification(content))
    }
}
