package com.example.expcalculator

import android.app.*
import android.content.*
import android.graphics.*
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.*
import android.provider.Settings
import android.view.*
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationCompat
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.nio.ByteBuffer
import java.util.regex.Pattern

// ==========================================
// 1. 메인 액티비티 (권한 요청 및 서비스 시작)
// ==========================================
class MainActivity : AppCompatActivity() {

    private val mediaProjectionManager by lazy {
        getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
    }

    private val captureLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK && result.data != null) {
            // 화면 캡처 권한 승인 시 서비스 시작
            val intent = Intent(this, ExpOverlayService::class.java).apply {
                putExtra("RESULT_CODE", result.resultCode)
                putExtra("DATA_INTENT", result.data)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
            finish() // 액티비티 종료 후 게임으로 복귀 준비
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 메인 UI (권한 실행 버튼)
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(50, 50, 50, 50)
        }

        val btnStart = Button(this).apply {
            text = "경험치 오버레이 시작하기"
            setOnClickListener { checkPermissionsAndStart() }
        }

        layout.addView(btnStart)
        setContentView(layout)
    }

    private fun checkPermissionsAndStart() {
        // 다른 앱 위에 그리기 권한 확인
        if (!Settings.canDrawOverlays(this)) {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            startActivity(intent)
            return
        }
        // 화면 캡처 권한 요청
        captureLauncher.launch(mediaProjectionManager.createScreenCaptureIntent())
    }
}

// ==========================================
// 2. 오버레이 & OCR 경험치 계산 서비스
// ==========================================
class ExpOverlayService : Service() {

    private lateinit var windowManager: WindowManager
    private var overlayView: View? = null

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null

    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    private val handler = Handler(Looper.getMainLooper())

    private var isMeasuring = false
    private var startTimeMs = 0L
    private var startExpSum = 0L

    private lateinit var tvStatus: TextView
    private lateinit var btnToggle: Button

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        startForegroundNotification()
        setupOverlayView()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val resultCode = intent?.getIntExtra("RESULT_CODE", Activity.RESULT_CANCELED) ?: Activity.RESULT_CANCELED
        val dataIntent = intent?.getParcelableExtra<Intent>("DATA_INTENT")

        if (resultCode == Activity.RESULT_OK && dataIntent != null) {
            val projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            mediaProjection = projectionManager.getMediaProjection(resultCode, dataIntent)
            setupVirtualDisplay()
        }
        return START_NOT_STICKY
    }

    // ------------------------------------------
    // 플로팅 오버레이 UI 생성 (드래그 가능)
    // ------------------------------------------
    private fun setupOverlayView() {
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.argb(200, 0, 0, 0)) // 반투명 검은색 배경
            setPadding(20, 20, 20, 20)
        }

        tvStatus = TextView(this).apply {
            text = "분당 EXP: 대기중..."
            setTextColor(Color.WHITE)
            textSize = 14f
        }

        btnToggle = Button(this).apply {
            text = "측정 시작"
            textSize = 12f
            setOnClickListener { toggleMeasurement() }
        }

        container.addView(tvStatus)
        container.addView(btnToggle)

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 100
            y = 300
        }

        // 터치 및 드래그 이동 처리
        container.setOnTouchListener(object : View.OnTouchListener {
            private var initialX = 0
            private var initialY = 0
            private var initialTouchX = 0f
            private var initialTouchY = 0f

            override fun onTouch(v: View?, event: MotionEvent): Boolean {
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        initialX = params.x
                        initialY = params.y
                        initialTouchX = event.rawX
                        initialTouchY = event.rawY
                        return true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        params.x = initialX + (event.rawX - initialTouchX).toInt()
                        params.y = initialY + (event.rawY - initialTouchY).toInt()
                        windowManager.updateViewLayout(container, params)
                        return true
                    }
                }
                return false
            }
        })

        overlayView = container
        windowManager.addView(overlayView, params)
    }

    // ------------------------------------------
    // 미디어 프로젝션 및 화면 캡처 설정
    // ------------------------------------------
    private fun setupVirtualDisplay() {
        val metrics = resources.displayMetrics
        val width = metrics.widthPixels
        val height = metrics.heightPixels

        imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        virtualDisplay = mediaProjection?.createVirtualDisplay(
            "ExpCapture",
            width, height, metrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader?.surface, null, null
        )
    }

    // ------------------------------------------
    // 경험치 측정 토글 및 주기적 계산
    // ------------------------------------------
    private fun toggleMeasurement() {
        if (!isMeasuring) {
            isMeasuring = true
            startTimeMs = System.currentTimeMillis()
            startExpSum = -1L // 첫 캡처 시 초기값 수집
            btnToggle.text = "중지"
            tvStatus.text = "스캔 중..."
            handler.post(captureRunnable)
        } else {
            isMeasuring = false
            btnToggle.text = "측정 시작"
            handler.removeCallbacks(captureRunnable)
        }
    }

    private val captureRunnable = object : Runnable {
        override fun run() {
            if (!isMeasuring) return
            processScreenCapture()
            handler.postDelayed(this, 5000) // 5초 간격 주기적 측정
        }
    }

    private fun processScreenCapture() {
        val image = imageReader?.acquireLatestImage() ?: return
        val planes = image.planes
        val buffer: ByteBuffer = planes[0].buffer
        val pixelStride = planes[0].pixelStride
        val rowStride = planes[0].rowStride
        val rowPadding = rowStride - pixelStride * image.width

        val bitmap = Bitmap.createBitmap(
            image.width + rowPadding / pixelStride,
            image.height,
            Bitmap.Config.ARGB_8888
        )
        bitmap.copyPixelsFromBuffer(buffer)
        image.close()

        // 화면 좌측 하단(경험치 표시 영역) Crop: Y축 80%~100%, X축 0%~40%
        val cropX = 0
        val cropY = (bitmap.height * 0.80).toInt()
        val cropW = (bitmap.width * 0.40).toInt()
        val cropH = (bitmap.height * 0.20).toInt()

        val croppedBitmap = Bitmap.createBitmap(bitmap, cropX, cropY, cropW, cropH)

        // ML Kit OCR 수행
        val inputImage = InputImage.fromBitmap(croppedBitmap, 0)
        recognizer.process(inputImage)
            .addOnSuccessListener { visionText ->
                parseAndCalculateExp(visionText.text)
            }
    }

    // ------------------------------------------
    // 텍스트 파싱 ("354 (+34)" 형태 숫자 합산 및 계산)
    // ------------------------------------------
    private fun parseAndCalculateExp(rawText: String) {
        val matcher = Pattern.compile("\\d+").matcher(rawText)
        val numbers = mutableListOf<Long>()
        while (matcher.find()) {
            numbers.add(matcher.group().toLong())
        }

        if (numbers.isEmpty()) return

        // 354 (+34) 형태에서 추출된 숫자들을 합산 (예: 354 + 34 = 388)
        val currentExpSum = numbers.sum()

        if (startExpSum < 0) {
            startExpSum = currentExpSum
            tvStatus.text = "기준점 설정 완료\n($currentExpSum)"
            return
        }

        val elapsedSec = (System.currentTimeMillis() - startTimeMs) / 1000
        if (elapsedSec > 0) {
            val gainedExp = currentExpSum - startExpSum
            // 분당 경험치 공식 = (획득 경험치 / 경과 초) * 60
            val expPerMin = (gainedExp.toDouble() / elapsedSec) * 60

            tvStatus.text = "분당 EXP: ${expPerMin.toInt()}\n(획득: +$gainedExp / ${elapsedSec}초)"
        }
    }

    // ------------------------------------------
    // 포그라운드 서비스 알림 설정
    // ------------------------------------------
    private fun startForegroundNotification() {
        val channelId = "exp_overlay_channel"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId, "경험치 오버레이",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }

        val notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("제2의나라 경험치 계산기")
            .setContentText("오버레이가 실행 중입니다.")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .build()

        startForeground(1, notification)
    }

    override fun onDestroy() {
        super.onDestroy()
        isMeasuring = false
        handler.removeCallbacks(captureRunnable)
        virtualDisplay?.release()
        mediaProjection?.stop()
        if (overlayView != null) windowManager.removeView(overlayView)
    }
}
