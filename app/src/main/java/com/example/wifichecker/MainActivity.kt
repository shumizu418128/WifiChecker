package com.example.wifichecker

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.example.wifichecker.ui.theme.WifiCheckerTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            WifiCheckerTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    WifiMonitorScreen(modifier = Modifier.padding(innerPadding))
                }
            }
        }
    }
}

@Composable
fun WifiMonitorScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    var hasPermissions by remember { mutableStateOf(checkPermissions(context)) }
    var isBackgroundGranted by remember { mutableStateOf(isBackgroundLocationPermissionGranted(context)) }

    // 設定画面から戻ってきたときに権限を再チェックするためのオブザーバー
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                hasPermissions = checkPermissions(context)
                isBackgroundGranted = isBackgroundLocationPermissionGranted(context)
                if (hasPermissions) {
                    startWifiService(context)
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        hasPermissions = checkPermissions(context)
        isBackgroundGranted = isBackgroundLocationPermissionGranted(context)
        if (hasPermissions) {
            startWifiService(context)
        }
    }

    LaunchedEffect(Unit) {
        if (!hasPermissions) {
            val permissions = mutableListOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
            )
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                permissions.add(Manifest.permission.POST_NOTIFICATIONS)
            }
            permissionLauncher.launch(permissions.toTypedArray())
        } else {
            startWifiService(context)
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "WiFi Checker",
            style = MaterialTheme.typography.headlineMedium
        )
        Spacer(modifier = Modifier.height(24.dp))

        // 「常に許可」や通知権限がない場合の警告表示
        val isNotificationGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }

        val allPermissionsGranted =
            hasPermissions && isBackgroundGranted && isNotificationGranted

        if (allPermissionsGranted) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color(0xFFE8F5E9))
                    .padding(16.dp)
            ) {
                Column {
                    Text(
                        text = "✅ OK",
                        style = MaterialTheme.typography.titleMedium,
                        color = Color(0xFF2E7D32),
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "WiFi 監視サービスが実行中です",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color(0xFF2E7D32)
                    )
                }
            }
            Spacer(modifier = Modifier.height(24.dp))
        } else {
            val warningMessages = mutableListOf<String>()
            if (!hasPermissions) {
                warningMessages.add("位置情報・通知の権限を許可してください。")
            }
            if (hasPermissions && !isBackgroundGranted) {
                warningMessages.add("位置情報の権限を「常に許可」に変更してください。")
            }
            if (!isNotificationGranted) {
                warningMessages.add("通知の権限を許可してください。")
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color(0xFFFFEBEE))
                    .clickable {
                        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                            data = Uri.fromParts("package", context.packageName, null)
                        }
                        context.startActivity(intent)
                    }
                    .padding(16.dp)
            ) {
                Column {
                    Text(
                        text = "⚠️ 設定が必要です",
                        style = MaterialTheme.typography.titleMedium,
                        color = Color(0xFFC62828),
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    warningMessages.forEach {
                        Text(
                            text = it,
                            style = MaterialTheme.typography.bodyMedium,
                            color = Color(0xFFC62828)
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "タップして設定を開く",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color(0xFFC62828),
                        fontWeight = FontWeight.Medium
                    )
                }
            }
            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

private fun isBackgroundLocationPermissionGranted(context: Context): Boolean {
    return ContextCompat.checkSelfPermission(
        context,
        Manifest.permission.ACCESS_BACKGROUND_LOCATION
    ) == PackageManager.PERMISSION_GRANTED
}

private fun checkPermissions(context: Context): Boolean {
    val fineLocation = ContextCompat.checkSelfPermission(
        context,
        Manifest.permission.ACCESS_FINE_LOCATION
    ) == PackageManager.PERMISSION_GRANTED

    val coarseLocation = ContextCompat.checkSelfPermission(
        context,
        Manifest.permission.ACCESS_COARSE_LOCATION
    ) == PackageManager.PERMISSION_GRANTED

    val notifications = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
    } else {
        true
    }

    return fineLocation && coarseLocation && notifications
}

private fun startWifiService(context: android.content.Context) {
    val intent = Intent(context, WifiMonitorService::class.java)
    context.startForegroundService(intent)
}
