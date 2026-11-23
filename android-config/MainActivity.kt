package com.vigiastock.pro

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.webkit.JavascriptInterface
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import com.getcapacitor.BridgeActivity

class MainActivity : BridgeActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel("vigiastock_alerts", "Alertas Críticas", NotificationManager.IMPORTANCE_HIGH).apply { enableVibration(true) }
            getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
        }
        bridge.webView.addJavascriptInterface(AndroidBridge(this), "AndroidBridge")
        requestAllSystemPermissions()
    }

    fun requestAllSystemPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 101)
        }
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        if (!pm.isIgnoringBatteryOptimizations(packageName)) {
            startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
        }
        if (!Settings.canDrawOverlays(this)) {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        }
    }

    class AndroidBridge(private val act: MainActivity) {
        @JavascriptInterface fun requestAllPermissions() { act.runOnUiThread { act.requestAllSystemPermissions() } }
        @JavascriptInterface fun showNotification(title: String, body: String) {
            val n = NotificationCompat.Builder(act, "vigiastock_alerts").setSmallIcon(android.R.drawable.ic_dialog_alert).setContentTitle(title).setContentText(body).setPriority(NotificationCompat.PRIORITY_MAX).setAutoCancel(true).build()
            (act.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).notify(System.currentTimeMillis().toInt(), n)
        }
    }
}
