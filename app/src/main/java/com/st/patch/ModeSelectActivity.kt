package com.st.patch

import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.st.patch.common.TermuxBridge

/**
 * 模式选择页：应用入口。点选后进入对应模式，不再直接启动。
 *
 * - 启动：app 自带内核（libnode + app.zip），免 Termux，等价原自动启动流程。
 * - 接管：让 Termux 原地启动其目录下的酒馆，app WebView 打开 127.0.0.1:8000，不复制文件，退出即停。
 * - 数据迁移：把 Termux 原版酒馆的用户数据迁入 app 自带内核。
 * - 修补：用 app 补丁版酒馆覆盖 Termux 原版酒馆的代码（保留数据）。
 * - 影子：把 Termux 的酒馆整目录复制进 app（复制-once），由 app 自带内核运行，与 Termux 解耦。
 *
 * 互操作均经 Termux RUN_COMMAND。
 */
class ModeSelectActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_mode_select)

        findViewById<Button>(R.id.btnLaunch).setOnClickListener { startLaunch() }

        setupCard(R.id.cardTakeoverHeader, R.id.cardTakeoverDetail, R.id.cardTakeoverHint)
        findViewById<Button>(R.id.btnTakeover).setOnClickListener { startTakeover() }

        setupCard(R.id.cardMigrateHeader, R.id.cardMigrateDetail, R.id.cardMigrateHint)
        findViewById<Button>(R.id.btnMigrate).setOnClickListener { startMigrate() }

        setupCard(R.id.cardUpgradeHeader, R.id.cardUpgradeDetail, R.id.cardUpgradeHint)
        findViewById<Button>(R.id.btnUpgrade).setOnClickListener { startUpgrade() }

        setupCard(R.id.cardShadowHeader, R.id.cardShadowDetail, R.id.cardShadowHint)
        findViewById<Button>(R.id.btnShadow).setOnClickListener { startShadow() }

        findViewById<Button>(R.id.btnSettings).setOnClickListener {
            startActivity(Intent(this, ConfigEditorActivity::class.java))
        }
    }

    /** 卡片展开/收起 */
    private fun setupCard(headerId: Int, detailId: Int, hintId: Int) {
        val header = findViewById<LinearLayout>(headerId)
        val detail = findViewById<LinearLayout>(detailId)
        val hint = findViewById<TextView>(hintId)
        header.setOnClickListener {
            if (detail.visibility == View.VISIBLE) {
                detail.visibility = View.GONE
                hint.text = "展开 ▼"
            } else {
                detail.visibility = View.VISIBLE
                hint.text = "收起 ▲"
            }
        }
    }

    /** 启动模式：自带内核流程（原 SplashActivity 自动启动）。不 finish 自身，返回可回到本页。 */
    private fun startLaunch() {
        startActivity(Intent(this, SplashActivity::class.java))
    }

    /** 接管模式：经 RUN_COMMAND 接管 Termux 酒馆 */
    private fun startTakeover() {
        if (!ensureTermux("接管模式")) return
        startActivity(Intent(this, TakeoverActivity::class.java))
    }

    /** 数据迁移：把 Termux 原版酒馆数据迁入 app 自带内核 */
    private fun startMigrate() {
        if (!ensureTermux("数据迁移")) return
        startActivity(Intent(this, PatchActivity::class.java))
    }

    /** 修补：用 app 补丁版酒馆覆盖 Termux 原版酒馆代码 */
    private fun startUpgrade() {
        if (!ensureTermux("修补模式")) return
        startActivity(Intent(this, PatchUpgradeActivity::class.java))
    }

    /** 影子：把 Termux 酒馆整目录复制进 app（复制-once），用自带内核运行 */
    private fun startShadow() {
        if (!ensureTermux("影子模式")) return
        startActivity(Intent(this, ShadowActivity::class.java))
    }

    /** 校验 Termux 已安装（各互操作模式共用） */
    private fun ensureTermux(mode: String): Boolean {
        if (!TermuxBridge.isTermuxInstalled(this)) {
            AlertDialog.Builder(this)
                .setTitle("$mode 需要 Termux")
                .setMessage(
                    "请先安装 Termux，并在 ~/.termux/termux.properties 设置：\n\n" +
                    "allow-external-apps = true\n\n" +
                    "随后在 Termux 内执行 termux-reload-settings，再回来使用。\n" +
                    "酒馆目录名不限，使用时会自动扫描识别。"
                )
                .setPositiveButton("确定", null)
                .show()
            return false
        }
        return true
    }
}
