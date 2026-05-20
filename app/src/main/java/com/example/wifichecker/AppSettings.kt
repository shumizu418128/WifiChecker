package com.example.wifichecker

import android.content.Context
import android.content.SharedPreferences

/**
 * アプリの設定を管理するクラス
 */
object AppSettings {
    private const val PREF_NAME = "wifi_checker_prefs"

    /** Webhook URL（local.properties の webhook.url からビルド時に注入。リポジトリには含めない） */
    val WEBHOOK_URL: String get() = BuildConfig.WEBHOOK_URL

    /** API Key（local.properties の webhook.api_key からビルド時に注入） */
    val API_KEY: String get() = BuildConfig.API_KEY

    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
    }
}
