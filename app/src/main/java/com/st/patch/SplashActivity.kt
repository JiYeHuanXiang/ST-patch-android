package com.st.patch

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.View
import android.view.WindowManager
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import kotlin.concurrent.thread

/**
 * 启动页：显示 Logo + 进度状态，自动编排 Node 启动 → WebView 加载流程。
 *
 * 流程：
 * 1. 显示 Logo
 * 2. 启动 NodeService（解压资源 + 启动 Node）
 * 3. 监听 SERVER_READY 广播
 * 4. 启动 WebViewActivity
 */
class SplashActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "SplashActivity"
    }

    private lateinit var statusText: TextView
    private lateinit var progressBar: ProgressBar

    private val serverReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                NodeService.BROADCAST_SERVER_READY -> {
                    runOnUiThread {
                        statusText.text = "服务器已就绪，正在打开..."
                        openWebView()
                    }
                }
                NodeService.BROADCAST_SERVER_FAILED -> {
                    runOnUiThread {
                        statusText.text = "服务器启动失败，请查看日志"
                    }
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.setFlags(
            WindowManager.LayoutParams.FLAG_FULLSCREEN,
            WindowManager.LayoutParams.FLAG_FULLSCREEN
        )

        setContentView(R.layout.activity_splash)

        statusText = findViewById(R.id.statusText)
        progressBar = findViewById(R.id.splashProgressBar)

        // 注册广播接收器（Android 14+ 需要显式 flag）
        val filter = IntentFilter().apply {
            addAction(NodeService.BROADCAST_SERVER_READY)
            addAction(NodeService.BROADCAST_SERVER_FAILED)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(serverReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(serverReceiver, filter)
        }

        // 如果 NodeService 已就绪（保活恢复场景），直接打开 WebView
        if (NodeService.isRunning) {
            openWebView()
            return
        }

        // 启动 NodeService
        statusText.text = "正在启动 SillyTavern..."

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(android.Manifest.permission.POST_NOTIFICATIONS),
                    100
                )
            }
        }

        val serviceIntent = Intent(this, NodeService::class.java).apply {
            action = NodeService.ACTION_START
            putExtra(NodeService.EXTRA_VERSION, packageManager
                .getPackageInfo(packageName, 0).versionName)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }

        // 兜底：HTTP 轮询，防止广播丢失
        startPolling()
    }

    private fun startPolling() {
        thread(name = "splash-polling", isDaemon = true) {
            for (i in 1..90) {
                if (isFinishing || isDestroyed) return@thread
                if (NodeService.isRunning) {
                    runOnUiThread { openWebView() }
                    return@thread
                }
                try {
                    val conn = java.net.URL("http://127.0.0.1:8000").openConnection() as java.net.HttpURLConnection
                    conn.connectTimeout = 2000
                    conn.readTimeout = 2000
                    val code = conn.responseCode
                    conn.disconnect()
                    if (code in 200..399) {
                        runOnUiThread { openWebView() }
                        return@thread
                    }
                } catch (_: Exception) {}
                try { Thread.sleep(1000) } catch (_: InterruptedException) { return@thread }
            }
        }
    }

    private fun openWebView() {
        val intent = Intent(this, WebViewActivity::class.java).apply {
            putExtra(WebViewActivity.EXTRA_URL, "http://127.0.0.1:8000")
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        startActivity(intent)
        finish()
    }

    override fun onDestroy() {
        try {
            unregisterReceiver(serverReceiver)
        } catch (e: Exception) {
            // 未注册时忽略
        }
        super.onDestroy()
    }
}
