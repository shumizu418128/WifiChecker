package com.example.wifichecker

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters

/**
 * WorkManager から定期的に呼び出され、サービスが停止していた場合に再起動する Worker
 */
class MonitoringWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        Log.d("MonitoringWorker", "WorkManager task triggered. Ensuring service is running...")
        val serviceIntent = Intent(applicationContext, WifiMonitorService::class.java)
        applicationContext.startForegroundService(serviceIntent)
        return Result.success()
    }
}
