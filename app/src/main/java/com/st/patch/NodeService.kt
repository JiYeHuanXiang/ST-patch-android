package com.st.patch

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import com.st.patch.common.ExtractAssets
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

/**
 * 前台 Service：管理 Node.js 进程的生命周期。
 */
class NodeService : Service() {

    companion object {
        private const val TAG = "NodeService"
        private const val CHANNEL_ID = "sillytavern_node_channel"
        private const val NOTIFICATION_ID = 1001
        private const val SERVER_URL = "http://127.0.0.1:8000"
        private const val POLL_INTERVAL_MS = 1000L
        private const val POLL_MAX_ATTEMPTS = 60

        const val ACTION_START = "com.st.patch.ACTION_START"
        const val ACTION_STOP = "com.st.patch.ACTION_STOP"
        const val BROADCAST_SERVER_READY = "com.st.patch.SERVER_READY"
        const val BROADCAST_SERVER_FAILED = "com.st.patch.SERVER_FAILED"
        const val EXTRA_VERSION = "app_version"

        var isRunning = false
            private set
    }

    private var nodeProcess: Process? = null
    private var appVersion: String = "1.0"
    private var lastError: String = ""

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopNode()
                return START_NOT_STICKY
            }
            ACTION_START -> {
                appVersion = intent.getStringExtra(EXTRA_VERSION) ?: "1.0"
                val notification = buildNotification("正在启动 SillyTavern...")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    startForeground(NOTIFICATION_ID, notification,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
                } else {
                    startForeground(NOTIFICATION_ID, notification)
                }
                startNodeAsync()
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        stopNode()
        super.onDestroy()
    }

    // ========== Node 进程管理 ==========

    private fun startNodeAsync() {
        thread(name = "node-starter") {
            try {
                // 0. 检测 CPU 架构
                val arch = Build.SUPPORTED_ABIS.firstOrNull() ?: "unknown"
                Log.i(TAG, "Device ABI: $arch")
                if (!arch.contains("arm64", true) && !arch.contains("aarch64", true)) {
                    lastError = "不支持的CPU架构: $arch\n需要 arm64-v8a 设备"
                    Log.e(TAG, lastError)
                    updateNotification(lastError)
                    sendBroadcast(Intent(BROADCAST_SERVER_FAILED))
                    return@thread
                }

                // 1. 解压 assets
                val filesDir = filesDir.absolutePath
                val versionFile = File(filesDir, "sillytavern/.extracted_version")

                if (!versionFile.exists() || versionFile.readText() != appVersion) {
                    updateNotification("正在解压资源文件...")
                    Log.i(TAG, "Extracting assets to $filesDir")
                    val extractAssets = ExtractAssets(this)
                    extractAssets.extractResources("sillytavern")

                    versionFile.parentFile?.mkdirs()
                    versionFile.writeText(appVersion)
                } else {
                    Log.i(TAG, "Assets already extracted, skipping")
                }

                // node 已作为 libnode.so 打包进 jniLibs，系统自动安装到 nativeLibraryDir（可执行）
                val nativeLibDir = File(applicationInfo.nativeLibraryDir)
                val nodeBin = File(nativeLibDir, "libnode.so")
                if (!nodeBin.exists()) {
                    lastError = "node 二进制未找到: ${nodeBin.absolutePath}"
                    Log.e(TAG, lastError)
                    updateNotification(lastError)
                    sendBroadcast(Intent(BROADCAST_SERVER_FAILED))
                    return@thread
                }
                nodeBin.setExecutable(true, false)

                // .so 留在 filesDir（只读即可，LD_LIBRARY_PATH 会指向这里）
                val soDir = File(filesDir, "sillytavern/node-bin")
                soDir.listFiles()?.forEach { lib ->
                    if (lib.name.endsWith(".so") || lib.name.contains(".so.")) {
                        lib.setReadable(true, false)
                    }
                }
                Log.i(TAG, "Node binary ready: ${nodeBin.absolutePath}")

                // 2. 启动 Node
                updateNotification("正在启动服务器...")
                val nodePath = nodeBin.absolutePath
                val serverPath = File(filesDir, "sillytavern/app/server.js").absolutePath
                val dataRoot = File(filesDir, "sillytavern/data").absolutePath
                File(dataRoot).mkdirs()

                Log.i(TAG, "Starting: $nodePath $serverPath --dataRoot $dataRoot")

                val pb = ProcessBuilder(nodePath, serverPath, "--dataRoot", dataRoot)
                pb.directory(File(filesDir, "sillytavern/app"))
                pb.environment()["HOME"] = filesDir
                pb.environment()["NODE_ENV"] = "production"
                pb.environment()["LD_LIBRARY_PATH"] = soDir.absolutePath
                pb.redirectErrorStream(true)

                nodeProcess = pb.start()
                readProcessOutput(nodeProcess!!)

                // 3. 轮询等待
                updateNotification("等待服务器就绪...")
                val ready = pollServerReady()

                if (ready) {
                    isRunning = true
                    Log.i(TAG, "Server ready")
                    updateNotification("SillyTavern 正在运行")
                    sendBroadcast(Intent(BROADCAST_SERVER_READY))
                } else {
                    lastError = "服务器启动超时 (60秒)"
                    Log.e(TAG, lastError)
                    updateNotification(lastError)
                    sendBroadcast(Intent(BROADCAST_SERVER_FAILED))
                }

            } catch (e: Exception) {
                lastError = "启动失败: ${e.message}"
                Log.e(TAG, "Failed to start node", e)
                updateNotification(lastError)
                sendBroadcast(Intent(BROADCAST_SERVER_FAILED))
            }
        }
    }

    private fun readProcessOutput(process: Process) {
        thread(name = "node-output", isDaemon = true) {
            try {
                val reader = BufferedReader(InputStreamReader(process.inputStream))
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    Log.i(TAG, "[node] $line")
                }
            } catch (e: Exception) {
                Log.d(TAG, "output reader exited: ${e.message}")
            }
        }
    }

    private fun pollServerReady(): Boolean {
        var wasReady = false
        for (attempt in 1..POLL_MAX_ATTEMPTS) {
            // 检查进程是否还活着
            if (nodeProcess != null && !nodeProcess!!.isAlive) {
                val exitCode = nodeProcess!!.exitValue()
                Log.e(TAG, "Node process exited with code $exitCode")
                // 如果曾 HTTP 成功过，进程退出也视为成功（xdg-open 等无害崩溃）
                if (wasReady) {
                    Log.i(TAG, "Server was ready before exit, treating as success")
                    return true
                }
                lastError = "Node 进程异常退出 (code $exitCode)"
                return false
            }

            try {
                val conn = URL(SERVER_URL).openConnection() as HttpURLConnection
                conn.requestMethod = "GET"
                conn.connectTimeout = 2000
                conn.readTimeout = 2000
                conn.instanceFollowRedirects = false
                val code = conn.responseCode
                conn.disconnect()
                if (code in 200..399) {
                    wasReady = true
                    return true
                }
            } catch (_: Exception) {}

            try { Thread.sleep(POLL_INTERVAL_MS) } catch (_: InterruptedException) { return false }

            if (attempt % 5 == 0) {
                Log.d(TAG, "Polling... $attempt/$POLL_MAX_ATTEMPTS")
            }
        }
        return false
    }

    private fun stopNode() {
        isRunning = false
        try {
            nodeProcess?.destroy()
            nodeProcess = null
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping node", e)
        }
    }

    // ========== 通知 ==========

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "SillyTavern 服务器",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "SillyTavern Node.js 服务器运行状态"
                setShowBadge(false)
            }
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(text: String): Notification {
        val contentIntent = Intent(this, WebViewActivity::class.java).apply {
            putExtra(WebViewActivity.EXTRA_URL, SERVER_URL)
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val contentPI = PendingIntent.getActivity(
            this, 0, contentIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stopIntent = Intent(this, NodeService::class.java).apply { action = ACTION_STOP }
        val stopPI = PendingIntent.getService(
            this, 1, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION") Notification.Builder(this)
        }
        return builder
            .setContentTitle("SillyTavern Patch")
            .setContentText(text)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(contentPI)
            .setOngoing(true)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "停止服务器", stopPI)
            .build()
    }

    private fun updateNotification(text: String) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, buildNotification(text))
    }
}
