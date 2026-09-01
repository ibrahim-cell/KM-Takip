package com.kmtakip.app

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : ComponentActivity() {

    private lateinit var webView: WebView
    private val permissionRequestCode = 9001

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        webView = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowFileAccess = true
            settings.allowContentAccess = true
            webViewClient = WebViewClient()
            webChromeClient = WebChromeClient()
            addJavascriptInterface(AndroidTrackingBridge(), "AndroidTracking")
            loadUrl("file:///android_asset/index.html")
        }

        setContentView(webView)
        requestPermissionsIfNeeded()
    }

    private fun requestPermissionsIfNeeded() {
        val permissions = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            permissions += Manifest.permission.ACCESS_BACKGROUND_LOCATION
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions += Manifest.permission.POST_NOTIFICATIONS
        }

        val missing = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, missing.toTypedArray(), permissionRequestCode)
        }
    }

    inner class AndroidTrackingBridge {
        @JavascriptInterface
        fun startTracking() {
            ContextCompat.startForegroundService(
                this@MainActivity,
                Intent(this@MainActivity, TrackingService::class.java)
            )
        }

        @JavascriptInterface
        fun pauseTracking() {
            startService(Intent(this@MainActivity, TrackingService::class.java).setAction(TrackingService.ACTION_PAUSE))
        }

        @JavascriptInterface
        fun stopTracking() {
            startService(Intent(this@MainActivity, TrackingService::class.java).setAction(TrackingService.ACTION_STOP))
        }

        @JavascriptInterface
        fun updateNotification(distanceKm: Double, speedKmh: Double, elapsedSeconds: Long) {
            TrackingService.updateFromBridge(this@MainActivity, distanceKm, speedKmh, elapsedSeconds)
        }
    }

    override fun onBackPressed() {
        if (::webView.isInitialized && webView.canGoBack()) webView.goBack()
        else super.onBackPressed()
    }
}
