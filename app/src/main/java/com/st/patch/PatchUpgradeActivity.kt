package com.st.patch

import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.st.patch.common.PatchExporter
import com.st.patch.common.TermuxBridge
import com.st.patch.common.TermuxScripts
import kotlin.concurrent.thread

/**
 * 修补模式：用 app 自带的补丁版酒馆（assets/sillytavern/app.zip）覆盖 Termux
 * 原版酒馆（目录名任意，脚本内特征扫描自动识别）的代码。app.zip 仅含代码（不含 data/ 与 config.yaml），
 * 故覆盖即升级代码，用户角色卡/聊天/设置不动。覆盖后用「接管」模式运行。
 * 原版与补丁版特征文件相同，脚本以 app.zip 内的 .st-patch-build 版本标记区分：
 * 只覆盖原版或旧版补丁；已是当前补丁版的候选跳过。
 *
 * app 不能读/写 Termux 私有目录，故走：app 导出 app.zip + apply.sh 到 Download → 拉起 Termux
 * （launcher 绕开 OPPO 服务拦截）→ Termux 自行 unzip 覆盖其酒馆目录。尽量自动投递 apply.sh；
 * 失败则回退显示手动命令。
 *
 * 结果反馈：RUN_COMMAND 无执行回传通道，apply.sh 结束时把结果覆写到 app 预创建的
 * patch-result.txt（app 经 MediaStore 创建、归自己所有 → 免权限读回；Termux 经 FUSE 覆写）。
 * 投递后轮询该文件（3 分钟超时），按 ok / skipped / error / 超时给出对应界面；
 * 冷 Termux 首投被 OPPO 静默拦截时在 2 秒后按「仍无结果」补投一次。
 */
class PatchUpgradeActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "PatchUpgrade"
        private const val PERM_RUN_COMMAND = "com.termux.permission.RUN_COMMAND"
        private const val RESULT_NAME = "patch-result.txt"
        private const val POLL_TIMEOUT_SEC = 180
        private const val REDISPATCH_AT_SEC = 2
    }

    /** apply.sh 回写的执行结果（status=ok|skipped|error|pending） */
    private data class ApplyResult(val status: String, val target: String, val version: String, val detail: String)

    private lateinit var statusText: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var actionButton: Button
    private lateinit var importButton: Button
    private var shPath: String? = null
    private var resultUri: Uri? = null
    @Volatile private var polling = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.i(TAG, "onCreate")
        window.setFlags(
            WindowManager.LayoutParams.FLAG_FULLSCREEN,
            WindowManager.LayoutParams.FLAG_FULLSCREEN
        )
        setContentView(R.layout.activity_patch)
        findViewById<TextView>(R.id.pageTitle).text = "修补模式"
        statusText = findViewById(R.id.statusText)
        progressBar = findViewById(R.id.progressBar)
        actionButton = findViewById(R.id.actionButton)
        importButton = findViewById(R.id.importButton)
        importButton.visibility = View.GONE
        actionButton.setOnClickListener { finish() }
        start()
    }

    private fun start() {
        if (!TermuxBridge.isTermuxInstalled(this)) {
            showFinal("未安装 Termux。\n请先安装 Termux，并装好原版酒馆（目录名不限，自动识别）。")
            return
        }
        statusText.text = "正在导出补丁包到 Download…"
        thread(name = "upgrade-prep", isDaemon = true) {
            if (isFinishing || isDestroyed) return@thread
            val zipPath = PatchExporter.exportAppZip(this) { mb ->
                runOnUiThread { statusText.text = "正在导出补丁包… $mb MB" }
            }
            Log.i(TAG, "exportAppZip=$zipPath")
            if (zipPath == null) {
                showFinal("导出补丁包失败（无法写 Download）。")
                return@thread
            }
            val sh = PatchExporter.writeTextToDownloads(this, "apply.sh", TermuxScripts.applyScript())
            Log.i(TAG, "wrote apply.sh=$sh")
            if (sh == null) {
                showFinal("导出修补脚本失败。")
                return@thread
            }
            // 结果文件必须由 app 预创建（归 app 所有才能免权限读回）；创建失败不阻断流程，仅无结果反馈
            val resultFile = PatchExporter.writeTextFile(this, RESULT_NAME, "status=pending\n")
            Log.i(TAG, "result file: ${resultFile?.path} uri=${resultFile?.uri}")
            runOnUiThread {
                shPath = sh
                resultUri = resultFile?.uri
                progressBar.visibility = View.GONE
                statusText.text = "已就绪。点「修补」拉起 Termux 自动覆盖原版酒馆（保留你的数据）。\n" +
                    "未自动执行可稍后在 Termux 运行：sh $sh"
                actionButton.text = "修补"
                actionButton.visibility = View.VISIBLE
                actionButton.setOnClickListener { onPatchClicked() }
            }
        }
    }

    private fun onPatchClicked() {
        val sh = shPath ?: return
        progressBar.visibility = View.VISIBLE
        val hasPerm =
            ContextCompat.checkSelfPermission(this, PERM_RUN_COMMAND) == PackageManager.PERMISSION_GRANTED
        if (hasPerm) {
            // 先投递（此刻 app 在前台，跨应用服务启动不被后台限制拦截），再拉起 Termux 预热/展示
            TermuxBridge.sendRunCommand(
                this,
                path = TermuxBridge.TERMUX_SH,
                arguments = arrayOf(sh),
                workdir = TermuxBridge.TERMUX_HOME,
                background = true
            )
            TermuxBridge.launchTermux(this)
            if (resultUri == null) {
                showFinal("已投递修补脚本并拉起 Termux。完成后用「接管」模式运行。\n未自动执行则在 Termux 运行：sh $sh")
            } else {
                statusText.text = "已拉起 Termux，正在修补…（覆盖代码约需 1–2 分钟，可稍候）"
                pollResult(redispatchPath = sh)
            }
        } else {
            TermuxBridge.launchTermux(this)
            if (resultUri == null) {
                showFinal("无 RUN_COMMAND 权限，已拉起 Termux。\n请手动在 Termux 运行：sh $sh")
            } else {
                statusText.text = "无 RUN_COMMAND 权限，已拉起 Termux。\n请手动运行：sh $sh\n运行完成后回到本页，这里会显示修补结果。"
                pollResult(redispatchPath = null)
            }
        }
    }

    /**
     * 轮询结果文件直到拿到非 pending 结果或超时。仍在等待且到点（默认 2 秒）时补投一次
     * （兜底冷 Termux 首投被 OPPO 拦截；apply.sh 幂等，重复执行只是再次覆盖）。
     */
    private fun pollResult(redispatchPath: String?) {
        if (polling) return
        polling = true
        thread(name = "upgrade-poll", isDaemon = true) {
            var redispatched = false
            for (sec in 1..POLL_TIMEOUT_SEC) {
                if (isFinishing || isDestroyed) { polling = false; return@thread }
                val r = readResult()
                if (r != null && r.status != "pending") {
                    Log.i(TAG, "apply result: $r")
                    runOnUiThread { showResult(r) }
                    polling = false
                    return@thread
                }
                if (!redispatched && sec >= REDISPATCH_AT_SEC && redispatchPath != null) {
                    redispatched = true
                    TermuxBridge.sendRunCommand(
                        this,
                        path = TermuxBridge.TERMUX_SH,
                        arguments = arrayOf(redispatchPath),
                        workdir = TermuxBridge.TERMUX_HOME,
                        background = true
                    )
                }
                if (sec % 15 == 0) {
                    val s = sec
                    runOnUiThread { if (polling) statusText.text = "仍在 Termux 内执行修补…（已等 ${s}s）" }
                }
                try { Thread.sleep(1000) } catch (_: InterruptedException) { polling = false; return@thread }
            }
            if (isFinishing || isDestroyed) { polling = false; return@thread }
            Log.w(TAG, "poll timeout, no result")
            runOnUiThread { showTimeout() }
            polling = false
        }
    }

    private fun readResult(): ApplyResult? {
        val uri = resultUri ?: return null
        return try {
            contentResolver.openInputStream(uri)?.bufferedReader()?.use { reader ->
                val map = mutableMapOf<String, String>()
                reader.lineSequence().forEach { line ->
                    val i = line.indexOf('=')
                    if (i > 0) map[line.substring(0, i).trim()] = line.substring(i + 1)
                }
                val st = map["status"] ?: return null
                ApplyResult(st, map["target"] ?: "", map["version"] ?: "", map["detail"] ?: "")
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun showResult(r: ApplyResult) {
        progressBar.visibility = View.GONE
        when (r.status) {
            "ok" -> {
                statusText.text = "修补成功！\n${r.target}\n已升级为补丁版 ${r.version}，角色卡/聊天/设置等数据未动。"
                actionButton.text = "去接管运行"
                actionButton.setOnClickListener {
                    startActivity(Intent(this, TakeoverActivity::class.java))
                }
                importButton.text = "完成"
                importButton.visibility = View.VISIBLE
                importButton.setOnClickListener { finish() }
            }
            "skipped" -> {
                statusText.text = "无需修补：${r.detail}"
                actionButton.text = "完成"
                actionButton.setOnClickListener { finish() }
                importButton.visibility = View.GONE
            }
            else -> {
                statusText.text = "修补未完成：${r.detail}\n详情见 Download/st-patch/apply-debug.log。"
                actionButton.text = "重试"
                actionButton.setOnClickListener { retry() }
                importButton.text = "完成"
                importButton.visibility = View.VISIBLE
                importButton.setOnClickListener { finish() }
            }
        }
    }

    private fun showTimeout() {
        val sh = shPath ?: ""
        progressBar.visibility = View.GONE
        statusText.text = "等待修补结果超时。若 Termux 未自动执行，请手动运行：sh $sh\n" +
            "执行情况见 Download/st-patch/apply-debug.log。"
        actionButton.text = "重试"
        actionButton.setOnClickListener { retry() }
        importButton.text = "完成"
        importButton.visibility = View.VISIBLE
        importButton.setOnClickListener { finish() }
    }

    /** 重试：先把结果文件重置为 pending（避免立刻读到上一轮的旧结果），再走投递流程 */
    private fun retry() {
        val rf = PatchExporter.writeTextFile(this, RESULT_NAME, "status=pending\n")
        resultUri = rf?.uri
        onPatchClicked()
    }

    private fun showFinal(msg: String) {
        runOnUiThread {
            progressBar.visibility = View.GONE
            statusText.text = msg
            actionButton.text = "完成"
            actionButton.visibility = View.VISIBLE
            actionButton.setOnClickListener { finish() }
        }
    }
}
