package com.mdkdw1.tesladash

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.webkit.*
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.firebase.messaging.FirebaseMessaging
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import org.json.JSONObject
import java.io.IOException

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private val client = OkHttpClient()
    private var keepAliveJob: Thread? = null
    private var isKeepAliveRunning = false

    private val cachedHtmlContent: String by lazy {
        assets.open("index.html")
            .bufferedReader(Charsets.UTF_8)
            .use { it.readText() }
    }
    private var pendingOAuthCode: String? = null
    private var lastProcessedCode: String? = null
    private var accessToken: String = ""

    companion object {
        private const val TAG = "TeslaDash"
        private const val RENDER_BASE_URL = "https://tesla-sentry.onrender.com"
        private const val BASE_URL = "https://mdkdw1-ui.github.io/tesla-dash"
        private const val OVERLAY_PERMISSION_REQ_CODE = 1234
        
        private const val TESLA_CLIENT_ID = "272ac00a-248e-4fa7-8027-1fc06e8e9a24"
        private const val REDIRECT_URI = "https://tesla-sync-api.vercel.app/api/callback"
        
        private var mainActivityInstance: MainActivity? = null

        fun injectFcmToken(token: String) {
            mainActivityInstance?.runOnUiThread {
                mainActivityInstance?.webView?.evaluateJavascript(
                    "window.fcmToken = '$token'; console.log('✅ FCM Token injected'); if (typeof onFcmTokenReady === 'function') onFcmTokenReady();",
                    null
                )
                Log.d(TAG, "✅ FCM Token injected: $token")
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        mainActivityInstance = this

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                    1001
                )
            }
        }

        webView = WebView(this)
        setContentView(webView)
        
        setupWebView()
        checkOverlayPermission()

        webView.loadDataWithBaseURL(
            BASE_URL,
            cachedHtmlContent,
            "text/html",
            "UTF-8",
            null
        )

        FirebaseMessaging.getInstance().token.addOnCompleteListener { task ->
            if (task.isSuccessful) {
                task.result?.let { token ->
                    webView.evaluateJavascript(
                        "window.fcmToken = '$token'; console.log('🔑 FCM Token pre-injected'); if (typeof onFcmTokenReady === 'function') onFcmTokenReady();",
                        null
                    )
                }
            }
        }
    }

    private fun setupWebView() {
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            allowFileAccessFromFileURLs = false
            allowUniversalAccessFromFileURLs = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            
            setSupportZoom(true)
            builtInZoomControls = true
            displayZoomControls = false
            loadWithOverviewMode = true
            useWideViewPort = true
            cacheMode = WebSettings.LOAD_DEFAULT
            setSupportMultipleWindows(false)
        }

        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)

        webView.addJavascriptInterface(AndroidBridge(), "AndroidBridge")

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val url = request?.url?.toString() ?: return false
                if (url.startsWith("https://tesla-sync-api.vercel.app/api/callback")) {
                    val code = extractCodeFromUrl(url)
                    if (!code.isNullOrEmpty()) {
                        exchangeTokenWithVercel(code)
                    }
                    return true
                }
                return false
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                if (url != null && url.contains("code=")) {
                    val code = extractCodeFromUrl(url)
                    if (code != null && code != lastProcessedCode) {
                        exchangeTokenWithVercel(code)
                    }
                }
            }

            private fun extractCodeFromUrl(url: String): String? {
                return try {
                    Uri.parse(url).getQueryParameter("code")
                } catch (e: Exception) {
                    null
                }
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(consoleMessage: android.webkit.ConsoleMessage?): Boolean {
                consoleMessage?.let {
                    Log.d(TAG, "🌐 Console: ${it.message()}")
                }
                return super.onConsoleMessage(consoleMessage)
            }
        }
    }

    private fun checkOverlayPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (!Settings.canDrawOverlays(this)) {
                Toast.makeText(this, "백그라운드 팝업을 위해 '다른 앱 위에 그리기' 권한이 필요합니다.", Toast.LENGTH_LONG).show()
                val intent = Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
                startActivityForResult(intent, OVERLAY_PERMISSION_REQ_CODE)
            }
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        when (requestCode) {
            OVERLAY_PERMISSION_REQ_CODE -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    if (Settings.canDrawOverlays(this)) {
                        Toast.makeText(this, "✅ 다른 앱 위에 그리기 권한이 허용되었습니다.", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(this, "⚠️ 권한이 거부되었습니다.", Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
    }

    private fun exchangeTokenWithVercel(code: String) {
        if (code == lastProcessedCode) return
        lastProcessedCode = code

        val json = JSONObject().apply {
            put("code", code)
            put("redirect_uri", REDIRECT_URI)
        }

        val mediaType = "application/json; charset=utf-8".toMediaType()
        val body = RequestBody.create(mediaType, json.toString())

        val request = Request.Builder()
            .url("https://tesla-sync-api.vercel.app/api/exchange")
            .post(body)
            .build()

        client.newCall(request).enqueue(object : okhttp3.Callback {
            override fun onFailure(call: Call, e: IOException) {
                runOnUiThread {
                    Toast.makeText(this@MainActivity, "❌ 네트워크 오류", Toast.LENGTH_SHORT).show()
                }
            }

            override fun onResponse(call: Call, response: Response) {
                val responseBody = response.body?.string() ?: ""
                if (response.isSuccessful) {
                    try {
                        val jsonRes = JSONObject(responseBody)
                        val token = jsonRes.getString("access_token")
                        accessToken = token

                        val prefs = getSharedPreferences("tesla_prefs", MODE_PRIVATE)
                        prefs.edit().putString("access_token", token).apply()

                        runOnUiThread {
                            webView.loadDataWithBaseURL(
                                BASE_URL,
                                cachedHtmlContent,
                                "text/html",
                                "UTF-8",
                                null
                            )
                            webView.postDelayed({
                                injectTokenToWebView(token)
                            }, 1000)
                        }
                    } catch (e: Exception) {
                        runOnUiThread {
                            Toast.makeText(this@MainActivity, "❌ 토큰 파싱 오류", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
        })
    }

    private fun injectTokenToWebView(token: String) {
        webView.postDelayed({
            val js = """
                (function() {
                    window.accessToken = '$token';
                    localStorage.setItem('tesla_access_token', '$token');
                    if (typeof window.handleOAuthCodeDirect === 'function') {
                        window.handleOAuthCodeDirect('$token', '');
                    }
                })();
            """.trimIndent()
            webView.evaluateJavascript(js, null)
        }, 500)
    }

    override fun onDestroy() {
        super.onDestroy()
        mainActivityInstance = null
    }

    inner class AndroidBridge {
        @JavascriptInterface
        fun startGuardianService(accessToken: String, vehicleId: String, interval: Int, topic: String) {
            Toast.makeText(this@MainActivity, "🛡️ 가디언 시작", Toast.LENGTH_SHORT).show()
        }

        @JavascriptInterface
        fun stopGuardianService() {
            Toast.makeText(this@MainActivity, "🛑 가디언 중지", Toast.LENGTH_SHORT).show()
        }

        @JavascriptInterface
        fun sendOAuthCode(code: String) {
            runOnUiThread { exchangeTokenWithVercel(code) }
        }

        @JavascriptInterface
        fun saveToken(token: String, refreshToken: String) {
            accessToken = token
            val prefs = getSharedPreferences("tesla_prefs", MODE_PRIVATE)
            prefs.edit().putString("access_token", token).apply()
        }

        @JavascriptInterface
        fun getToken(): String {
            val prefs = getSharedPreferences("tesla_prefs", MODE_PRIVATE)
            return prefs.getString("access_token", "") ?: ""
        }
    }
}
