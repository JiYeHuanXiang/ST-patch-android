package com.st.patch

import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.st.patch.common.PatchExporter
import com.st.patch.common.TermuxBridge
import com.st.patch.common.TermuxScripts
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipInputStream
import kotlin.concurrent.thread

/**
 * 数据迁移：把 Termux 原版酒馆（目录名任意，脚本内特征扫描自动识别）的用户数据
 * 迁入 app 自带内核数据目录（filesDir/sillytavern/data），之后用「启动」模式即可带原有数据运行。
 * 只迁数据，不动代码；对 Termux 侧全程只读（仅 tar 复制打包），不删除/修改任何原数据。
 *
 * 用户先勾选要迁移的分类（角色卡/聊天/API key/设置/扩展，默认全选），app 按勾选生成
 * export-data.sh；Termux 侧用 tar（引导包基础组件，无需额外安装）打包所选分类回 Download
 * （scoped storage 下 app 读不了他人文件），app 用 SAF 文件选择器导入（免权限，tar/zip 自动识别）。
 * 导出/导入两按钮并列：已有归档（上一轮导出的 data.tar 等）可直接导入，不必先重跑导出。
 */
class PatchActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "PatchActivity"
        private const val PERM_RUN_COMMAND = "com.termux.permission.RUN_COMMAND"
    }

    /** 可迁移分类：label / 复选框 id / data/default-user/ 下相对路径 */
    private data class Category(val label: String, val cbId: Int, val shPath: String)

    private val categories = listOf(
        Category("角色卡", R.id.cbCharacters, "characters"),
        Category("聊天记录", R.id.cbChats, "chats"),
        Category("API key", R.id.cbSecrets, "secrets.json"),
        Category("系统设置", R.id.cbSettings, "settings.json"),
        Category("扩展插件", R.id.cbExtensions, "extensions")
    )

    private lateinit var statusText: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var actionButton: Button
    private lateinit var importButton: Button
    private lateinit var categoryBox: LinearLayout
    @Volatile private var done = false

    private val pickArchive = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri == null) return@registerForActivityResult
        thread(name = "patch-import", isDaemon = true) {
            if (isFinishing || isDestroyed) return@thread
            runOnUiThread {
                progressBar.visibility = View.VISIBLE
                importButton.visibility = View.GONE
                actionButton.visibility = View.GONE
                statusText.text = "正在导入数据…"
            }
            val err = importArchive(uri)
            Log.i(TAG, "importArchive err=$err")
            if (err == null) {
                // 大量角色卡不开启懒加载会在启动时报「角色长度已达上限」，导入成功后确保开启
                val lazyFixed = com.st.patch.common.ConfigPatcher.ensureLazyLoadCharacters(filesDir)
                showDone(
                    true,
                    "已导入所选数据到 app 自带内核。\n用「启动」模式即可带原有角色卡/聊天运行。" +
                        if (lazyFixed) "\n（已自动开启角色懒加载）" else ""
                )
            }
            else runOnUiThread {
                // 失败可当场重试：恢复两按钮，不进终态
                progressBar.visibility = View.GONE
                actionButton.visibility = View.VISIBLE
                importButton.visibility = View.VISIBLE
                statusText.text = err
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
        setContentView(R.layout.activity_patch)
        findViewById<TextView>(R.id.pageTitle).text = "数据迁移"
        statusText = findViewById(R.id.statusText)
        progressBar = findViewById(R.id.progressBar)
        actionButton = findViewById(R.id.actionButton)
        importButton = findViewById(R.id.importButton)
        categoryBox = findViewById(R.id.categoryBox)

        progressBar.visibility = View.GONE
        categoryBox.visibility = View.VISIBLE
        actionButton.text = "导出数据"
        actionButton.visibility = View.VISIBLE
        actionButton.setOnClickListener { onExportClicked() }
        importButton.text = "导入数据"
        importButton.visibility = View.VISIBLE
        importButton.setOnClickListener { onImportClicked() }

        if (!TermuxBridge.isTermuxInstalled(this)) {
            showDone(false, "未安装 Termux。\n请先安装 Termux，并装好酒馆（目录名不限，自动识别）。")
            return
        }
        statusText.text =
            "勾选要迁移的分类，点「导出数据」在 Termux 打包（只读复制，不会改动或删除原数据）。\n" +
            "已导出过？直接点「导入数据」选 Download/st-patch/data.tar。"
    }

    private fun onExportClicked() {
        val sel = categories.filter { findViewById<CheckBox>(it.cbId).isChecked }.map { it.shPath }
        if (sel.isEmpty()) {
            statusText.text = "请至少勾选一项要迁移的数据。"
            return
        }
        Log.i(TAG, "onExportClicked: ${sel.joinToString()}")
        progressBar.visibility = View.VISIBLE
        statusText.text = "正在生成导出脚本…"
        thread(name = "patch-prep", isDaemon = true) {
            if (isFinishing || isDestroyed) return@thread
            val sh = PatchExporter.writeTextToDownloads(this, "export-data.sh", TermuxScripts.exportDataScript(sel))
            Log.i(TAG, "wrote export-data.sh=$sh")
            if (sh == null) {
                runOnUiThread {
                    progressBar.visibility = View.GONE
                    statusText.text = "无法写导出脚本到 Download。"
                }
                return@thread
            }
            runOnUiThread {
                progressBar.visibility = View.GONE
                statusText.text =
                    "已拉起 Termux 打包所选数据（只读复制，不动原数据；无需额外安装）。\n" +
                    "未自动执行则在 Termux 运行：sh $sh\n" +
                    "打包完成后点「导入数据」选 Download/st-patch/data.tar"
                if (ContextCompat.checkSelfPermission(this, PERM_RUN_COMMAND) == PackageManager.PERMISSION_GRANTED) {
                    // 先投递（此刻 app 在前台，跨应用服务启动不被后台限制拦截），再拉起 Termux 预热/展示
                    TermuxBridge.sendRunCommand(
                        this,
                        path = TermuxBridge.TERMUX_SH,
                        arguments = arrayOf(sh),
                        workdir = TermuxBridge.TERMUX_HOME,
                        background = true
                    )
                    TermuxBridge.launchTermux(this)
                    // 拉起 2 秒后补投一次（兜底冷 Termux 首投被 OPPO 拦截；脚本幂等，重复执行只覆盖同一输出文件）
                    thread(name = "patch-redispatch", isDaemon = true) {
                        try { Thread.sleep(2000) } catch (_: InterruptedException) { return@thread }
                        TermuxBridge.sendRunCommand(
                            this,
                            path = TermuxBridge.TERMUX_SH,
                            arguments = arrayOf(sh),
                            workdir = TermuxBridge.TERMUX_HOME,
                            background = true
                        )
                    }
                } else {
                    TermuxBridge.launchTermux(this)
                }
            }
        }
    }

    /**
     * 导入不必先导出：上一轮已生成的 data.tar（或从别处拷来的归档）可直接选入。
     */
    private fun onImportClicked() {
        // termux 写入的归档可能未被 MediaStore 索引（选择器看不到），导入前触发扫描
        android.media.MediaScannerConnection.scanFile(
            this,
            arrayOf("${PatchExporter.PUBLIC_DIR}/data.tar", "${PatchExporter.PUBLIC_DIR}/data.zip"),
            null, null
        )
        pickArchive.launch(arrayOf("*/*"))
    }

    /** 导入结果计数：accepted=成功落地的有效文件数；skipped=跳过的无关/非法条目数 */
    private class ImportStats {
        var accepted = 0
        var skipped = 0
    }

    /**
     * SAF 选中的归档解压到 app 数据目录。按魔数自动识别 zip（PK）/ tar.gz（1F 8B）/ tar（ustar）。
     * 严格校验：仅接受 data/default-user/… 结构的条目（无关归档的其它条目一律跳过，防误选污染
     * app 数据目录）；一个有效文件都没落地即判失败。返回 null=成功，非 null=给用户看的错误信息。
     */
    private fun importArchive(uri: Uri): String? {
        val stats = ImportStats()
        try {
            val appData = File(filesDir, "sillytavern/data")
            appData.mkdirs()
            val base = appData.canonicalPath + File.separator

            // 魔数嗅探：读前 262 字节（tar 的 ustar 魔数在 257 偏移处）
            val head = ByteArray(262)
            contentResolver.openInputStream(uri)?.use { it.read(head) } ?: return "无法读取所选文件。"
            val isZip = head[0] == 'P'.toByte() && head[1] == 'K'.toByte()
            val isGzip = head[0] == 0x1f.toByte() && head[1] == 0x8b.toByte()
            Log.i(TAG, "importArchive: isZip=$isZip isGzip=$isGzip")

            if (isZip) {
                ZipInputStream(contentResolver.openInputStream(uri)).use { zis ->
                    var entry = zis.nextEntry
                    while (entry != null) {
                        extractEntry(appData, base, entry.name, entry.isDirectory, stats) { o -> zis.copyTo(o) }
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
                        extractEntry(appData, base, entry.name, entry.isDirectory, stats) { o -> tarIn.copyTo(o) }
                        entry = it.nextEntry
                    }
                }
            }
            appData.walkTopDown().forEach {
                it.setReadable(true, false)
                it.setWritable(true, true)
            }
        } catch (e: Exception) {
            Log.e(TAG, "importArchive failed: ${e.message}", e)
            return "导入失败（解压出错或无法读取）。"
        }
        Log.i(TAG, "importArchive: accepted=${stats.accepted} skipped=${stats.skipped}")
        if (stats.accepted == 0) {
            val hint = "\n若选择器里看不到 data.tar：左上角菜单 → 内部存储 → Download → st-patch 选择。"
            return if (stats.skipped > 0)
                "所选文件不含酒馆数据（需要 data/default-user/… 结构的归档），未导入任何内容。"
            else
                "所选文件不是有效的数据归档（支持 tar/tar.gz/zip），未导入任何内容。$hint"
        }
        return null
    }

    /**
     * 单条目落地：仅接受 default-user/ 下的路径（兼容 data/ 前缀），防路径穿越；
     * 无关/非法条目计入 skipped 并跳过；写入失败仅记日志（不中断其余条目）。
     */
    private fun extractEntry(
        appData: File, base: String, rawName: String, isDir: Boolean,
        stats: ImportStats, copy: (java.io.OutputStream) -> Unit
    ) {
        var name = rawName.replace('\\', '/')
        if (name.startsWith("./")) name = name.substring(2)
        if (name.startsWith("data/")) name = name.substring("data/".length)
        if (!name.startsWith("default-user/") ||
            name.isEmpty() || name.startsWith("/") || name.split('/').any { it == ".." }
        ) {
            stats.skipped++
            return
        }
        val outFile = File(appData, name)
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

    private fun showDone(ok: Boolean, msg: String) {
        if (done) return
        done = true
        runOnUiThread {
            progressBar.visibility = View.GONE
            importButton.visibility = View.GONE
            categoryBox.visibility = View.GONE
            statusText.text = msg
            actionButton.text = "完成"
            actionButton.visibility = View.VISIBLE
            actionButton.setOnClickListener { finish() }
        }
    }
}
