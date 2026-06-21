package com.example.wifichecker

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * AlarmManager から定期的に呼び出され、サービスが停止していた場合に再起動する Receiver
 */
class ServiceRestarter : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Log.d("ServiceRestarter", "Alarm triggered. Ensuring service is running...")
        val serviceIntent = Intent(context, WifiMonitorService::class.java)
        context.startForegroundService(serviceIntent)
    }
}
