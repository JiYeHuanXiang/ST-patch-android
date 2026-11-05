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
import com.st.patch.common.PublicCharactersLinker
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

        /**
         * 影子模式：ACTION_START 附带该 extra 指定酒馆根目录（从 Termux 复制来的完整酒馆），
         * 跳过 assets 解压，直接以 app 自带 libnode.so 跑该目录的 server.js。
         * 缺省 = 启动模式（解压 app.zip 跑 app 自带内核）。
         */
        const val EXTRA_ST_DIR = "st_dir"

        var isRunning = false
            private set

        /** 供 SplashActivity 显示的最近错误信息 */
        var lastErrorPublic = ""
            private set

        /**
         * Node 输出中是否出现过就绪特征行。
         * 用于兜底：Node 在 HTTP 探测窗口外 ready 后瞬间退出时，避免误判为失败。
         */
        @Volatile
        var nodeReportedReady = false
            private set

        /** 就绪日志特征词（小写匹配）。SillyTavern 启动时打印监听端口/就绪信息。 */
        private val READY_KEYWORDS = arrayOf("listen", "started", "running", "ready", "launched")

        /** 最近 200 行 Node 输出日志，供 UI 查阅/复制 */
        val nodeLogLines = java.util.Collections.synchronizedList(ArrayList<String>(256))
        private const val LOG_MAX_LINES = 200
    }

    private var nodeProcess: Process? = null

    /** 影子模式的酒馆根目录（null = 启动模式，跑 app 自带内核） */
    private var shadowStDir: String? = null

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
                // 影子模式：附带酒馆根目录（跳过 assets 解压，直接跑该目录）；缺省走自带内核
                shadowStDir = intent.getStringExtra(EXTRA_ST_DIR)
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
                // 重置本次启动的状态
                lastErrorPublic = ""
                nodeReportedReady = false

                // 在后台线程读取版本号，避免主线程 PackageManager 查询造成卡顿
                val appVersion = try {
                    packageManager.getPackageInfo(packageName, 0).versionName ?: "1.0"
                } catch (e: Exception) {
                    "1.0"
                }

                // 0. 检测 CPU 架构：按 SUPPORTED_ABIS 优先级取首个受支持 ABI
                //    （arm64 真机与 x86_64 模拟器均可跑，运行组件在 assets 里按 ABI 分目录）
                Log.i(TAG, "Device ABIs: ${Build.SUPPORTED_ABIS.joinToString()}")
                val nativeAbi = Build.SUPPORTED_ABIS.firstOrNull { it == "arm64-v8a" || it == "x86_64" }
                if (nativeAbi == null) {
                    lastErrorPublic = "不支持的CPU架构: ${Build.SUPPORTED_ABIS.firstOrNull() ?: "unknown"}\n需要 arm64-v8a 或 x86_64 设备"
                    Log.e(TAG, lastErrorPublic)
                    updateNotification(lastErrorPublic)
                    sendBroadcast(Intent(BROADCAST_SERVER_FAILED))
                    return@thread
                }

                // 1. 运行目录：影子模式跑复制来的酒馆目录（跳过 assets 解压）；否则解压 app 自带内核
                val filesDir = filesDir.absolutePath
                val shadowDir: File? = shadowStDir?.let(::File)
                val isShadow = shadowDir != null

                if (isShadow) {
                    val dir = shadowDir!!
                    if (!File(dir, "server.js").isFile) {
                        lastErrorPublic = "影子酒馆目录无效（缺 server.js）：${dir.absolutePath}"
                        Log.e(TAG, lastErrorPublic)
                        updateNotification(lastErrorPublic)
                        sendBroadcast(Intent(BROADCAST_SERVER_FAILED))
                        return@thread
                    }
                } else {
                    val sillyDir = File(filesDir, "sillytavern")
                    val versionFile = File(sillyDir, ".extracted_version")
                    val extractingFlag = File(sillyDir, ".extracting")

                    // 版本不匹配，或上次解压被中断（.extracting 仍存在）→ 需要重新解压
                    val needExtract = !versionFile.exists()
                            || versionFile.readText().trim() != appVersion
                            || extractingFlag.exists()

                    if (needExtract) {
                        updateNotification("正在解压资源文件...")
                        Log.i(TAG, "Extracting assets to $filesDir")

                        val extractAssets = ExtractAssets(this)
                        try {
                            // app/ 走单流 zip 解压（原子落地，杜绝半解压后误启动 Node）
                            extractAssets.extractZipArchive(
                                "sillytavern/app.zip", sillyDir
                            )
                            // node-bin（.so 运行组件）不在此处：与 ABI 相关，下方统一按 ABI 解压
                        } catch (e: Exception) {
                            // 解压失败：写明错误并广播失败，绝不带半状态启动 Node
                            lastErrorPublic = "资源解压失败: ${e.message}"
                            Log.e(TAG, lastErrorPublic, e)
                            updateNotification(lastErrorPublic)
                            sendBroadcast(Intent(BROADCAST_SERVER_FAILED))
                            return@thread
                        }

                        // 全部成功后写版本号；.extracting 标记已被解压器清除
                        sillyDir.mkdirs()
                        versionFile.writeText(appVersion)
                    } else {
                        Log.i(TAG, "Assets already extracted, skipping")
                    }
                }

                // 1.5 node-bin（.so 运行组件）按 ABI 独立解压：
                //     assets/sillytavern/node-bin/<abi> → filesDir/sillytavern/node-bin/<abi>。
                //     独立标记（版本|ABI）判断是否重解：升级、换设备架构（真机↔模拟器）、
                //     或旧版平铺布局（.so 直接堆在 node-bin 根下）都会触发；成功后清理平铺残留。
                //     影子模式同样走这里（用户可能从未跑过「启动」模式）。
                val nodeBinRoot = File(filesDir, "sillytavern/node-bin")
                val soDir = File(nodeBinRoot, nativeAbi)
                val nodeBinMarker = File(filesDir, "sillytavern/.nodebin_version")
                val nodeBinExpected = "$appVersion|$nativeAbi"
                val nodeBinReady = soDir.isDirectory && !soDir.listFiles().isNullOrEmpty()
                        && nodeBinMarker.exists() && nodeBinMarker.readText().trim() == nodeBinExpected
                if (!nodeBinReady) {
                    updateNotification("正在准备运行组件...")
                    try {
                        ExtractAssets(this).extractResources("sillytavern/node-bin/$nativeAbi")
                        // 旧版布局残留：node-bin 根下的平铺 .so 已不再被引用，清理省空间
                        nodeBinRoot.listFiles()?.forEach { f ->
                            if (f.isFile && (f.name.endsWith(".so") || f.name.contains(".so."))) f.delete()
                        }
                        if (!soDir.isDirectory || soDir.listFiles().isNullOrEmpty()) {
                            throw IllegalStateException("解压后 node-bin/$nativeAbi 仍为空")
                        }
                        nodeBinMarker.writeText(nodeBinExpected)
                    } catch (e: Exception) {
                        lastErrorPublic = "运行组件解压失败: ${e.message}"
                        Log.e(TAG, lastErrorPublic, e)
                        updateNotification(lastErrorPublic)
                        sendBroadcast(Intent(BROADCAST_SERVER_FAILED))
                        return@thread
                    }
                }

                // node 已作为 libnode.so 打包进 jniLibs，系统自动安装到 nativeLibraryDir（可执行）
                val nativeLibDir = File(applicationInfo.nativeLibraryDir)
                val nodeBin = File(nativeLibDir, "libnode.so")
                if (!nodeBin.exists()) {
                    lastErrorPublic = "node 二进制未找到: ${nodeBin.absolutePath}"
                    Log.e(TAG, lastErrorPublic)
                    updateNotification(lastErrorPublic)
                    sendBroadcast(Intent(BROADCAST_SERVER_FAILED))
                    return@thread
                }
                nodeBin.setExecutable(true, false)

                // .so 依赖留在 filesDir/node-bin/<abi>（只读即可，LD_LIBRARY_PATH 指向这里）
                soDir.listFiles()?.forEach { lib ->
                    if (lib.name.endsWith(".so") || lib.name.contains(".so.")) {
                        lib.setReadable(true, false)
                    }
                }
                Log.i(TAG, "Node binary ready: ${nodeBin.absolutePath}")

                // 2. 修正已有的 config.yaml（首次启动时还不存在，由 Node 生成；
                //    此处处理非首次启动场景，防御性地保证 browserLaunch 关闭、端口 8000）
                val configFile = if (isShadow) File(shadowDir!!, "config.yaml")
                                 else File(filesDir, "sillytavern/app/config.yaml")
                sanitizeConfig(configFile)

                // 3. 启动 Node（影子：复制来的目录即酒馆根；启动：app/ 下为代码、data/ 为数据）
                updateNotification("正在启动服务器...")
                val nodePath = nodeBin.absolutePath
                val serverPath = if (isShadow) File(shadowDir!!, "server.js").absolutePath
                                 else File(filesDir, "sillytavern/app/server.js").absolutePath
                val dataRoot = if (isShadow) File(shadowDir!!, "data").absolutePath
                               else File(filesDir, "sillytavern/data").absolutePath
                val workDir = if (isShadow) shadowDir!! else File(filesDir, "sillytavern/app")
                File(dataRoot).mkdirs()

                // 角色卡公共目录映射（可选）：按用户开关把 characters 目录制为公有目录符号链接。
                // 补丁版(启动模式)与原版(影子模式)目录严格分离，互不干扰。
                val variant = if (isShadow) PublicCharactersLinker.VARIANT_SHADOW
                              else PublicCharactersLinker.VARIANT_PATCH
                PublicCharactersLinker.applyForCharactersDir(this, File(dataRoot, "default-user/characters"), variant)

                Log.i(TAG, "Starting: $nodePath $serverPath --dataRoot $dataRoot")

                val pb = ProcessBuilder(nodePath, serverPath, "--dataRoot", dataRoot)
                pb.directory(workDir)
                pb.environment()["HOME"] = if (isShadow) workDir.absolutePath else filesDir
                pb.environment()["NODE_ENV"] = "production"
                pb.environment()["LD_LIBRARY_PATH"] = soDir.absolutePath
                // Android 使用内置 WebView，无需 Node 端自动打开浏览器。
                // 双保险 ① 环境变量覆盖 getConfigValue（首次启动 config.yaml 不存在时仍生效）
                pb.environment()["SILLYTAVERN_BROWSERLAUNCH_ENABLED"] = "false"
                pb.redirectErrorStream(true)

                nodeProcess = pb.start()
                readProcessOutput(nodeProcess!!)

                // 4. 轮询等待
                updateNotification("等待服务器就绪...")
                val ready = pollServerReady()

                if (ready) {
                    isRunning = true
                    Log.i(TAG, "Server ready")
                    // 服务器就绪后，修正首次启动生成的 config.yaml（防御纵深）
                    sanitizeConfig(configFile)
                    updateNotification("SillyTavern 正在运行")
                    sendBroadcast(Intent(BROADCAST_SERVER_READY))
                } else {
                    lastErrorPublic = "服务器启动超时 (60秒)"
                    Log.e(TAG, lastErrorPublic)
                    updateNotification(lastErrorPublic)
                    sendBroadcast(Intent(BROADCAST_SERVER_FAILED))
                }

            } catch (e: Exception) {
                lastErrorPublic = "启动失败: ${e.message}"
                Log.e(TAG, "Failed to start node", e)
                updateNotification(lastErrorPublic)
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
                    val current = line // 取稳定值，便于 smart cast
                    Log.i(TAG, "[node] $current")
                    // 存入环形缓冲区，供 UI 查阅
                    synchronized(nodeLogLines) {
                        if (nodeLogLines.size >= LOG_MAX_LINES) {
                            nodeLogLines.removeAt(0)
                        }
                        nodeLogLines.add(current ?: "")
                    }

                    // 日志就绪兜底：Node 自己打印了监听/就绪信息，
                    // 即便 HTTP 探测窗口外它瞬间退出，也不误判为失败
                    if (!nodeReportedReady && current != null && isReadyLogLine(current)) {
                        nodeReportedReady = true
                        Log.i(TAG, "Server reported ready via log output")
                    }
                }
            } catch (e: Exception) {
                Log.d(TAG, "output reader exited: ${e.message}")
            }
        }
    }

    private fun pollServerReady(): Boolean {
        var wasReady = false
        for (attempt in 1..POLL_MAX_ATTEMPTS) {
            // 优先尝试 HTTP 探测：服务器可能在 ready 后因无害原因（如 xdg-open）
            // 瞬间退出，先查端口能避免误判为失败
            val code = httpProbe()
            if (code in 200..399) {
                wasReady = true
                return true
            }

            // 再检查进程是否已退出
            if (nodeProcess != null && !nodeProcess!!.isAlive) {
                val exitCode = nodeProcess!!.exitValue()
                Log.e(TAG, "Node process exited with code $exitCode")
                // wasReady=HTTP 曾探到就绪；nodeReportedReady=Node 日志自报就绪
                // 任一为真即视为启动成功，避免 ready 后瞬间退出被误判失败
                if (wasReady || nodeReportedReady) {
                    Log.i(TAG, "Server was ready before exit, treating as success")
                    return true
                }
                lastErrorPublic = "Node 进程异常退出 (code $exitCode)"
                return false
            }

            try { Thread.sleep(POLL_INTERVAL_MS) } catch (_: InterruptedException) { return false }

            if (attempt % 5 == 0) {
                Log.d(TAG, "Polling... $attempt/$POLL_MAX_ATTEMPTS")
            }
        }
        return false
    }

    /**
     * HTTP 探测服务器是否就绪。返回状态码或 -1（连接失败/超时）。
     * pollServerReady 与 SplashActivity 的兜底轮询复用本方法，避免逻辑分叉。
     */
    private fun httpProbe(): Int {
        return try {
            val conn = URL(SERVER_URL).openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.connectTimeout = 2000
            conn.readTimeout = 2000
            conn.instanceFollowRedirects = false
            val code = conn.responseCode
            conn.disconnect()
            code
        } catch (_: Exception) {
            -1
        }
    }

    /**
     * 判断一行 Node 输出是否表示服务器已就绪。
     * 需同时含端口号 8000 且含就绪特征词，避免误命中普通日志。
     * （特征词宽松覆盖 listen/started/running/ready/launched）
     */
    private fun isReadyLogLine(line: String): Boolean {
        val lower = line.lowercase()
        if (!lower.contains("8000")) return false
        return READY_KEYWORDS.any { lower.contains(it) }
    }

    /**
     * 防御性地修正 config.yaml 中 Android 环境不安全/不一致的配置项。
     *
     * 双保险 ②：环境变量已覆盖 getConfigValue，此处再修正文件本身，
     * 确保即使上游修改 env 机制，config 文件层面也是安全的。
     * 用正则做行级替换，对上游 config 结构变化有韧性。
     */
    private fun sanitizeConfig(configFile: File) {
        if (!configFile.exists()) return
        try {
            val content = configFile.readText()
            val patched = content
                // browserLaunch.enabled: true → false （xdg-open 不存在会导致进程崩溃）
                .replace(
                    Regex("(\\nbrowserLaunch:\\n(\\s+)enabled:\\s*)true"),
                    "$1false"
                )
                // 端口统一 8000（app WebView 硬编码 8000；影子 config 复制自 Termux，可能改过端口）
                .replace(
                    Regex("(\\nport:\\s*)[0-9]+")
                ) { m -> m.groupValues[1] + "8000" }
            if (patched != content) {
                configFile.writeText(patched)
                Log.i(TAG, "Config sanitized: browserLaunch disabled, port=8000")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not sanitize config: ${e.message}")
        }
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
