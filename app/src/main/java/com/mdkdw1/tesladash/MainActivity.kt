package com.mdkdw1.tesladash

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.widget.Button
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    private val TAG = "ExpHelper_Main"
    private val REQUEST_MEDIA_PROJECTION = 1001
    private val REQUEST_OVERLAY_PERMISSION = 1002
    private lateinit var mediaProjectionManager: MediaProjectionManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 🛠️ 전역 예외 처리기: 강제 종료(크래시) 발생 시 에러 메시지를 화면에 팝업으로 표시
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            Log.e(TAG, "Uncaught Exception in thread ${thread.name}", throwable)
            val errorDetails = "🚨 크래시 발생:\n${throwable.javaClass.simpleName}: ${throwable.message}\n" +
                    (throwable.stackTrace.firstOrNull()?.let { "위치: ${it.fileName}:${it.lineNumber}" } ?: "")
            
            Looper.prepare()
            Toast.makeText(applicationContext, errorDetails, Toast.LENGTH_LONG).show()
            Looper.loop()
        }

        setContentView(createSimpleLayout())
        mediaProjectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
    }

    private fun createSimpleLayout(): android.view.View {
        val layout = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(60, 100, 60, 60)
        }

        val btnStart = Button(this).apply {
            text = "경험치 감지 실행 (화면 공유)"
            textSize = 18f
            setOnClickListener {
                try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this@MainActivity)) {
                        Toast.makeText(this@MainActivity, "다른 앱 위에 표시 권한을 허용해주세요.", Toast.LENGTH_LONG).show()
                        val intent = Intent(
                            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            Uri.parse("package:$packageName")
                        )
                        startActivityForResult(intent, REQUEST_OVERLAY_PERMISSION)
                    } else {
                        startScreenCaptureRequest()
                    }
                } catch (e: Exception) {
                    showErrorToast("권한 요청 중 에러: ${e.message}")
                }
            }
        }
        layout.addView(btnStart)

        return layout
    }

    private fun startScreenCaptureRequest() {
        try {
            val captureIntent = mediaProjectionManager.createScreenCaptureIntent()
            startActivityForResult(captureIntent, REQUEST_MEDIA_PROJECTION)
        } catch (e: Exception) {
            Log.e(TAG, "화면 캡처 Intent 생성 실패: ${e.message}", e)
            showErrorToast("캡처 요청 실패: ${e.message}")
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        if (requestCode == REQUEST_OVERLAY_PERMISSION) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && Settings.canDrawOverlays(this)) {
                Toast.makeText(this, "오버레이 권한 허용 완료", Toast.LENGTH_SHORT).show()
            }
        } else if (requestCode == REQUEST_MEDIA_PROJECTION) {
            if (resultCode == Activity.RESULT_OK && data != null) {
                Log.d(TAG, "화면 공유 권한 획득 성공")

                val serviceIntent = Intent(this, ExpCaptureService::class.java).apply {
                    putExtra("RESULT_CODE", resultCode)
                    putExtra("RESULT_DATA", data)
                }

                try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        startForegroundService(serviceIntent)
                    } else {
                        startService(serviceIntent)
                    }
                    
                    // 안드로이드 14 보안 이슈 방지: 서비스 가동 준비를 위해 0.5초 후 백그라운드 전환
                    Handler(Looper.getMainLooper()).postDelayed({
                        try {
                            moveTaskToBack(true)
                        } catch (e: Exception) {
                            Log.e(TAG, "moveTaskToBack 에러", e)
                        }
                    }, 500)

                } catch (e: Exception) {
                    Log.e(TAG, "서비스 시작 오류: ${e.message}", e)
                    showErrorToast("서비스 구동 에러: ${e.javaClass.simpleName} - ${e.message}")
                }
            } else {
                showErrorToast("화면 공유 권한이 거부되었습니다. (Code: $resultCode)")
            }
        }
    }

    private fun showErrorToast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
    }
}
