package com.st.patch

import androidx.appcompat.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.transition.TransitionManager
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.st.patch.common.PublicCharactersLinker
import com.st.patch.common.TermuxBridge
import java.io.File

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

    companion object {
        private const val REQUEST_MANAGE_STORAGE = 10003
        private const val REQUEST_MEDIA_PERMISSION = 10004
        private const val REQUEST_LEGACY_STORAGE = 10005
    }

    /** 授权返回后挂起的「启用公共目录角色卡」请求（存 variant） */
    private var pendingPublicCharsEnable: String? = null

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

        findViewById<Button>(R.id.btnPublicChars).setOnClickListener { togglePublicCharacters() }

        findViewById<Button>(R.id.btnStoragePerm).setOnClickListener { requestStoragePermission() }

        findViewById<Button>(R.id.btnSettings).setOnClickListener {
            startActivity(Intent(this, ConfigEditorActivity::class.java))
        }
    }

    /** 卡片展开/收起（带过渡动画） */
    private fun setupCard(headerId: Int, detailId: Int, hintId: Int) {
        val header = findViewById<LinearLayout>(headerId)
        val detail = findViewById<LinearLayout>(detailId)
        val hint = findViewById<TextView>(hintId)
        header.setOnClickListener {
            val parent = detail.parent as android.view.ViewGroup
            TransitionManager.beginDelayedTransition(parent)
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

    // ========== 存储权限与公共目录角色卡 ==========

    /**
     * 申请存储 / 管理所有文件权限。
     * - Android 11+：跳系统「所有文件访问权限」设置页（MANAGE_EXTERNAL_STORAGE）。
     * - Android 13+：额外运行时申请 READ_MEDIA_IMAGES。
     * - Android 8-10：运行时申请 READ/WRITE_EXTERNAL_STORAGE。
     */
    private fun requestStoragePermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (Environment.isExternalStorageManager()) {
                requestMediaPermissionsIfNeeded()
                Toast.makeText(this, "已获得管理所有文件权限", Toast.LENGTH_SHORT).show()
                return
            }
            try {
                val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                    data = Uri.parse("package:$packageName")
                }
                startActivityForResult(intent, REQUEST_MANAGE_STORAGE)
            } catch (e: Exception) {
                try {
                    val intent = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                    startActivityForResult(intent, REQUEST_MANAGE_STORAGE)
                } catch (e2: Exception) {
                    Toast.makeText(this, "无法跳转文件权限设置，请手动在系统设置中开启", Toast.LENGTH_LONG).show()
                    return
                }
            }
            Toast.makeText(this, "请开启「所有文件访问权限」后返回应用", Toast.LENGTH_LONG).show()
        } else {
            requestLegacyStoragePermissions()
        }
    }

    /** Android 13+：运行时申请媒体读取权限 */
    private fun requestMediaPermissionsIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val perms = ArrayList<String>()
        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.READ_MEDIA_IMAGES)
            != PackageManager.PERMISSION_GRANTED
        ) {
            perms.add(android.Manifest.permission.READ_MEDIA_IMAGES)
        }
        if (perms.isEmpty()) {
            Toast.makeText(this, "媒体读取权限已开启", Toast.LENGTH_SHORT).show()
            return
        }
        ActivityCompat.requestPermissions(this, perms.toTypedArray(), REQUEST_MEDIA_PERMISSION)
    }

    /** Android 8-10：运行时申请读写外部存储 */
    private fun requestLegacyStoragePermissions() {
        val perms = ArrayList<String>()
        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.READ_EXTERNAL_STORAGE)
            != PackageManager.PERMISSION_GRANTED
        ) {
            perms.add(android.Manifest.permission.READ_EXTERNAL_STORAGE)
        }
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P &&
            ContextCompat.checkSelfPermission(this, android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
            != PackageManager.PERMISSION_GRANTED
        ) {
            perms.add(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
        if (perms.isEmpty()) {
            Toast.makeText(this, "存储权限已开启", Toast.LENGTH_SHORT).show()
            return
        }
        ActivityCompat.requestPermissions(this, perms.toTypedArray(), REQUEST_LEGACY_STORAGE)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_MANAGE_STORAGE) {
            val granted = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
                    Environment.isExternalStorageManager()
            if (pendingPublicCharsEnable != null) {
                val variant = pendingPublicCharsEnable!!
                pendingPublicCharsEnable = null
                if (granted) {
                    PublicCharactersLinker.setEnabled(this, variant, true)
                    val label = if (variant == PublicCharactersLinker.VARIANT_SHADOW) "原版（影子）" else "补丁版"
                    Toast.makeText(this, "已启用${label}公共目录角色卡，下次进入酒馆生效", Toast.LENGTH_LONG).show()
                } else {
                    Toast.makeText(this, "需开启所有文件访问权限才能启用", Toast.LENGTH_LONG).show()
                }
            } else {
                Toast.makeText(
                    this,
                    if (granted) "已获得管理所有文件权限" else "未开启所有文件访问权限",
                    Toast.LENGTH_SHORT
                ).show()
                if (granted) requestMediaPermissionsIfNeeded()
            }
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        val granted = grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }
        val msg = when (requestCode) {
            REQUEST_LEGACY_STORAGE -> if (granted) "存储权限已开启" else "存储权限被拒绝"
            REQUEST_MEDIA_PERMISSION -> if (granted) "媒体读取权限已开启" else "媒体读取权限被拒绝"
            else -> return
        }
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    /**
     * 切换公共目录角色卡开关（仅 Android 11+）。
     * 补丁版与原版（影子）角色卡目录严格分离：
     * - 补丁版 → Download/SillyTavern/patch/characters
     * - 原版（影子） → Download/SillyTavern/shadow/characters
     * 先选择版本，再对该版本独立启用/关闭。
     * 原版选项仅在影子副本已创建时显示，避免在无影子酒馆时误启用。
     */
    private fun togglePublicCharacters() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            Toast.makeText(this, "该功能需 Android 11 以上", Toast.LENGTH_LONG).show()
            return
        }

        val patchOn = PublicCharactersLinker.isEnabled(this, PublicCharactersLinker.VARIANT_PATCH)
        val shadowOn = PublicCharactersLinker.isEnabled(this, PublicCharactersLinker.VARIANT_SHADOW)

        // 影子副本是否存在（server.js 作为有效标志）
        val shadowExists = File(filesDir, "sillytavern-shadow/server.js").exists()

        val items = ArrayList<String>()
        val itemVariants = ArrayList<String>()
        val itemLabels = ArrayList<String>()

        items.add("补丁版（启动模式）" + if (patchOn) "  ✓已开启" else "")
        itemVariants.add(PublicCharactersLinker.VARIANT_PATCH)
        itemLabels.add("补丁版")

        if (shadowExists) {
            items.add("原版（影子模式）" + if (shadowOn) "  ✓已开启" else "")
            itemVariants.add(PublicCharactersLinker.VARIANT_SHADOW)
            itemLabels.add("原版（影子）")
        }

        AlertDialog.Builder(this)
            .setTitle("角色卡公共目录（选择版本）")
            .setItems(items.toTypedArray()) { _, which ->
                val variant = itemVariants[which]
                val label = itemLabels[which]
                if (PublicCharactersLinker.isEnabled(this, variant)) {
                    confirmDisable(variant, label)
                } else {
                    confirmEnable(variant, label)
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun confirmEnable(variant: String, label: String) {
        // 校验酒馆已运行过：data/default-user/characters 须由后端生成，
        // 否则符号链接因父目录缺失而静默失败
        if (!PublicCharactersLinker.privateCharactersDir(this, variant).exists()) {
            AlertDialog.Builder(this)
                .setTitle("${label}酒馆尚未运行")
                .setMessage("需先启动一次酒馆，待角色卡数据目录生成后再启用公共目录角色卡。")
                .setPositiveButton("确定", null)
                .show()
            return
        }
        AlertDialog.Builder(this)
            .setTitle("启用${label}公共目录角色卡")
            .setMessage(
                "${label}角色卡将迁移到对应公有目录，" +
                        "可用系统文件管理器直接增删角色卡（PNG/JSON）。\n\n" +
                        "将迁移现有角色卡，下次进入酒馆生效。"
            )
            .setPositiveButton("启用") { _, _ ->
                if (Environment.isExternalStorageManager()) {
                    PublicCharactersLinker.setEnabled(this, variant, true)
                    Toast.makeText(this, "已启用${label}，下次进入酒馆生效", Toast.LENGTH_LONG).show()
                } else {
                    pendingPublicCharsEnable = variant
                    requestStoragePermission()
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun confirmDisable(variant: String, label: String) {
        AlertDialog.Builder(this)
            .setTitle("关闭${label}公共目录角色卡")
            .setMessage("${label}角色卡将迁回应用私有目录，下次进入酒馆生效。")
            .setPositiveButton("关闭") { _, _ ->
                PublicCharactersLinker.setEnabled(this, variant, false)
                Toast.makeText(this, "已关闭${label}，下次进入酒馆生效", Toast.LENGTH_LONG).show()
            }
            .setNegativeButton("取消", null)
            .show()
    }
}
