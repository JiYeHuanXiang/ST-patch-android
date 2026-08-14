package com.st.patch

import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.WindowManager
import android.webkit.WebView
import android.widget.Button
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import kotlin.concurrent.thread

/**
 * 启动页：显示 Logo + 进度状态 + 实时日志，自动编排 Node 启动 → WebView 加载流程。
 *
 * 流程：
 * 1. 显示 Logo + 进度条 + 实时日志窗口
 * 2. 启动 NodeService（解压资源 + 启动 Node）
 * 3. 后台定时刷新日志窗口，让用户看到解压/编译/启动全过程
 * 4. 监听 SERVER_READY 广播 → 启动 WebViewActivity
 */
class SplashActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "SplashActivity"
        private const val LOG_REFRESH_MS = 300L
    }

    private lateinit var statusText: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var retryButton: Button
    private lateinit var logTextView: TextView
    private lateinit var logScrollView: ScrollView
    private lateinit var copyLogButton: Button

    private val mainHandler = Handler(Looper.getMainLooper())
    private var logRefreshActive = true
    private var displayedLogCount = 0

    private val logRefresher = object : Runnable {
        override fun run() {
            if (logRefreshActive && !isFinishing && !isDestroyed) {
                updateLogText()
                mainHandler.postDelayed(this, LOG_REFRESH_MS)
            }
        }
    }

    private val serverReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                NodeService.BROADCAST_SERVER_READY -> {
                    runOnUiThread {
                        statusText.text = "服务器已就绪，正在打开..."
                        // 停止日志刷新
                        logRefreshActive = false
                        mainHandler.removeCallbacks(logRefresher)
                        updateLogText()
                        openWebView()
                    }
                }
                NodeService.BROADCAST_SERVER_FAILED -> {
                    runOnUiThread {
                        statusText.text = "服务器启动失败\n${NodeService.lastErrorPublic}"
                        progressBar.visibility = View.GONE
                        retryButton.visibility = View.VISIBLE
                        // 停止日志刷新，显示最终日志
                        logRefreshActive = false
                        mainHandler.removeCallbacks(logRefresher)
                        updateLogText()
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
        retryButton = findViewById(R.id.retryButton)
        logTextView = findViewById(R.id.logTextView)
        logScrollView = findViewById(R.id.logScrollView)
        copyLogButton = findViewById(R.id.copyLogButton)

        retryButton.setOnClickListener {
            progressBar.visibility = View.VISIBLE
            retryButton.visibility = View.GONE
            statusText.text = "正在重启 SillyTavern..."
            displayedLogCount = 0
            logTextView.text = ""
            logRefreshActive = true
            mainHandler.post(logRefresher)
            startNodeService()
        }

        copyLogButton.setOnClickListener {
            val logText = getFormattedLog()
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("SillyTavern Log", logText))
            Toast.makeText(this, "日志已复制到剪贴板", Toast.LENGTH_SHORT).show()
        }

        // 复制按钮需要重建完整日志文本
        logTextView.text = ""  // 初始空文本，由 updateLogText 追加

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
        startNodeService()

        // 启动日志实时刷新
        mainHandler.post(logRefresher)

        // 兜底：HTTP 轮询，防止广播丢失
        startPolling()

        // 预热 WebView 引擎（与 Node 启动并行，缩短就绪后的等待）
        mainHandler.post { warmUpWebView() }
    }

    private fun startNodeService() {
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
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }
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
                // Node 日志自报就绪也视为成功（兜底广播丢失场景）
                if (NodeService.nodeReportedReady) {
                    runOnUiThread { openWebView() }
                    return@thread
                }
                try { Thread.sleep(1000) } catch (_: InterruptedException) { return@thread }
            }

            // 90 秒轮询结束仍未就绪——不再静默卡死。
            // 若 NodeService 已有错误则呈现失败 UI，否则提示启动耗时过长并显示重试按钮。
            if (isFinishing || isDestroyed) return@thread
            runOnUiThread {
                logRefreshActive = false
                mainHandler.removeCallbacks(logRefresher)
                updateLogText()
                progressBar.visibility = View.GONE
                retryButton.visibility = View.VISIBLE
                statusText.text = if (NodeService.lastErrorPublic.isNotEmpty()) {
                    "服务器启动失败\n${NodeService.lastErrorPublic}"
                } else {
                    "启动耗时较长，请查看日志或点重试"
                }
            }
        }
    }

    private fun updateLogText() {
        val newLines: List<String>
        synchronized(NodeService.nodeLogLines) {
            if (displayedLogCount >= NodeService.nodeLogLines.size) {
                return
            }
            newLines = NodeService.nodeLogLines.subList(displayedLogCount, NodeService.nodeLogLines.size)
            displayedLogCount = NodeService.nodeLogLines.size
        }

        // 判断用户是否正在主动上滑查看历史日志；若是，不要自动滚动到底部
        val canAutoScroll = logScrollView.scrollY + logScrollView.height >= logScrollView.getChildAt(0).height - 32

        val sb = StringBuilder(logTextView.text)
        for (line in newLines) {
            if (sb.isNotEmpty()) sb.append('\n')
            sb.append(line)
        }
        logTextView.text = sb.toString()

        if (canAutoScroll) {
            logScrollView.post {
                logScrollView.fullScroll(View.FOCUS_DOWN)
            }
        }
    }

    private fun getFormattedLog(): String {
        val sb = StringBuilder()
        synchronized(NodeService.nodeLogLines) {
            for (line in NodeService.nodeLogLines) {
                sb.appendLine(line)
            }
        }
        return sb.toString().trimEnd()
    }

    /**
     * 预热 WebView 引擎：WebView 冷启动（加载 provider、拉起渲染进程）通常要数百 ms。
     * 在等待 Node 启动的空档提前触发，使随后 WebViewActivity 中的 WebView 创建更快。
     * 个别 ROM/WebView 实现预热失败时静默忽略，不影响启动流程。
     */
    private fun warmUpWebView() {
        try {
            val warm = WebView(this)
            warm.destroy()
        } catch (_: Throwable) {
            // 预热失败不影响启动
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
        logRefreshActive = false
        mainHandler.removeCallbacks(logRefresher)
        try {
            unregisterReceiver(serverReceiver)
        } catch (e: Exception) {
            // 未注册时忽略
        }
        super.onDestroy()
    }
}
