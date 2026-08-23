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

        checkOverlayPermission()

        webView = WebView(this)
        setContentView(webView)
        
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
                    } else {
                        showErrorOnScreen("Callback URL에서 인증 코드를 찾을 수 없습니다.")
                    }
                    return true
                }
                return false
            }

            override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                super.onReceivedError(view, request, error)
                if (request?.isForMainFrame == true) {
                    showErrorOnScreen("웹 로딩 에러 [코드 ${error?.errorCode}]: ${error?.description}")
                }
            }

            override fun onReceivedHttpError(view: WebView?, request: WebResourceRequest?, errorResponse: WebResourceResponse?) {
                super.onReceivedHttpError(view, request, errorResponse)
                if (request?.isForMainFrame == true) {
                    showErrorOnScreen("HTTP 에러 [상태코드 ${errorResponse?.statusCode}]: ${errorResponse?.reasonPhrase}")
                }
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)

                if (url != null && url.contains("code=") && url.contains("tesla-sync-api.vercel.app")) {
                    val code = extractCodeFromUrl(url)
                    if (code != null && code != lastProcessedCode) {
                        exchangeTokenWithVercel(code)
                        return
                    }
                }

                pendingOAuthCode?.let { code ->
                    webView.evaluateJavascript(
                        "if (typeof addLog === 'function') addLog('🔐 로그인 코드 처리 중: ${code.take(10)}...');" +
                        "if (typeof window.handleOAuthCode === 'function') { window.handleOAuthCode('$code'); }",
                        null
                    )
                    pendingOAuthCode = null
                }

                FirebaseMessaging.getInstance().token.addOnCompleteListener { task ->
                    if (task.isSuccessful) {
                        task.result?.let { token ->
                            view?.evaluateJavascript(
                                "window.fcmToken = '$token'; console.log('🔄 FCM Token re-injected'); if (typeof onFcmTokenReady === 'function') onFcmTokenReady();",
                                null
                            )
                        }
                    }
                }
            }

            private fun extractCodeFromUrl(url: String): String? {
                return try {
                    val uri = Uri.parse(url)
                    uri.getQueryParameter("code")
                } catch (e: Exception) {
                    showErrorOnScreen("URL 파싱 실패: ${e.message}")
                    null
                }
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(consoleMessage: android.webkit.ConsoleMessage?): Boolean {
                consoleMessage?.let {
                    if (it.messageLevel() == android.webkit.ConsoleMessage.MessageLevel.ERROR) {
                        showErrorOnScreen("JS Error: ${it.message()}")
                    }
                }
                return super.onConsoleMessage(consoleMessage)
            }
        }

        val prefs = getSharedPreferences("tesla_prefs", MODE_PRIVATE)
        accessToken = prefs.getString("access_token", "") ?: ""

        webView.loadDataWithBaseURL(
            BASE_URL,
            cachedHtmlContent,
            "text/html",
            "UTF-8",
            null
        )

        if (accessToken.isNotEmpty()) {
            webView.postDelayed({
                injectTokenToWebView(accessToken)
            }, 500)
        }

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

        intent?.data?.let { uri -> handleDeepLink(uri) }
    }

    private fun checkOverlayPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (!Settings.canDrawOverlays(this)) {
                Toast.makeText(this, "백그라운드 팝업 동작을 위해 '다른 앱 위에 그리기' 권한이 필요합니다.", Toast.LENGTH_LONG).show()
                val intent = Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
                startActivityForResult(intent, OVERLAY_PERMISSION_REQ_CODE)
            }
        }
    }

    private fun showErrorOnScreen(errorMessage: String) {
        runOnUiThread {
            Log.e(TAG, "❌ [ScreenError] $errorMessage")
            Toast.makeText(this, "⚠️ $errorMessage", Toast.LENGTH_LONG).show()
            
            val safeMsg = errorMessage.replace("'", "\\'").replace("\n", " ")
            webView.evaluateJavascript(
                "if (typeof addLog === 'function') { addLog('❌ $safeMsg'); } " +
                "else { console.error('❌ $safeMsg'); }",
                null
            )
        }
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        intent?.data?.let { uri -> handleDeepLink(uri) }
    }

    private fun handleDeepLink(uri: Uri) {
        val code = uri.getQueryParameter("code")
        if (code != null && code != lastProcessedCode) {
            exchangeTokenWithVercel(code)
        }
    }

    private fun exchangeTokenWithVercel(code: String) {
        if (code == lastProcessedCode) return
        lastProcessedCode = code

        showToast("🔄 토큰 교환 중...")

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
                showErrorOnScreen("Vercel 통신 실패: ${e.message}")
            }

            override fun onResponse(call: Call, response: Response) {
                val responseBody = response.body?.string() ?: ""

                if (response.isSuccessful) {
                    try {
                        val jsonRes = JSONObject(responseBody)
                        val token = jsonRes.getString("access_token")
                        val refreshToken = jsonRes.optString("refresh_token", "")
                        
                        accessToken = token

                        val prefs = getSharedPreferences("tesla_prefs", MODE_PRIVATE)
                        prefs.edit().putString("access_token", token).apply()
                        if (refreshToken.isNotEmpty()) {
                            prefs.edit().putString("refresh_token", refreshToken).apply()
                        }

                        syncTokensToRender(token, refreshToken)

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
                                showToast("✅ 로그인 성공!")
                            }, 1000)
                        }

                    } catch (e: Exception) {
                        showErrorOnScreen("토큰 파싱 에러 [응답: $responseBody]: ${e.message}")
                    }
                } else {
                    showErrorOnScreen("토큰 교환 실패 [HTTP ${response.code}]: $responseBody")
                }
            }
        })
    }

    private fun syncTokensToRender(accessToken: String, refreshToken: String) {
        if (RENDER_BASE_URL.contains("<YOUR-RENDER-APP>")) return
        
        val json = JSONObject().apply {
            put("accessToken", accessToken)
            put("refreshToken", refreshToken)
        }

        val mediaType = "application/json; charset=utf-8".toMediaType()
        val body = RequestBody.create(mediaType, json.toString())

        val request = Request.Builder()
            .url("$RENDER_BASE_URL/api/token")
            .post(body)
            .build()

        client.newCall(request).enqueue(object : okhttp3.Callback {
            override fun onFailure(call: Call, e: IOException) {
                showErrorOnScreen("Render 동기화 실패: ${e.message}")
            }

            override fun onResponse(call: Call, response: Response) {
                if (!response.isSuccessful) {
                    showErrorOnScreen("Render 동기화 에러 [코드 ${response.code}]")
                }
                response.body?.close()
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
                    } else {
                        var loginSection = document.getElementById('loginSection');
                        var tokenSection = document.getElementById('tokenInfoSection');
                        var displayToken = document.getElementById('displayAccessToken');
                        
                        if (loginSection) loginSection.classList.add('hidden');
                        if (tokenSection) tokenSection.classList.remove('hidden');
                        if (displayToken) displayToken.innerText = '$token';
                        
                        if (typeof fetchTeslaVehicles === 'function') {
                            fetchTeslaVehicles('$token', true);
                        }
                        if (typeof handleRefresh === 'function') {
                            handleRefresh(false);
                        }
                    }
                })();
            """.trimIndent()

            webView.evaluateJavascript(js, null)
        }, 500)
    }

    private fun showToast(message: String) {
        runOnUiThread {
            Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        stopKeepAlive()
        mainActivityInstance = null
    }

    inner class AndroidBridge {
        @JavascriptInterface
        fun startGuardianService(accessToken: String, vehicleId: String, interval: Int, topic: String) {
            showToast("🛡️ 가디언 시작")
            startKeepAlive()
        }

        @JavascriptInterface
        fun stopGuardianService() {
            showToast("🛑 가디언 중지")
            stopKeepAlive()
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
            if (refreshToken.isNotEmpty()) {
                prefs.edit().putString("refresh_token", refreshToken).apply()
            }
        }

        @JavascriptInterface
        fun getToken(): String {
            val prefs = getSharedPreferences("tesla_prefs", MODE_PRIVATE)
            return prefs.getString("access_token", "") ?: ""
        }

        @JavascriptInterface
        fun setVibrationPattern(pattern: String) {
            runOnUiThread {
                MyFirebaseMessagingService.resetNotificationChannels(this@MainActivity, pattern)
            }
        }
    }

    private fun startKeepAlive() {
        if (isKeepAliveRunning) return
        isKeepAliveRunning = true

        keepAliveJob = Thread {
            while (isKeepAliveRunning) {
                try {
                    val request = Request.Builder().url("$RENDER_BASE_URL/health").build()
                    client.newCall(request).execute().close()
                } catch (e: Exception) {
                    showErrorOnScreen("Keep-Alive 실패: ${e.message}")
                }
                Thread.sleep(8 * 60 * 1000L)
            }
        }.apply { start() }
    }

    private fun stopKeepAlive() {
        isKeepAliveRunning = false
        keepAliveJob?.interrupt()
        keepAliveJob = null
    }
}
