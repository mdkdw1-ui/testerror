package com.mdkdw1.tesladash

import android.app.Activity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.NotificationCompat
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.nio.ByteBuffer

class ExpCaptureService : Service() {

    companion object {
        private const val TAG = "ExpCaptureService"
        private const val CHANNEL_ID = "ExpCaptureChannel"
        private const val NOTIFICATION_ID = 2001
    }

    private var windowManager: WindowManager? = null
    private var overlayView: View? = null

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private val textRecognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    private val handler = Handler(Looper.getMainLooper())
    private var isMeasuring = false

    private var totalExpAccumulated: Long = 0L
    private var startTimeMs: Long = 0L
    private var lastFrameLines = setOf<String>()

    private lateinit var tvExpPerMin: TextView
    private lateinit var tvTotalExp: TextView
    private lateinit var btnToggle: Button

    override fun onCreate() {
        super.onCreate()
        try {
            createNotificationChannel()
            // 안드로이드 14 필수: Service 생성 직후 가장 먼저 포그라운드 전환 선언
            startForegroundServiceNotification()
            setupOverlayUI()
        } catch (e: Exception) {
            Log.e(TAG, "Service onCreate 에러: ${e.message}", e)
            showToastOnMainThread("서비스 초기화 에러: ${e.message}")
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            val resultCode = intent?.getIntExtra("RESULT_CODE", Activity.RESULT_CANCELED) ?: Activity.RESULT_CANCELED
            val resultData: Intent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent?.getParcelableExtra("RESULT_DATA", Intent::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent?.getParcelableExtra("RESULT_DATA")
            }

            if (resultCode == Activity.RESULT_OK && resultData != null) {
                val projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                mediaProjection = projectionManager.getMediaProjection(resultCode, resultData)

                if (mediaProjection == null) {
                    showToastOnMainThread("🚨 MediaProjection 생성 실패 (null)")
                    stopSelf()
                    return START_NOT_STICKY
                }

                setupVirtualDisplay()
                showToastOnMainThread("✅ 오버레이 창 구동 성공! 게임 화면으로 이동하세요.")
            } else {
                showToastOnMainThread("🚨 화면 공유 데이터 유실 (Code: $resultCode)")
                stopSelf()
            }
        } catch (e: Exception) {
            Log.e(TAG, "onStartCommand 에러: ${e.message}", e)
            showToastOnMainThread("🚨 서비스 구동 실패: ${e.javaClass.simpleName} - ${e.message}")
            stopSelf()
        }

        return START_NOT_STICKY
    }

    private fun setupOverlayUI() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            showToastOnMainThread("🚨 오버레이 권한이 비활성화되어 있습니다.")
            return
        }

        try {
            windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager

            val layoutParams = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                else
                    @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x = 100
                y = 300
            }

            val container = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundColor(Color.argb(220, 20, 20, 20))
                setPadding(24, 20, 24, 20)
            }

            tvExpPerMin = TextView(this).apply {
                text = "⚡ 분당 XP: 0"
                setTextColor(Color.YELLOW)
                textSize = 15f
                setTypeface(null, android.graphics.Typeface.BOLD)
            }

            tvTotalExp = TextView(this).apply {
                text = "📊 누적: 0 XP (0초)"
                setTextColor(Color.WHITE)
                textSize = 12f
                setPadding(0, 4, 0, 12)
            }

            val btnLayout = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
            }

            btnToggle = Button(this).apply {
                text = "▶ 측정 시작"
                textSize = 12f
                setBackgroundColor(Color.parseColor("#4CAF50"))
                setTextColor(Color.WHITE)
                setOnClickListener { toggleMeasurement() }
            }

            val btnReset = Button(this).apply {
                text = "🔄 리셋"
                textSize = 12f
                setBackgroundColor(Color.parseColor("#757575"))
                setTextColor(Color.WHITE)
                setOnClickListener { resetData() }
            }

            btnLayout.addView(btnToggle)
            btnLayout.addView(btnReset)

            container.addView(tvExpPerMin)
            container.addView(tvTotalExp)
            container.addView(btnLayout)

            // 드래그 기능
            var initialX = 0
            var initialY = 0
            var initialTouchX = 0f
            var initialTouchY = 0f

            container.setOnTouchListener { _, event ->
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        initialX = layoutParams.x
                        initialY = layoutParams.y
                        initialTouchX = event.rawX
                        initialTouchY = event.rawY
                        true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        layoutParams.x = initialX + (event.rawX - initialTouchX).toInt()
                        layoutParams.y = initialY + (event.rawY - initialTouchY).toInt()
                        windowManager?.updateViewLayout(container, layoutParams)
                        true
                    }
                    else -> false
                }
            }

            overlayView = container
            windowManager?.addView(overlayView, layoutParams)
        } catch (e: Exception) {
            Log.e(TAG, "오버레이 창 생성 에러: ${e.message}", e)
            showToastOnMainThread("오버레이 생성 실패: ${e.message}")
        }
    }

    private fun toggleMeasurement() {
        isMeasuring = !isMeasuring
        if (isMeasuring) {
            if (startTimeMs == 0L) {
                startTimeMs = System.currentTimeMillis()
            }
            btnToggle.text = "⏹ 정지"
            btnToggle.setBackgroundColor(Color.parseColor("#F44336"))
            handler.post(captureRunnable)
            Toast.makeText(this, "경험치 측정 시작", Toast.LENGTH_SHORT).show()
        } else {
            btnToggle.text = "▶ 다시 시작"
            btnToggle.setBackgroundColor(Color.parseColor("#4CAF50"))
            handler.removeCallbacks(captureRunnable)
            Toast.makeText(this, "측정 일시정지", Toast.LENGTH_SHORT).show()
        }
    }

    private fun resetData() {
        totalExpAccumulated = 0L
        startTimeMs = if (isMeasuring) System.currentTimeMillis() else 0L
        lastFrameLines = emptySet()
        updateUI(0)
        Toast.makeText(this, "측정 데이터 초기화 완료", Toast.LENGTH_SHORT).show()
    }

    private val captureRunnable = object : Runnable {
        override fun run() {
            if (isMeasuring) {
                captureAndProcessScreen()
                handler.postDelayed(this, 1000)
            }
        }
    }

    private fun setupVirtualDisplay() {
        try {
            val metrics = resources.displayMetrics
            val width = metrics.widthPixels
            val height = metrics.heightPixels
            val density = metrics.densityDpi

            imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
            virtualDisplay = mediaProjection?.createVirtualDisplay(
                "ExpScreenCapture",
                width, height, density,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader?.surface, null, null
            )
        } catch (e: Exception) {
            Log.e(TAG, "VirtualDisplay 생성 실패: ${e.message}", e)
            showToastOnMainThread("가상 디스플레이 에러: ${e.message}")
        }
    }

    private fun captureAndProcessScreen() {
        try {
            val image = imageReader?.acquireLatestImage() ?: return

            val planes = image.planes
            val buffer: ByteBuffer = planes[0].buffer
            val pixelStride = planes[0].pixelStride
            val rowStride = planes[0].rowStride
            val width = resources.displayMetrics.widthPixels
            val height = resources.displayMetrics.heightPixels
            val rowPadding = rowStride - pixelStride * width

            val bitmapWidth = width + rowPadding / pixelStride
            var bitmap = Bitmap.createBitmap(bitmapWidth, height, Bitmap.Config.ARGB_8888)
            bitmap.copyPixelsFromBuffer(buffer)
            image.close()

            val cropX = 0
            val cropY = (height * 0.55).toInt()
            val cropWidth = (bitmapWidth * 0.45).toInt().coerceAtMost(bitmapWidth)
            val cropHeight = (height * 0.45).toInt().coerceAtMost(height - cropY)

            val croppedBitmap = Bitmap.createBitmap(bitmap, cropX, cropY, cropWidth, cropHeight)
            bitmap.recycle()

            val visionImage = InputImage.fromBitmap(croppedBitmap, 0)
            textRecognizer.process(visionImage)
                .addOnSuccessListener { visionText ->
                    processRecognizedText(visionText.text)
                }
                .addOnFailureListener { e ->
                    Log.e(TAG, "OCR 오류: ${e.message}")
                }
        } catch (e: Exception) {
            Log.e(TAG, "캡처 예외 발생", e)
        }
    }

    private fun processRecognizedText(recognizedText: String) {
        val currentLines = recognizedText.lines()
            .map { it.trim() }
            .filter { it.isNotEmpty() }

        val newLines = currentLines.filter { !lastFrameLines.contains(it) }

        var frameGainedExp = 0L
        val regex = Regex("(\\d+)\\D+(\\d+)")

        for (line in newLines) {
            val match = regex.find(line)
            if (match != null) {
                val baseExp = match.groupValues[1].toLongOrNull() ?: 0L
                val bonusExp = match.groupValues[2].toLongOrNull() ?: 0L
                frameGainedExp += (baseExp + bonusExp)
            }
        }

        if (frameGainedExp > 0) {
            totalExpAccumulated += frameGainedExp
        }

        lastFrameLines = currentLines.toSet()
        updateUI(frameGainedExp)
    }

    private fun updateUI(gainedThisFrame: Long) {
        val elapsedSec = if (startTimeMs > 0) ((System.currentTimeMillis() - startTimeMs) / 1000).coerceAtLeast(1) else 1
        val expPerMin = (totalExpAccumulated.toDouble() / elapsedSec) * 60

        tvExpPerMin.text = "⚡ 분당 XP: ${String.format("%.1f", expPerMin)}"
        tvTotalExp.text = "📊 누적: ${totalExpAccumulated} XP (${elapsedSec}초)"
    }

    private fun startForegroundServiceNotification() {
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("제2 경험치 측정 동작 중")
            .setContentText("게임 화면 위에 오버레이 위젯이 표시됩니다.")
            .setSmallIcon(android.R.drawable.sym_def_app_icon)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "경험치 자동 측정 서비스",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    private fun showToastOnMainThread(msg: String) {
        Handler(Looper.getMainLooper()).post {
            Toast.makeText(applicationContext, msg, Toast.LENGTH_LONG).show()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacks(captureRunnable)
        if (overlayView != null) {
            windowManager?.removeView(overlayView)
        }
        virtualDisplay?.release()
        mediaProjection?.stop()
    }
}
