package com.kmtakip.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.webkit.ConsoleMessage
import android.webkit.GeolocationPermissions
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.webkit.WebViewAssetLoader

class MainActivity : ComponentActivity() {

    private lateinit var webView: WebView

    companion object {
        private const val LOCATION_PERMISSION_REQUEST = 1001
        private const val NOTIFICATION_PERMISSION_REQUEST = 1002
    }

    private inner class TrackingBridge {

        @JavascriptInterface
        fun startTracking() {
            runOnUiThread {
                if (hasLocationPermission()) {
                    val intent = Intent(this@MainActivity, TrackingService::class.java)
                    ContextCompat.startForegroundService(this@MainActivity, intent)
                } else {
                    requestLocationPermission()
                }
            }
        }

        @JavascriptInterface
        fun stopTracking() {
            runOnUiThread {
                val intent = Intent(
                    this@MainActivity,
                    TrackingService::class.java
                ).setAction(TrackingService.ACTION_STOP)

                try {
                    startService(intent)
                } catch (_: IllegalStateException) {
                }
            }
        }

        @JavascriptInterface
        fun isTracking(): Boolean {
            return TrackingService.isRunning
        }

        @JavascriptInterface
        fun isMotionDetected(): Boolean {
            return TrackingService.isMotionDetected
        }

        @JavascriptInterface
        fun isPaused(): Boolean {
            return TrackingService.isPaused
        }

        @JavascriptInterface
        fun updateNotification(distanceKm: Double, speedKmh: Double, elapsedSeconds: Long) {
            TrackingService.updateFromBridge(this@MainActivity, distanceKm, speedKmh, elapsedSeconds)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        webView = WebView(this)

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            allowFileAccess = true
            allowContentAccess = true
            mediaPlaybackRequiresUserGesture = false
        }

        webView.addJavascriptInterface(TrackingBridge(), "AndroidTracking")

        val assetLoader = WebViewAssetLoader.Builder()
            .addPathHandler(
                "/assets/",
                WebViewAssetLoader.AssetsPathHandler(this)
            )
            .build()

        webView.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(
                view: WebView,
                request: WebResourceRequest
            ): WebResourceResponse? {
                return assetLoader.shouldInterceptRequest(request.url)
            }

            override fun shouldInterceptRequest(
                view: WebView,
                url: String
            ): WebResourceResponse? {
                return assetLoader.shouldInterceptRequest(Uri.parse(url))
            }

            override fun onPageFinished(view: WebView, url: String) {
                super.onPageFinished(view, url)
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onGeolocationPermissionsShowPrompt(
                origin: String?,
                callback: GeolocationPermissions.Callback?
            ) {
                callback?.invoke(origin, true, false)
            }

            override fun onConsoleMessage(consoleMessage: ConsoleMessage): Boolean {
                android.util.Log.e(
                    "KM_TAKIP_JS",
                    "${consoleMessage.message()} -- ${consoleMessage.sourceId()}:${consoleMessage.lineNumber()}"
                )
                return true
            }
        }

        setContentView(webView)

        requestLocationPermission()
        requestNotificationPermissionIfNeeded()

        webView.loadUrl("https://appassets.androidplatform.net/assets/index.html")
    }

    private fun hasLocationPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_COARSE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
    }

    private fun requestLocationPermission() {
        if (!hasLocationPermission()) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                ),
                LOCATION_PERMISSION_REQUEST
            )
        }
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(
                    Manifest.permission.POST_NOTIFICATIONS
                ),
                NOTIFICATION_PERMISSION_REQUEST
            )
        }
    }

    override fun onResume() {
        super.onResume()
        TrackingService.setUiVisible(true)
    }

    override fun onPause() {
        TrackingService.setUiVisible(false)
        super.onPause()
    }

    override fun onDestroy() {
        webView.destroy()
        super.onDestroy()
    }
}
