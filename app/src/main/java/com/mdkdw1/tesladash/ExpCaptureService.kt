package com.mdkdw1.tesladash

import android.app.Activity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
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
import android.util.Log
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

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private val textRecognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val resultCode = intent?.getIntExtra("RESULT_CODE", Activity.RESULT_CANCELED) ?: Activity.RESULT_CANCELED
        val resultData: Intent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent?.getParcelableExtra("RESULT_DATA", Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent?.getParcelableExtra("RESULT_DATA")
        }

        if (resultCode == Activity.RESULT_OK && resultData != null) {
            startForegroundServiceNotification()

            val projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            mediaProjection = projectionManager.getMediaProjection(resultCode, resultData)

            setupVirtualDisplayAndCapture()
        } else {
            Toast.makeText(this, "화면 공유 권한 데이터가 전달되지 않았습니다.", Toast.LENGTH_SHORT).show()
            stopSelf()
        }

        return START_NOT_STICKY
    }

    private fun startForegroundServiceNotification() {
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("제2 경험치 자동 인식 중")
            .setContentText("게임 화면의 경험치를 실시간으로 감지하고 있습니다.")
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
                "경험치 인지 서비스",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    private fun setupVirtualDisplayAndCapture() {
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

        // 1.5초 후 화면 수집 및 OCR 실행
        Handler(Looper.getMainLooper()).postDelayed({
            captureAndRecognize(width, height)
        }, 1500)
    }

    private fun captureAndRecognize(width: Int, height: Int) {
        try {
            val image = imageReader?.acquireLatestImage()
            if (image == null) {
                Toast.makeText(this, "화면 읽기 실패. 다시 시도해 주세요.", Toast.LENGTH_SHORT).show()
                return
            }

            val planes = image.planes
            val buffer: ByteBuffer = planes[0].buffer
            val pixelStride = planes[0].pixelStride
            val rowStride = planes[0].rowStride
            val rowPadding = rowStride - pixelStride * width

            val bitmapWidth = width + rowPadding / pixelStride
            var bitmap = Bitmap.createBitmap(bitmapWidth, height, Bitmap.Config.ARGB_8888)
            bitmap.copyPixelsFromBuffer(buffer)
            image.close()

            // 가로 기준 화면 좌측 하단 (가로 0~45%, 세로 55~100%) 크롭
            val cropX = 0
            val cropY = (height * 0.55).toInt()
            val cropWidth = (bitmapWidth * 0.45).toInt().coerceAtMost(bitmapWidth)
            val cropHeight = (height * 0.45).toInt().coerceAtMost(height - cropY)

            val croppedBitmap = Bitmap.createBitmap(bitmap, cropX, cropY, cropWidth, cropHeight)
            bitmap.recycle()

            val visionImage = InputImage.fromBitmap(croppedBitmap, 0)
            textRecognizer.process(visionImage)
                .addOnSuccessListener { visionText ->
                    parseNumbersAndShowToast(visionText.text)
                }
                .addOnFailureListener { e ->
                    Log.e(TAG, "OCR 실패: ${e.message}")
                }
        } catch (e: Exception) {
            Log.e(TAG, "캡처 예외 발생", e)
        }
    }

    private fun parseNumbersAndShowToast(recognizedText: String) {
        val regex = Regex("(\\d+)\\D+(\\d+)")
        val matchResult = regex.find(recognizedText)

        if (matchResult != null) {
            val baseExp = matchResult.groupValues[1].toInt()
            val bonusExp = matchResult.groupValues[2].toInt()
            val totalExp = baseExp + bonusExp
            Toast.makeText(this, "🎯 인식 성공: $baseExp (+ $bonusExp) = 총 $totalExp", Toast.LENGTH_LONG).show()
        } else {
            Toast.makeText(this, "숫자 인식 실패 (텍스트: $recognizedText)", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        virtualDisplay?.release()
        mediaProjection?.stop()
    }
}
