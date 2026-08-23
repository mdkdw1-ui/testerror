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
import android.graphics.drawable.GradientDrawable
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
    private var controlOverlayView: View? = null
    
    // 🎯 직접 지정 가능한 스캔 영역 박스 관련
    private var roiBoxView: View? = null
    private var roiLayoutParams: WindowManager.LayoutParams? = null
    private var isRoiBoxVisible = true

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
    private lateinit var btnToggleRoiBox: Button

    override fun onCreate() {
        super.onCreate()
        try {
            createNotificationChannel()
            startForegroundServiceNotification()
            setupOverlayUI()
            setupRoiBoxUI()
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

                // 안드로이드 14 필수 콜백 등록
                mediaProjection?.registerCallback(object : MediaProjection.Callback() {
                    override fun onStop() {
                        super.onStop()
                        Log.d(TAG, "MediaProjection 중단됨")
                    }
                }, handler)

                setupVirtualDisplay()
                showToastOnMainThread("✅ 초록색 박스를 경험치 글자 위치로 드래그하세요!")
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

    // 1. 메인 컨트롤 오버레이 UI
    private fun setupOverlayUI() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            showToastOnMainThread("🚨 오버레이 권한이 필요합니다.")
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
                x = 60
                y = 150
            }

            val container = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundColor(Color.argb(220, 20, 20, 20))
                setPadding(20, 16, 20, 16)
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
                setPadding(0, 4, 0, 8)
            }

            // 첫 번째 줄 버튼 (시작/정지, 리셋)
            val btnRow1 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }

            btnToggle = Button(this).apply {
                text = "▶ 측정 시작"
                textSize = 11f
                setBackgroundColor(Color.parseColor("#4CAF50"))
                setTextColor(Color.WHITE)
                setOnClickListener { toggleMeasurement() }
            }

            val btnReset = Button(this).apply {
                text = "🔄 리셋"
                textSize = 11f
                setBackgroundColor(Color.parseColor("#757575"))
                setTextColor(Color.WHITE)
                setOnClickListener { resetData() }
            }

            btnRow1.addView(btnToggle)
            btnRow1.addView(btnReset)

            // 두 번째 줄 버튼 (영역 박스 숨김/표시, 종료)
            val btnRow2 = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, 4, 0, 0)
            }

            btnToggleRoiBox = Button(this).apply {
                text = "👁️ 박스 숨기기"
                textSize = 11f
                setBackgroundColor(Color.parseColor("#0288D1"))
                setTextColor(Color.WHITE)
                setOnClickListener { toggleRoiBoxVisibility() }
            }

            val btnExit = Button(this).apply {
                text = "❌ 종료"
                textSize = 11f
                setBackgroundColor(Color.parseColor("#D32F2F"))
                setTextColor(Color.WHITE)
                setOnClickListener {
                    Toast.makeText(this@ExpCaptureService, "측정 서비스를 종료합니다.", Toast.LENGTH_SHORT).show()
                    stopSelf()
                }
            }

            btnRow2.addView(btnToggleRoiBox)
            btnRow2.addView(btnExit)

            container.addView(tvExpPerMin)
            container.addView(tvTotalExp)
            container.addView(btnRow1)
            container.addView(btnRow2)

            // 컨트롤 창 드래그
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

            controlOverlayView = container
            windowManager?.addView(controlOverlayView, layoutParams)
        } catch (e: Exception) {
            Log.e(TAG, "컨트롤 창 생성 에러: ${e.message}", e)
        }
    }

    // 2. 사용자가 조절 가능한 초록색 영역 박스 UI
    private fun setupRoiBoxUI() {
        try {
            roiLayoutParams = WindowManager.LayoutParams(
                550, // 초기 너비 (px)
                300, // 초기 높이 (px)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                else
                    @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x = 100
                y = 1000 // 초기 Y 위치
            }

            val container = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                
                // 초록색 테두리 + 반투명 연두색 배경
                val border = GradientDrawable().apply {
                    setShape(GradientDrawable.RECTANGLE)
                    setStroke(6, Color.GREEN)
                    setColor(Color.argb(50, 0, 255, 0))
                }
                background = border
                setPadding(10, 10, 10, 10)
            }

            // 상단 레이블 & 크기 조절 컨트롤
            val titleLayout = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setBackgroundColor(Color.argb(180, 0, 0, 0))
                setPadding(6, 4, 6, 4)
            }

            val tvTitle = TextView(this).apply {
                text = "🎯 스캔 영역"
                setTextColor(Color.GREEN)
                textSize = 10f
                setTypeface(null, android.graphics.Typeface.BOLD)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }

            val btnWMinus = createSmallButton("가로-") { resizeRoiBox(-50, 0) }
            val btnWPlus = createSmallButton("가로+") { resizeRoiBox(50, 0) }
            val btnHMinus = createSmallButton("세로-") { resizeRoiBox(0, -30) }
            val btnHPlus = createSmallButton("세로+") { resizeRoiBox(0, 30) }

            titleLayout.addView(tvTitle)
            titleLayout.addView(btnWMinus)
            titleLayout.addView(btnWPlus)
            titleLayout.addView(btnHMinus)
            titleLayout.addView(btnHPlus)

            container.addView(titleLayout)

            // 드래그로 스캔 박스 위치 이동
            var initialX = 0
            var initialY = 0
            var initialTouchX = 0f
            var initialTouchY = 0f

            container.setOnTouchListener { _, event ->
                val params = roiLayoutParams ?: return@setOnTouchListener false
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        initialX = params.x
                        initialY = params.y
                        initialTouchX = event.rawX
                        initialTouchY = event.rawY
                        true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        params.x = initialX + (event.rawX - initialTouchX).toInt()
                        params.y = initialY + (event.rawY - initialTouchY).toInt()
                        windowManager?.updateViewLayout(container, params)
                        true
                    }
                    else -> false
                }
            }

            roiBoxView = container
            windowManager?.addView(roiBoxView, roiLayoutParams)
        } catch (e: Exception) {
            Log.e(TAG, "스캔 영역 박스 생성 에러: ${e.message}", e)
        }
    }

    private fun createSmallButton(textStr: String, onClick: () -> Unit): Button {
        return Button(this).apply {
            text = textStr
            textSize = 8f
            setPadding(4, 0, 4, 0)
            setBackgroundColor(Color.DKGRAY)
            setTextColor(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                60
            ).apply { setMargins(2, 0, 2, 0) }
            setOnClickListener { onClick() }
        }
    }

    private fun resizeRoiBox(deltaW: Int, deltaH: Int) {
        val params = roiLayoutParams ?: return
        val newW = (params.width + deltaW).coerceAtLeast(200)
        val newH = (params.height + deltaH).coerceAtLeast(120)
        params.width = newW
        params.height = newH
        roiBoxView?.let { windowManager?.updateViewLayout(it, params) }
    }

    private fun toggleRoiBoxVisibility() {
        isRoiBoxVisible = !isRoiBoxVisible
        if (isRoiBoxVisible) {
            roiBoxView?.visibility = View.VISIBLE
            btnToggleRoiBox.text = "👁️ 박스 숨기기"
        } else {
            roiBoxView?.visibility = View.GONE
            btnToggleRoiBox.text = "👁️ 박스 표시"
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

            // 🎯 사용자가 지정한 초록색 박스의 X, Y, 너비, 높이 좌표로 정확히 크롭
            val roiParams = roiLayoutParams
            val cropX = (roiParams?.x ?: 0).coerceIn(0, bitmapWidth - 1)
            val cropY = (roiParams?.y ?: 0).coerceIn(0, height - 1)
            val cropWidth = (roiParams?.width ?: bitmapWidth).coerceAtMost(bitmapWidth - cropX)
            val cropHeight = (roiParams?.height ?: height).coerceAtMost(height - cropY)

            if (cropWidth <= 10 || cropHeight <= 10) {
                bitmap.recycle()
                return
            }

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
            .setContentText("초록색 박스를 스캔할 영역으로 맞추세요.")
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
        if (controlOverlayView != null) {
            windowManager?.removeView(controlOverlayView)
        }
        if (roiBoxView != null) {
            windowManager?.removeView(roiBoxView)
        }
        virtualDisplay?.release()
        mediaProjection?.stop()
    }
}
