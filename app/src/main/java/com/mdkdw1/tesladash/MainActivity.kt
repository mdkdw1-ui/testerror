package com.mdkdw1.tesladash

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.nio.ByteBuffer

class MainActivity : AppCompatActivity() {

    private lateinit var projectionManager: MediaProjectionManager
    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null

    private val SCREEN_CAPTURE_REQUEST_CODE = 1000
    private val textRecognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    private lateinit var tvResult: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(createDynamicLayout())
        projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
    }

    private fun createDynamicLayout(): android.view.View {
        val layout = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(50, 50, 50, 50)
        }

        tvResult = TextView(this).apply {
            text = "자동 인식 결과가 여기에 표시됩니다."
            textSize = 18f
            setPadding(0, 0, 0, 30)
        }
        layout.addView(tvResult)

        val btnStartCapture = Button(this).apply {
            text = "게임 화면 자동 인식 및 계산 시작"
            setOnClickListener {
                val intent = projectionManager.createScreenCaptureIntent()
                startActivityForResult(intent, SCREEN_CAPTURE_REQUEST_CODE)
            }
        }
        layout.addView(btnStartCapture)

        return layout
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == SCREEN_CAPTURE_REQUEST_CODE && resultCode == Activity.RESULT_OK) {
            if (data != null) {
                mediaProjection = projectionManager.getMediaProjection(resultCode, data)
                setupVirtualDisplayAndCapture()
            }
        } else {
            Toast.makeText(this, "화면 캡처 권한이 거부되었습니다.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun setupVirtualDisplayAndCapture() {
        val metrics = resources.displayMetrics
        val width = metrics.widthPixels
        val height = metrics.heightPixels
        val density = metrics.densityDpi

        imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        virtualDisplay = mediaProjection?.createVirtualDisplay(
            "ScreenCapture",
            width, height, density,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader?.surface, null, null
        )

        Handler(Looper.getMainLooper()).postDelayed({
            captureScreenAndExtractText(width, height)
        }, 1000)
    }

    private fun captureScreenAndExtractText(width: Int, height: Int) {
        val image = imageReader?.acquireLatestImage() ?: return
        val planes = image.planes
        val buffer: ByteBuffer = planes[0].buffer
        val pixelStride = planes[0].pixelStride
        val rowStride = planes[0].rowStride
        val rowPadding = rowStride - pixelStride * width

        var bitmap = Bitmap.createBitmap(width + rowPadding / pixelStride, height, Bitmap.Config.ARGB_8888)
        bitmap.copyPixelsFromBuffer(buffer)
        image.close()

        // 왼쪽 아래 영역 크롭 (가로 0~40%, 세로 60~100%)
        val cropX = 0
        val cropY = (height * 0.6).toInt()
        val cropWidth = (width * 0.4).toInt()
        val cropHeight = (height * 0.4).toInt()

        val croppedBitmap = Bitmap.createBitmap(bitmap, cropX, cropY, cropWidth, cropHeight)
        bitmap.recycle()

        val visionImage = InputImage.fromBitmap(croppedBitmap, 0)
        textRecognizer.process(visionImage)
            .addOnSuccessListener { visionText ->
                parseNumbersAndCalculate(visionText.text)
            }
            .addOnFailureListener { e ->
                tvResult.text = "OCR 인식 실패: ${e.message}"
            }
    }

    private fun parseNumbersAndCalculate(recognizedText: String) {
        val regex = Regex("(\\d+)\\D+(\\d+)")
        val matchResult = regex.find(recognizedText)

        if (matchResult != null) {
            val baseExp = matchResult.groupValues[1].toInt()
            val bonusExp = matchResult.groupValues[2].toInt()
            val totalExp = baseExp + bonusExp
            tvResult.text = "인식 성공!\n- 기본 경험치: $baseExp\n- 보너스 경험치: $bonusExp\n- 총 합산: $totalExp"
        } else {
            tvResult.text = "숫자를 찾지 못했습니다.\n인식된 텍스트: $recognizedText"
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        virtualDisplay?.release()
        mediaProjection?.stop()
    }
}
