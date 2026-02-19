# WiFi Checker

Android 端末の WiFi 接続状況を監視し、接続・切断の変化を Webhook で通知するアプリです。

## 機能

- **WiFi 監視**: フォアグラウンドサービスで WiFi の接続・切断を常時監視
- **Webhook 送信**: 接続状態が変わったタイミングで、指定 URL へ JSON を POST
- **再起動後も継続**: 端末の再起動後、監視サービスを自動で再開（`BootReceiver`）
- **リトライ**: Webhook 送信失敗時、最大 1 分間の指数バックオフでリトライ

### Webhook ペイロード例

**接続時（`wifi_connected`）**

```json
{
  "event": "wifi_connected",
  "ssid": "YourNetworkName",
  "timestamp": "2025-02-19T12:00:00+09:00"
}
```

**切断時（`wifi_disconnected`）**

```json
{
  "event": "wifi_disconnected",
  "timestamp": "2025-02-19T12:05:00+09:00"
}
```

## 必要な権限

- **位置情報（常に許可）**: WiFi の SSID 取得に必要（Android の仕様）
- **通知（Android 13 以降）**: フォアグラウンドサービス表示用

初回起動時、または設定が不足している場合は画面上の案内に従って「設定を開く」から権限を付与してください。

## 環境

- **minSdk**: 29 (Android 10)
- **targetSdk**: 36
- **Kotlin** + **Jetpack Compose** + **OkHttp** + **Coroutines**

## セットアップ

1. リポジトリをクローンする
2. **Webhook URL の設定**
   - `local.properties.example` をコピーして `local.properties` を作成
   - `webhook.url` に送信先の URL を設定

   ```properties
   webhook.url=https://your-webhook-url-here.example.com
   ```

   ※ `local.properties` は Git に含めない想定です。ビルド時に `BuildConfig.WEBHOOK_URL` へ注入されます。

3. Android Studio でプロジェクトを開き、実機またはエミュレータでビルド・実行

## ビルド・実行

- **Debug**: 通常の Run で `./gradlew assembleDebug` 相当
- **Release**: `./gradlew assembleRelease`

## プロジェクト構成（主要ファイル）

| ファイル | 説明 |
|----------|------|
| `MainActivity.kt` | Compose UI。権限確認と監視サービスの起動、状態表示 |
| `WifiMonitorService.kt` | フォアグラウンドサービス。ネットワーク変化の監視と Webhook 送信 |
| `BootReceiver.kt` | 端末起動完了時に `WifiMonitorService` を起動 |
| `AppSettings.kt` | 設定の参照（Webhook URL は `BuildConfig` から取得） |
