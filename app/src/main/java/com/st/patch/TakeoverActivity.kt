package com.st.patch

import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.st.patch.common.TakeoverBeacon
import com.st.patch.common.TermuxBridge
import kotlin.concurrent.thread

/**
 * 接管模式（经 Termux RUN_COMMAND）：
 * 1. 请求 com.termux.permission.RUN_COMMAND（dangerous 权限，需运行时授予）；
 * 2. 开 TakeoverBeacon（OS 分配空闲端口）并启动 TakeoverGuardService（前台保活），
 *    与 Termux 侧守卫脚本配合：app 一旦消失（划掉/崩溃/被杀）即 kill node，杜绝后台残留；
 * 3. 探 127.0.0.1:8000——若已在跑直接接管打开；否则生成 takeover.sh（特征扫描自动识别酒馆
 *    目录，目录名不限）经 RUN_COMMAND 启动；
 * 4. 轮询 8000 就绪后打开 WebViewActivity；退出时由 WebViewActivity 停止守卫服务。
 *
 * 限制：端口固定 8000；Termux 酒馆若改过端口需改回。执行反馈见 Termux 内 ~/st-takeover.log。
 */
class TakeoverActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "TakeoverActivity"
        private const val REQ_RUN_COMMAND = 7001
        private const val PERM_RUN_COMMAND = "com.termux.permission.RUN_COMMAND"
    }

    private lateinit var statusText: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var retryButton: Button

    private val mainHandler = Handler(Looper.getMainLooper())
    @Volatile private var polling = false
    @Volatile private var openedWebView = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.i(TAG, "onCreate")
        window.setFlags(
            WindowManager.LayoutParams.FLAG_FULLSCREEN,
            WindowManager.LayoutParams.FLAG_FULLSCREEN
        )
        setContentView(R.layout.activity_takeover)

        statusText = findViewById(R.id.statusText)
        progressBar = findViewById(R.id.progressBar)
        retryButton = findViewById(R.id.retryButton)

        retryButton.setOnClickListener {
            progressBar.visibility = View.VISIBLE
            retryButton.visibility = View.GONE
            ensurePermissionAndStart()
        }

        ensurePermissionAndStart()
    }

    private fun ensurePermissionAndStart() {
        if (ContextCompat.checkSelfPermission(this, PERM_RUN_COMMAND) == PackageManager.PERMISSION_GRANTED) {
            Log.i(TAG, "RUN_COMMAND permission granted, start takeover")
            startTakeover()
        } else {
            Log.i(TAG, "requesting RUN_COMMAND permission")
            statusText.text = "需要授予「运行命令」权限以经 Termux 启动酒馆…"
            ActivityCompat.requestPermissions(this, arrayOf(PERM_RUN_COMMAND), REQ_RUN_COMMAND)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_RUN_COMMAND) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                Log.i(TAG, "RUN_COMMAND permission granted (result)")
                startTakeover()
            } else {
                Log.w(TAG, "RUN_COMMAND permission denied")
                showStartFailed("未授予「运行命令」权限，无法经 Termux 接管。请允许该权限后重试。")
            }
        }
    }

    private fun startTakeover() {
        Log.i(TAG, "startTakeover begin")
        statusText.text = "正在检查 Termux 酒馆状态..."
        startGuard()
        thread(name = "takeover-start", isDaemon = true) {
            if (isFinishing || isDestroyed) return@thread
            // 已在跑 → 直接接管
            if (probe()) {
                Log.i(TAG, "probe: 8000 already up -> openWebView")
                runOnUiThread { openWebView() }
                return@thread
            }
            Log.i(TAG, "probe: 8000 not ready, starting termux ST")
            runOnUiThread { statusText.text = "正在经 Termux 接管启动 SillyTavern..." }
            val beaconPort = TakeoverBeacon.open()
            Log.i(TAG, "beacon opened on port $beaconPort")
            TermuxBridge.acquireWakeLock(this)
            var ok = TermuxBridge.startTermuxSt(this, beaconPort)
            if (!ok) {
                // 投递异常（多为冷 Termux 被 OPPO 拦截拉起）→ 先拉起 Termux 进程，稍候重投一次
                Log.w(TAG, "dispatch failed, warming termux and retrying")
                TermuxBridge.launchTermux(this)
                try { Thread.sleep(2000) } catch (_: InterruptedException) {}
                ok = TermuxBridge.startTermuxSt(this, beaconPort)
            }
            Log.i(TAG, "startTermuxSt returned ok=$ok")
            if (!ok) {
                TakeoverBeacon.close()
                stopGuard()
                runOnUiThread { showStartFailed() }
                return@thread
            }
            startPolling()
        }
    }

    private fun startGuard() {
        val gi = Intent(this, TakeoverGuardService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(gi) else startService(gi)
    }

    private fun stopGuard() {
        stopService(Intent(this, TakeoverGuardService::class.java))
    }

    private fun startPolling() {
        if (polling) return
        polling = true
        Log.i(TAG, "startPolling")
        thread(name = "takeover-poll", isDaemon = true) {
            for (i in 1..90) {
                if (isFinishing || isDestroyed) { polling = false; return@thread }
                if (probe()) {
                    Log.i(TAG, "poll success -> openWebView")
                    runOnUiThread { openWebView() }
                    polling = false
                    return@thread
                }
                if (i % 5 == 0) Log.i(TAG, "polling $i/90 ...")
                try { Thread.sleep(1000) } catch (_: InterruptedException) { polling = false; return@thread }
            }
            if (isFinishing || isDestroyed) { polling = false; return@thread }
            Log.i(TAG, "polling timeout")
            runOnUiThread {
                showStartFailed(
                    "Termux 酒馆未在 90 秒内就绪。请打开 Termux 查看 ~/st-takeover.log，" +
                    "确认酒馆已安装（目录名不限，自动识别；特殊目录可用 ST_DIR 环境变量指定）、端口为 8000。"
                )
            }
            polling = false
        }
    }

    private fun probe(): Boolean {
        return try {
            val conn = java.net.URL("http://127.0.0.1:8000").openConnection() as java.net.HttpURLConnection
            conn.connectTimeout = 2000
            conn.readTimeout = 2000
            val code = conn.responseCode
            conn.disconnect()
            code in 200..399
        } catch (_: Exception) {
            false
        }
    }

    private fun showStartFailed(
        msg: String = "无法启动。请确认已安装 Termux，并在 ~/.termux/termux.properties 设置 " +
            "allow-external-apps = true（后执行 termux-reload-settings），且已授予「运行命令」权限。"
    ) {
        progressBar.visibility = View.GONE
        retryButton.visibility = View.VISIBLE
        statusText.text = msg
    }

    private fun openWebView() {
        if (openedWebView) return
        openedWebView = true
        Log.i(TAG, "openWebView")
        val intent = Intent(this, WebViewActivity::class.java).apply {
            putExtra(WebViewActivity.EXTRA_URL, WebViewActivity.DEFAULT_URL)
            putExtra(WebViewActivity.EXTRA_STOP_MODE, WebViewActivity.STOP_MODE_TERMUX)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        startActivity(intent)
        finish()
    }

    override fun onDestroy() {
        // 若用户在就绪前退出（未打开 WebView），关闭 beacon、停止守卫并清理可能已启动的 Termux 酒馆。
        // 若已打开 WebView，由 WebViewActivity 退出时负责。
        if (!openedWebView) {
            TakeoverBeacon.close()
            stopGuard()
            TermuxBridge.stopTermuxSt(this)
        }
        super.onDestroy()
    }
}
