package com.st.patch.common

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log

/**
 * Termux 互操作桥：基于 Termux 的 RUN_COMMAND intent——app 请 Termux 以 termux
 * 用户身份代为执行命令（启动/停止酒馆节点、导出数据等），全程不碰 /data/data/com.termux
 * 私有文件，无需 su。
 *
 * 新建数据文件天然归 termux 所有，Termux 之后仍可正常读写。
 *
 * 前提（用户一次性设置，无法自举——开 RUN_COMMAND 本身就先要它）：
 *  1. 安装 Termux；
 *  2. 在 ~/.termux/termux.properties 设 allow-external-apps = true，并在 Termux 内执行 termux-reload-settings；
 *  3. （可选）termux-wake-lock 降低后台被杀概率。
 *
 * 注意：RUN_COMMAND 的 extra / 前台服务行为随 Termux 版本与 Android 版本而异，
 * 关键路径需在目标真机复核。OPPO 会对"跨应用 startService 拉起 com.termux 进程"做拦截
 * （OplusAppStartupManager），故需先经 launcher Activity 把 Termux 进程带起来再投递命令。
 */
object TermuxBridge {

    private const val TAG = "TermuxBridge"

    const val TERMUX_PKG = "com.termux"
    private const val RUN_COMMAND_ACTION = "com.termux.RUN_COMMAND"
    private const val RUN_COMMAND_SERVICE = "com.termux.app.RunCommandService"

    const val TERMUX_PREFIX = "/data/data/com.termux/files/usr"
    const val TERMUX_HOME = "/data/data/com.termux/files/home"
    const val TERMUX_NODE = "$TERMUX_PREFIX/bin/node"
    const val TERMUX_SH = "$TERMUX_PREFIX/bin/sh"
    const val TERMUX_BASH = "$TERMUX_PREFIX/bin/bash"
    const val TERMUX_LIB = "$TERMUX_PREFIX/lib"

    // ========== 检测 ==========

    /** Termux 是否已安装 */
    fun isTermuxInstalled(context: Context): Boolean = try {
        context.packageManager.getPackageInfo(TERMUX_PKG, 0) != null
    } catch (_: PackageManager.NameNotFoundException) {
        false
    }

    /**
     * RUN_COMMAND 是否可用（best-effort）。allow-external-apps 开关在 Termux 配置文件里，
     * 外部 app 无法可靠探测，故仅判断 Termux 已安装；真正能否执行以 sendRunCommand 返回值为准。
     */
    fun isRunCommandAvailable(context: Context): Boolean = isTermuxInstalled(context)

    // ========== RUN_COMMAND ==========

    /**
     * 经 RUN_COMMAND 请 Termux 执行一条命令（以 termux 身份，在 Termux 环境内）。
     * @return 是否成功投递（不代表命令执行成功；Termux 内执行结果不回传给本 app）
     */
    fun sendRunCommand(
        context: Context,
        path: String,
        arguments: Array<String>,
        workdir: String,
        background: Boolean = true
    ): Boolean {
        val intent = Intent().apply {
            setClassName(TERMUX_PKG, RUN_COMMAND_SERVICE)
            action = RUN_COMMAND_ACTION
            putExtra("com.termux.RUN_COMMAND_PATH", path)
            putExtra("com.termux.RUN_COMMAND_ARGUMENTS", arguments)
            putExtra("com.termux.RUN_COMMAND_WORKDIR", workdir)
            putExtra("com.termux.RUN_COMMAND_BACKGROUND", background)
            putExtra("com.termux.RUN_COMMAND_SESSION_ACTION", "0")
        }
        Log.i(TAG, "RUN_COMMAND dispatch: path=$path workdir=$workdir bg=$background")
        return try {
            // Android 8+ 用 startForegroundService（Termux 的 RunCommandService 会自行 startForeground）
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
            Log.i(TAG, "RUN_COMMAND dispatched (no exception)")
            true
        } catch (e: Exception) {
            // 常见失败：未设 allow-external-apps、Termux 未安装、Android 12+ 后台起服务受限
            Log.w(TAG, "sendRunCommand failed: ${e.message}", e)
            false
        }
    }

    /**
     * 以 termux 身份原地启动 Termux 酒馆（接管），并附守卫：app 持有 beacon 端口时 node 持续运行；
     * app 一旦消失（划掉/崩溃/被杀）beacon 随进程关闭，守卫连续 3 次探测失败即 kill node，
     * 杜绝后台残留耗电/占端口。beaconPort 由 app 绑定 OS 分配的空闲端口获得。
     * 脚本落地 Download 再经 RUN_COMMAND 执行（RUN_COMMAND 对 -c 多行/长命令兼容性差）；
     * 酒馆目录由脚本特征扫描自动识别（用户目录名任意，不能假定），日志写 ~/st-takeover.log。
     * 端口固定 8000（与 app WebView 一致）；Termux 酒馆若改过端口需改回。
     */
    fun startTermuxSt(context: Context, beaconPort: Int): Boolean {
        Log.i(TAG, "startTermuxSt: beacon=$beaconPort")
        val scriptPath = PatchExporter.writeTextToDownloads(
            context, "takeover.sh", TermuxScripts.takeoverScript(beaconPort)
        )
        if (scriptPath == null) {
            Log.w(TAG, "startTermuxSt: write takeover.sh failed")
            return false
        }
        return sendRunCommand(
            context,
            path = TERMUX_SH,
            arguments = arrayOf(scriptPath, beaconPort.toString()),
            workdir = TERMUX_HOME,
            background = true
        )
    }

    /** 以 termux 身份杀掉 Termux 里的 SillyTavern node 进程 */
    fun stopTermuxSt(context: Context): Boolean {
        return sendRunCommand(
            context,
            path = TERMUX_SH,
            arguments = arrayOf("-c", "pkill -f 'node.*server.js' 2>/dev/null; true"),
            workdir = TERMUX_HOME,
            background = true
        )
    }

    /** 申请 Termux 唤醒锁（best-effort，降低后台 node 被回收） */
    fun acquireWakeLock(context: Context): Boolean {
        return sendRunCommand(
            context,
            path = "$TERMUX_PREFIX/bin/termux-wake-lock",
            arguments = emptyArray(),
            workdir = TERMUX_HOME,
            background = true
        )
    }

    /** 释放 Termux 唤醒锁 */
    fun releaseWakeLock(context: Context): Boolean {
        return sendRunCommand(
            context,
            path = "$TERMUX_PREFIX/bin/termux-wake-release",
            arguments = emptyArray(),
            workdir = TERMUX_HOME,
            background = true
        )
    }

    // ========== Termux 拉起 ==========

    /** 拉起 Termux 主界面（launcher Activity，绕开 OPPO 对跨应用 startService 的拦截）。 */
    fun launchTermux(context: Context): Boolean {
        val intent = context.packageManager.getLaunchIntentForPackage(TERMUX_PKG) ?: return false
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try { context.startActivity(intent); true }
        catch (e: Exception) { Log.w(TAG, "launchTermux: ${e.message}"); false }
    }
}
