package com.st.patch

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.st.patch.common.PatchExporter
import com.st.patch.common.TermuxBridge
import com.st.patch.common.TermuxScripts
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.util.zip.ZipInputStream
import kotlin.concurrent.thread

/**
 * 影子模式：把 Termux 的「原版」酒馆（无 .st-patch-build 标记的目录；补丁版候选被脚本跳过）
 * 整目录复制到 app 私有目录 filesDir/sillytavern-shadow/（复制-once），之后由 app 自带内核
 * （libnode.so）运行——与 Termux 解耦，也与「启动」模式的 app 自带补丁版区分。
 * 原版酒馆在 Termux 可能从未运行过（无 node_modules），导入后自动从 app 内置包补齐依赖。
 *
 * 流程（复制阶段同数据迁移/修补，运行阶段同启动模式）：
 * 1. 「从 Termux 复制酒馆」→ 生成 shadow-export.sh 经 RUN_COMMAND 投递（先投递再拉起 Termux，
 *    2 秒仍无结果补投一次），Termux 侧特征扫描识别「原版」目录后 tar 整目录到
 *    Download/st-patch/shadow.tar，结果经 trap EXIT 覆写 app 预创建的 shadow-result.txt（app 轮询读回）。
 * 2. 「导入影子副本」→ SAF 选择 shadow.tar（tar/tar.gz/zip 魔数自动识别）解压到影子目录，
 *    校验 server.js/package.json；缺 node_modules 时从 app 内置 app.zip 注入，全部成功后写 .shadow_ok。
 * 3. 「启动影子酒馆」→ 以 EXTRA_ST_DIR 启动 NodeService（跳过 assets 解压，直接跑影子目录），
 *    就绪后进 WebView；退出 WebView 停 NodeService 即停影子 node。
 *
 * 按钮互不隐藏：「启动」的可见性只取决于影子副本是否就绪（.shadow_ok + server.js + data/ 齐全），
 * 已就绪时直接可点，绝不强迫重新复制；「复制」「导入」并列可用，已打包过 shadow.tar 的可直接导入。
 */
class ShadowActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "ShadowActivity"
        private const val PERM_RUN_COMMAND = "com.termux.permission.RUN_COMMAND"
        private const val RESULT_NAME = "shadow-result.txt"
        private const val PROGRESS_NAME = "shadow-progress.txt"
        private const val SHADOW_DIR_NAME = "sillytavern-shadow"
        private const val TMP_DIR_NAME = "sillytavern-shadow.tmp"
        private const val SHADOW_MARKER = ".shadow_ok"
        private const val POLL_TIMEOUT_SEC = 180
        private const val REDISPATCH_AT_SEC = 2
        private const val LOG_REFRESH_MS = 300L
        /** 导入进度回调的最小字节间隔（32MB），避免高频 runOnUiThread 塞爆主线程 */
        private const val PROGRESS_REPORT_BYTES = 32L * 1024 * 1024
    }

    /** shadow-export.sh 回写的执行结果（status=ok|error|pending） */
    private data class ShadowResult(val status: String, val target: String, val detail: String)

    private lateinit var statusText: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var startButton: Button
    private lateinit var copyButton: Button
    private lateinit var importButton: Button
    private lateinit var retryButton: Button
    private lateinit var logBox: View
    private lateinit var logTextView: TextView
    private lateinit var logScrollView: ScrollView
    private lateinit var copyLogButton: Button

    private val mainHandler = Handler(Looper.getMainLooper())
    private var logRefreshActive = false
    private var displayedLogCount = 0
    @Volatile private var polling = false
    @Volatile private var launched = false
    private var opened = false

    private var shPath: String? = null
    private var resultUri: Uri? = null
    private var progressUri: Uri? = null
    private val shadowDir: File get() = File(filesDir, SHADOW_DIR_NAME)

    private val pickArchive = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri == null) return@registerForActivityResult
        thread(name = "shadow-import", isDaemon = true) {
            if (isFinishing || isDestroyed) return@thread
            runOnUiThread {
                progressBar.visibility = View.VISIBLE
                progressBar.isIndeterminate = true
                importButton.isEnabled = false
                statusText.text = "正在导入影子副本…"
            }
            val err = importShadowArchive(uri) { done, total ->
                runOnUiThread {
                    if (total > 0) {
                        progressBar.isIndeterminate = false
                        progressBar.progress = ((done * 100) / total).toInt().coerceIn(0, 100)
                        statusText.text = "正在导入影子副本… ${done / 1048576}/${total / 1048576} MB"
                    }
                }
            }
            Log.i(TAG, "importShadowArchive err=$err")
            if (err != null) {
                runOnUiThread {
                    progressBar.visibility = View.GONE
                    importButton.isEnabled = true
                    showCopyState(err)   // 失败可当场重试，不进终态
                }
                return@thread
            }
            // 原版酒馆在 Termux 可能从未运行过（无 node_modules）——从 app 内置包补齐依赖
            runOnUiThread { statusText.text = "正在补齐运行依赖（node_modules）…" }
            val nmErr = injectNodeModulesIfNeeded()
            Log.i(TAG, "injectNodeModules err=$nmErr")
            runOnUiThread {
                progressBar.visibility = View.GONE
                importButton.isEnabled = true
                if (nmErr == null) {
                    writeShadowMarker()
                    showReadyState("影子副本导入完成。")
                } else {
                    showCopyState(nmErr)
                }
            }
        }
    }

    private val serverReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                NodeService.BROADCAST_SERVER_READY -> {
                    runOnUiThread {
                        statusText.text = "影子酒馆已就绪，正在打开..."
                        stopLogRefresh()
                        updateLogText()
                        openWebView()
                    }
                }
                NodeService.BROADCAST_SERVER_FAILED -> {
                    runOnUiThread {
                        statusText.text = "影子酒馆启动失败\n${NodeService.lastErrorPublic}"
                        progressBar.visibility = View.GONE
                        stopLogRefresh()
                        updateLogText()
                        showRetryStart()
                    }
                }
            }
        }
    }

    private val logRefresher = object : Runnable {
        override fun run() {
            if (logRefreshActive && !isFinishing && !isDestroyed) {
                updateLogText()
                mainHandler.postDelayed(this, LOG_REFRESH_MS)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.i(TAG, "onCreate")
        window.setFlags(
            WindowManager.LayoutParams.FLAG_FULLSCREEN,
            WindowManager.LayoutParams.FLAG_FULLSCREEN
        )
        setContentView(R.layout.activity_shadow)

        statusText = findViewById(R.id.statusText)
        progressBar = findViewById(R.id.shadowProgressBar)
        startButton = findViewById(R.id.startButton)
        copyButton = findViewById(R.id.copyButton)
        importButton = findViewById(R.id.importButton)
        retryButton = findViewById(R.id.retryButton)
        logBox = findViewById(R.id.logBox)
        logTextView = findViewById(R.id.logTextView)
        logScrollView = findViewById(R.id.logScrollView)
        copyLogButton = findViewById(R.id.copyLogButton)

        copyLogButton.setOnClickListener {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("SillyTavern Log", getFormattedLog()))
            Toast.makeText(this, "日志已复制到剪贴板", Toast.LENGTH_SHORT).show()
        }

        // 监听器统一在 onCreate 绑定（与可见性管理解耦）：
        // showExportResult 等状态切换只改 visibility，避免「可见但无监听」的按钮（本轮真机踩坑）
        importButton.setOnClickListener { onImportClicked() }

        val filter = IntentFilter().apply {
            addAction(NodeService.BROADCAST_SERVER_READY)
            addAction(NodeService.BROADCAST_SERVER_FAILED)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(serverReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(serverReceiver, filter)
        }

        if (!TermuxBridge.isTermuxInstalled(this) && !isShadowReady()) {
            showCopyState("未安装 Termux。\n请先安装 Termux 并装好酒馆，再使用影子模式复制。")
            copyButton.isEnabled = false
            return
        }
        refreshState()
    }

    /** 影子副本是否就绪：marker + server.js 非空 + data/ 目录齐全 */
    private fun isShadowReady(): Boolean {
        val dir = shadowDir
        if (!File(dir, SHADOW_MARKER).isFile) return false
        val serverJs = File(dir, "server.js")
        return serverJs.isFile && serverJs.length() > 0L && File(dir, "data").isDirectory
    }

    /** 按副本状态刷新 UI（核心原则：启动按钮只看副本是否就绪，与复制流程无关） */
    private fun refreshState() {
        if (isShadowReady()) showReadyState(null) else showCopyState(null)
    }

    /** 就绪态：启动按钮直接可见可用；「重新复制」降为更新副本的次要入口 */
    private fun showReadyState(msg: String?) {
        progressBar.visibility = View.GONE
        retryButton.visibility = View.GONE
        statusText.text = (msg?.plus("\n") ?: "") +
            "影子副本已就绪（复制一次即可，无需重复复制）。\n" +
            "点「启动影子酒馆」用 app 自带内核运行，与 Termux 完全解耦。"
        startButton.visibility = View.VISIBLE
        startButton.setOnClickListener { onStartClicked() }
        copyButton.text = "重新复制（从 Termux 更新影子副本）"
        copyButton.visibility = View.VISIBLE
        copyButton.setOnClickListener { onCopyClicked() }
        importButton.visibility = View.GONE
    }

    /** 待复制态：复制/导入并列可用（已打包过 shadow.tar 可直接导入，不必重跑复制） */
    private fun showCopyState(err: String?) {
        startButton.visibility = View.GONE
        copyButton.text = "从 Termux 复制酒馆"
        copyButton.visibility = View.VISIBLE
        copyButton.setOnClickListener { onCopyClicked() }
        importButton.text = "导入影子副本"
        importButton.visibility = View.VISIBLE
        retryButton.visibility = View.GONE
        statusText.text = err
            ?: ("影子模式：把 Termux 的原版酒馆整目录复制进 app，之后不依赖 Termux 运行、与「启动」模式的补丁版并存。\n" +
                "首次使用：点「从 Termux 复制酒馆」在 Termux 打包（只读，不动原数据）→ 回来点「导入影子副本」。\n" +
                "已打包过？直接点「导入影子副本」选 Download/st-patch/shadow.tar。")
    }

    // ========== 复制（Termux 侧打包） ==========

    private fun onCopyClicked() {
        ensureNotificationPermission()
        progressBar.visibility = View.VISIBLE
        progressBar.isIndeterminate = true
        statusText.text = "正在生成影子导出脚本…"
        importButton.isEnabled = false
        thread(name = "shadow-prep", isDaemon = true) {
            if (isFinishing || isDestroyed) return@thread
            val sh = PatchExporter.writeTextToDownloads(this, "shadow-export.sh", TermuxScripts.shadowScript())
            Log.i(TAG, "wrote shadow-export.sh=$sh")
            if (sh == null) {
                runOnUiThread {
                    progressBar.visibility = View.GONE
                    importButton.isEnabled = true
                    statusText.text = "无法写影子导出脚本到 Download。"
                }
                return@thread
            }
            // 结果/进度文件必须由 app 预创建（归 app 所有才能免权限读回）；失败不阻断流程，仅无反馈
            val resultFile = PatchExporter.writeTextFile(this, RESULT_NAME, "status=pending\n")
            val progressFile = PatchExporter.writeTextFile(this, PROGRESS_NAME, "percent=0\n")
            Log.i(TAG, "result: ${resultFile?.path} progress: ${progressFile?.path}")
            runOnUiThread {
                shPath = sh
                resultUri = resultFile?.uri
                progressUri = progressFile?.uri
                val hasPerm =
                    ContextCompat.checkSelfPermission(this, PERM_RUN_COMMAND) == PackageManager.PERMISSION_GRANTED
                if (hasPerm) {
                    // 先投递（此刻 app 在前台，跨应用服务启动不被后台限制拦截），再拉起 Termux 预热/展示。
                    // 前台会话（background=false）：Termux 界面实时显示脚本进度，避免"界面待机像卡死"
                    TermuxBridge.sendRunCommand(
                        this,
                        path = TermuxBridge.TERMUX_SH,
                        arguments = arrayOf(sh),
                        workdir = TermuxBridge.TERMUX_HOME,
                        background = false
                    )
                    TermuxBridge.launchTermux(this)
                    statusText.text =
                        "已拉起 Termux 打包原版酒馆，Termux 界面可见实时进度。\n" +
                        "打包完成后点「导入影子副本」（也可点通知直达）。\n未自动执行则在 Termux 运行：sh $sh"
                    if (resultUri == null) {
                        progressBar.visibility = View.GONE
                        importButton.isEnabled = true
                    } else {
                        pollResult(redispatchPath = sh)
                    }
                } else {
                    TermuxBridge.launchTermux(this)
                    progressBar.visibility = View.GONE
                    statusText.text = "无 RUN_COMMAND 权限，已拉起 Termux。\n请手动运行：sh $sh\n" +
                        (if (resultUri == null) "" else "运行完成后回本页导入。")
                    importButton.isEnabled = true
                    if (resultUri != null) pollResult(redispatchPath = null)
                }
            }
        }
    }

    /**
     * 轮询结果文件直到非 pending 或超时；期间读取进度文件显示真实百分比。
     * 仍在等待且到点（2 秒）时补投一次（兜底冷 Termux 首投被 OPPO 拦截；脚本幂等）。
     */
    private fun pollResult(redispatchPath: String?) {
        if (polling) return
        polling = true
        thread(name = "shadow-export-poll", isDaemon = true) {
            var redispatched = false
            for (sec in 1..POLL_TIMEOUT_SEC) {
                if (isFinishing || isDestroyed) { polling = false; return@thread }
                val r = readResult()
                if (r != null && r.status != "pending") {
                    Log.i(TAG, "shadow export result: $r")
                    runOnUiThread { showExportResult(r) }
                    polling = false
                    return@thread
                }
                // 打包进度：Termux 侧循环覆写 percent=N，驱动确定性进度条
                val pct = readProgress()
                if (pct != null) {
                    runOnUiThread {
                        if (polling) {
                            progressBar.isIndeterminate = false
                            progressBar.progress = pct
                            statusText.text = "正在 Termux 内打包原版酒馆… $pct%（已等 ${sec}s）"
                        }
                    }
                } else if (sec % 15 == 0) {
                    val s = sec
                    runOnUiThread { if (polling) statusText.text = "仍在 Termux 内打包整目录…（已等 ${s}s）" }
                }
                if (!redispatched && sec >= REDISPATCH_AT_SEC && redispatchPath != null) {
                    redispatched = true
                    TermuxBridge.sendRunCommand(
                        this,
                        path = TermuxBridge.TERMUX_SH,
                        arguments = arrayOf(redispatchPath),
                        workdir = TermuxBridge.TERMUX_HOME,
                        background = false
                    )
                }
                try { Thread.sleep(1000) } catch (_: InterruptedException) { polling = false; return@thread }
            }
            if (isFinishing || isDestroyed) { polling = false; return@thread }
            runOnUiThread {
                progressBar.visibility = View.GONE
                importButton.isEnabled = true
                statusText.text = "等待打包结果超时。若 Termux 未自动执行，请手动运行：sh ${shPath ?: ""}\n" +
                    "执行情况见 Download/st-patch/shadow-debug.log。"
                retryButton.text = "重试复制"
                retryButton.visibility = View.VISIBLE
                retryButton.setOnClickListener { onRetryCopyClicked() }
            }
            polling = false
        }
    }

    /** 读进度文件里的 percent=0..100；读不到/无进度通路返回 null */
    private fun readProgress(): Int? {
        val uri = progressUri ?: return null
        return try {
            contentResolver.openInputStream(uri)?.bufferedReader()?.use { reader ->
                reader.lineSequence().forEach { line ->
                    if (line.startsWith("percent=")) {
                        return line.substring(8).trim().toIntOrNull()?.coerceIn(0, 100)
                    }
                }
                null
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun readResult(): ShadowResult? {
        val uri = resultUri ?: return null
        return try {
            contentResolver.openInputStream(uri)?.bufferedReader()?.use { reader ->
                val map = mutableMapOf<String, String>()
                reader.lineSequence().forEach { line ->
                    val i = line.indexOf('=')
                    if (i > 0) map[line.substring(0, i).trim()] = line.substring(i + 1)
                }
                val st = map["status"] ?: return null
                ShadowResult(st, map["target"] ?: "", map["detail"] ?: "")
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun showExportResult(r: ShadowResult) {
        progressBar.visibility = View.GONE
        retryButton.visibility = View.GONE
        importButton.isEnabled = true
        if (r.status == "ok") {
            statusText.text = "Termux 打包完成${if (r.target.isNotEmpty()) "：${r.target}" else ""}。\n" +
                "点「导入影子副本」选择 Download/st-patch/shadow.tar。"
            // 无论当前何种状态都亮出导入按钮（就绪态重新复制后也要能导入，启动按钮不动）
            importButton.visibility = View.VISIBLE
            // Termux 前台会话挡着 app：高优先级通知引导用户返回（点通知直达本页）
            notifyExportDone(true, "")
        } else {
            statusText.text = "打包未完成：${r.detail}\n详情见 Download/st-patch/shadow-debug.log。"
            notifyExportDone(false, r.detail)
            retryButton.text = "重试复制"
            retryButton.visibility = View.VISIBLE
            retryButton.setOnClickListener { onRetryCopyClicked() }
        }
    }

    /** 打包结果通知（heads-up）：Termux 在前台时用户看不到 app，点通知回到本页继续导入 */
    private fun notifyExportDone(ok: Boolean, detail: String) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        try {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val channel = NotificationChannel(
                "shadow_export", "影子模式打包", NotificationManager.IMPORTANCE_HIGH
            ).apply { description = "Termux 打包完成/失败提醒" }
            nm.createNotificationChannel(channel)
            val pi = PendingIntent.getActivity(
                this, 0, Intent(this, ShadowActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val n = Notification.Builder(this, "shadow_export")
                .setContentTitle(if (ok) "Termux 打包完成" else "Termux 打包失败")
                .setContentText(if (ok) "点此返回 app，导入影子副本" else detail)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentIntent(pi)
                .setAutoCancel(true)
                .build()
            nm.notify(3001, n)
        } catch (e: Exception) {
            Log.w(TAG, "notify failed: ${e.message}")
        }
    }

    /** 通知渠道需要运行时权限（Android 13+）；结果通知是"Termux 挡屏"时唯一的返回引导 */
    private fun ensureNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this, arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 101
            )
        }
    }

    /** 重试复制：把结果/进度文件重置（避免读到旧值），再走投递流程 */
    private fun onRetryCopyClicked() {
        retryButton.visibility = View.GONE
        val rf = PatchExporter.writeTextFile(this, RESULT_NAME, "status=pending\n")
        resultUri = rf?.uri
        val pf = PatchExporter.writeTextFile(this, PROGRESS_NAME, "percent=0\n")
        progressUri = pf?.uri
        onCopyClicked()
    }

    // ========== 导入（shadow.tar → 影子目录） ==========

    private fun onImportClicked() {
        // Termux 写入的归档可能未被 MediaStore 索引（选择器看不到），导入前触发扫描
        android.media.MediaScannerConnection.scanFile(
            this,
            arrayOf("${PatchExporter.PUBLIC_DIR}/shadow.tar", "${PatchExporter.PUBLIC_DIR}/shadow.tar.gz"),
            null, null
        )
        pickArchive.launch(arrayOf("*/*"))
    }

    /** 导入结果计数：accepted=成功落地的文件数；skipped=跳过的非法条目数 */
    private class ImportStats {
        var accepted = 0
        var skipped = 0
    }

    /**
     * SAF 选中的归档解压为影子副本。按魔数自动识别 zip（PK）/ tar.gz（1F 8B）/ tar（ustar）。
     * 接受整目录条目（去 ./ 前缀，防路径穿越）。已有就绪副本时先解压到临时目录、
     * 校验通过后原子替换（失败不毁现有副本）；校验 server.js/package.json 后写 .shadow_ok 标记。
     * [onProgress] 节流回调（已解压字节, 归档总字节；总字节未知时为 -1），驱动进度条。
     * 返回 null=成功，非 null=给用户看的错误信息。
     */
    private fun importShadowArchive(uri: Uri, onProgress: (Long, Long) -> Unit): String? {
        val stats = ImportStats()
        // 归档总大小（SAF 元数据；查不到则进度条退化为不确定模式）
        val totalBytes = try {
            contentResolver.query(
                uri, arrayOf(android.provider.OpenableColumns.SIZE), null, null, null
            )?.use { c -> if (c.moveToFirst()) c.getLong(0) else -1L } ?: -1L
        } catch (_: Exception) {
            -1L
        }
        var done = 0L
        var lastReport = -1L
        try {
            // 已有副本 → 解压到临时目录保护原副本；无副本 → 直接解压目标（省一倍空间）
            val existing = isShadowReady()
            val dir = if (existing) File(filesDir, TMP_DIR_NAME) else shadowDir
            if (dir.exists()) dir.deleteRecursively()
            dir.mkdirs()
            val base = dir.canonicalPath + File.separator

            val head = ByteArray(262)
            contentResolver.openInputStream(uri)?.use { it.read(head) } ?: return "无法读取所选文件。"
            val isZip = head[0] == 'P'.toByte() && head[1] == 'K'.toByte()
            val isGzip = head[0] == 0x1f.toByte() && head[1] == 0x8b.toByte()
            Log.i(TAG, "importShadowArchive: isZip=$isZip isGzip=$isGzip existing=$existing total=$totalBytes")

            if (isZip) {
                ZipInputStream(contentResolver.openInputStream(uri)).use { zis ->
                    var entry = zis.nextEntry
                    while (entry != null) {
                        extractEntry(dir, base, entry.name, entry.isDirectory, stats) { o -> zis.copyTo(o) }
                        if (entry.size > 0) done += entry.size
                        if (done - lastReport > PROGRESS_REPORT_BYTES || done >= totalBytes) {
                            lastReport = done; onProgress(done, totalBytes)
                        }
                        zis.closeEntry()
                        entry = zis.nextEntry
                    }
                }
            } else {
                val rawIn = contentResolver.openInputStream(uri) ?: return "无法读取所选文件。"
                val tarIn = if (isGzip) TarArchiveInputStream(GzipCompressorInputStream(BufferedInputStream(rawIn)))
                            else TarArchiveInputStream(BufferedInputStream(rawIn))
                tarIn.use {
                    var entry = it.nextEntry
                    while (entry != null) {
                        extractEntry(dir, base, entry.name, entry.isDirectory, stats) { o -> tarIn.copyTo(o) }
                        if (entry.size > 0) done += entry.size
                        if (done - lastReport > PROGRESS_REPORT_BYTES || done >= totalBytes) {
                            lastReport = done; onProgress(done, totalBytes)
                        }
                        entry = it.nextEntry
                    }
                }
            }
            onProgress(done, totalBytes)
            dir.walkTopDown().forEach {
                it.setReadable(true, false)
                it.setWritable(true, true)
            }

            if (stats.accepted == 0) {
                dir.deleteRecursively()
                return "所选文件不是有效的酒馆归档（支持 tar/tar.gz/zip），未生成影子副本。\n" +
                    "若选择器里看不到 shadow.tar：左上角菜单 → 内部存储 → Download → st-patch 选择。"
            }
            if (!File(dir, "server.js").isFile || !File(dir, "package.json").isFile) {
                dir.deleteRecursively()
                return "导入的归档不含酒馆结构（缺 server.js / package.json），未生成影子副本。"
            }
            if (existing) {
                // 校验通过才替换旧副本（同一 filesDir 内 rename 原子落位）
                shadowDir.deleteRecursively()
                if (!dir.renameTo(shadowDir)) {
                    dir.deleteRecursively()
                    return "导入失败（无法落位影子目录，请重试）。"
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "importShadowArchive failed: ${e.message}", e)
            File(filesDir, TMP_DIR_NAME).deleteRecursively()
            return "导入失败（解压出错或存储空间不足）。"
        }
        Log.i(TAG, "importShadowArchive: accepted=${stats.accepted} skipped=${stats.skipped}")
        return null
    }

    /** 依赖补齐成功后写复制-once 标记（.shadow_ok 是启动按钮亮起的依据） */
    private fun writeShadowMarker() {
        try {
            File(shadowDir, SHADOW_MARKER).writeText("ok")
        } catch (e: Exception) {
            Log.w(TAG, "write marker failed: ${e.message}")
        }
    }

    /**
     * 影子目录缺 node_modules 时（原版酒馆在 Termux 从未运行、无依赖），从 app 内置
     * app.zip 提取 node_modules/ 子树注入——与「启动」模式同源依赖，可运行性一致；
     * 只写 node_modules/ 下的条目，不覆盖影子目录任何原版代码文件。
     * 返回 null=成功或无需注入。
     */
    private fun injectNodeModulesIfNeeded(): String? {
        val nmDir = File(shadowDir, "node_modules")
        if (nmDir.isDirectory && !nmDir.listFiles().isNullOrEmpty()) {
            Log.i(TAG, "shadow already has node_modules, skip injection")
            return null
        }
        try {
            var count = 0
            val base = shadowDir.canonicalPath + File.separator
            assets.open("sillytavern/app.zip").use { raw ->
                ZipInputStream(BufferedInputStream(raw)).use { zis ->
                    var entry = zis.nextEntry
                    while (entry != null) {
                        var name = entry.name.replace('\\', '/')
                        while (name.startsWith("./")) name = name.substring(2)
                        if (name.startsWith("node_modules/") && !name.endsWith("/") &&
                            !name.split('/').any { it == ".." }
                        ) {
                            val outFile = File(shadowDir, name)
                            if (outFile.canonicalPath.startsWith(base)) {
                                outFile.parentFile?.mkdirs()
                                FileOutputStream(outFile).use { zis.copyTo(it) }
                                outFile.setReadable(true, false)
                                outFile.setWritable(true, true)
                                count++
                                if (count % 2000 == 0) {
                                    val c = count
                                    runOnUiThread {
                                        if (!isFinishing && !isDestroyed) {
                                            statusText.text = "正在补齐运行依赖…（已写入 $c 个文件）"
                                        }
                                    }
                                }
                            }
                        }
                        zis.closeEntry()
                        entry = zis.nextEntry
                    }
                }
            }
            Log.i(TAG, "injected $count node_modules entries")
            return if (count > 0) null else "补齐依赖失败（app.zip 内无 node_modules 条目）。"
        } catch (e: Exception) {
            Log.e(TAG, "injectNodeModules failed: ${e.message}", e)
            return "补齐运行依赖失败: ${e.message}"
        }
    }

    /** 单条目落地：去 ./ 前缀，防路径穿越；接受整目录条目（影子副本是完整酒馆目录树）。 */
    private fun extractEntry(
        dir: File, base: String, rawName: String, isDir: Boolean,
        stats: ImportStats, copy: (OutputStream) -> Unit
    ) {
        var name = rawName.replace('\\', '/')
        while (name.startsWith("./")) name = name.substring(2)
        if (name.isEmpty() || name.startsWith("/") || name.split('/').any { it == ".." }) {
            stats.skipped++
            return
        }
        val outFile = File(dir, name)
        if (!outFile.canonicalPath.startsWith(base)) {
            stats.skipped++
            return
        }
        try {
            if (isDir) outFile.mkdirs()
            else {
                outFile.parentFile?.mkdirs()
                FileOutputStream(outFile).use(copy)
                stats.accepted++
            }
        } catch (e: Exception) {
            Log.e(TAG, "extractEntry fail: $name (${e.message})")
        }
    }

    // ========== 启动（app 自带内核跑影子副本） ==========

    private fun onStartClicked() {
        if (launched) return
        progressBar.visibility = View.VISIBLE
        statusText.text = if (NodeService.isRunning) "正在停止当前酒馆实例…" else "正在启动影子酒馆..."
        stopExistingNode {
            // Termux 酒馆也可能占着 8000（接管残留）：本 app node 已停但端口仍被占时清一下
            if (!NodeService.isRunning && probe8000()) {
                TermuxBridge.stopTermuxSt(this)
            }
            enterRunUi()
            startShadowNode()
        }
    }

    /** 发 ACTION_STOP（服务内同步置 isRunning=false），最多等 3 秒 */
    private fun stopExistingNode(onDone: () -> Unit) {
        if (!NodeService.isRunning) {
            onDone()
            return
        }
        val stopIntent = Intent(this, NodeService::class.java).apply { action = NodeService.ACTION_STOP }
        try { startService(stopIntent) } catch (e: Exception) { Log.w(TAG, "stop existing: ${e.message}") }
        thread(name = "shadow-stopwait", isDaemon = true) {
            for (i in 1..10) {
                if (!NodeService.isRunning) break
                try { Thread.sleep(300) } catch (_: InterruptedException) { break }
            }
            runOnUiThread { onDone() }
        }
    }

    /** 8000 端口是否有任意响应（含 4xx/5xx，有响应即被占） */
    private fun probe8000(): Boolean = try {
        val conn = java.net.URL("http://127.0.0.1:8000").openConnection() as java.net.HttpURLConnection
        conn.connectTimeout = 1000
        conn.readTimeout = 1000
        val code = conn.responseCode
        conn.disconnect()
        code in 200..599
    } catch (_: Exception) {
        false
    }

    private fun enterRunUi() {
        launched = true
        startButton.visibility = View.GONE
        copyButton.visibility = View.GONE
        importButton.visibility = View.GONE
        retryButton.visibility = View.GONE
        progressBar.visibility = View.VISIBLE
        progressBar.isIndeterminate = true
        logBox.visibility = View.VISIBLE
        statusText.text = "正在启动影子酒馆..."
        displayedLogCount = 0
        logTextView.text = ""
        logRefreshActive = true
        mainHandler.post(logRefresher)
    }

    private fun startShadowNode() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(
                    this, arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 100
                )
            }
        }
        val serviceIntent = Intent(this, NodeService::class.java).apply {
            action = NodeService.ACTION_START
            putExtra(NodeService.EXTRA_ST_DIR, shadowDir.absolutePath)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }
        startRunPolling()
    }

    /** 兜底轮询（防广播丢失），就绪即进 WebView；超时呈现错误与重试 */
    private fun startRunPolling() {
        thread(name = "shadow-run-poll", isDaemon = true) {
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
                } catch (_: Exception) {
                }
                // Node 日志自报就绪也视为成功（兜底广播丢失场景）
                if (NodeService.nodeReportedReady) {
                    runOnUiThread { openWebView() }
                    return@thread
                }
                try { Thread.sleep(1000) } catch (_: InterruptedException) { return@thread }
            }
            if (isFinishing || isDestroyed) return@thread
            runOnUiThread {
                if (!opened) {
                    statusText.text = if (NodeService.lastErrorPublic.isNotEmpty())
                        "影子酒馆启动失败\n${NodeService.lastErrorPublic}"
                    else
                        "启动耗时较长，请查看日志或点重试"
                    progressBar.visibility = View.GONE
                    stopLogRefresh()
                    updateLogText()
                    showRetryStart()
                }
            }
        }
    }

    private fun showRetryStart() {
        retryButton.text = "重试启动"
        retryButton.visibility = View.VISIBLE
        retryButton.setOnClickListener {
            retryButton.visibility = View.GONE
            progressBar.visibility = View.VISIBLE
            statusText.text = "正在重启影子酒馆..."
            displayedLogCount = 0
            logTextView.text = ""
            stopExistingNode {
                logRefreshActive = true
                mainHandler.post(logRefresher)
                startShadowNode()
            }
        }
    }

    private fun openWebView() {
        if (opened) return
        opened = true
        val intent = Intent(this, WebViewActivity::class.java).apply {
            putExtra(WebViewActivity.EXTRA_URL, WebViewActivity.DEFAULT_URL)
            // bundled 模式：退出 WebView 时 stopService(NodeService) 即停影子 node
            putExtra(WebViewActivity.EXTRA_STOP_MODE, WebViewActivity.STOP_MODE_BUNDLED)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        startActivity(intent)
        finish()
    }

    // ========== 日志显示（同 SplashActivity） ==========

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

    private fun stopLogRefresh() {
        logRefreshActive = false
        mainHandler.removeCallbacks(logRefresher)
    }

    override fun onDestroy() {
        stopLogRefresh()
        try {
            unregisterReceiver(serverReceiver)
        } catch (_: Exception) {
        }
        // 启动中退出（尚未进 WebView）：视为放弃，停掉影子 node 避免半启动占端口
        if (launched && !opened) {
            try {
                stopService(Intent(this, NodeService::class.java))
            } catch (_: Exception) {
            }
        }
        super.onDestroy()
    }
}
