package com.st.patch.common

import android.content.Context
import android.os.Build
import android.os.Environment
import android.util.Log
import java.io.File
import java.nio.file.Files

/**
 * 角色卡公共目录映射：可选地把 SillyTavern 的 characters 目录（app 私有目录，
 * 文件管理器不可见）替换为指向公有目录的符号链接，让用户能直接用文件管理器
 * 增删角色卡。
 *
 * 原版（影子模式）与补丁版（启动模式）的角色卡目录严格分离，绝不混合：
 * - 补丁版 → Download/SillyTavern/patch/characters
 * - 原版（影子） → Download/SillyTavern/shadow/characters
 *
 * 仅 Android 11+ 支持（依赖 MANAGE_EXTERNAL_STORAGE 让同 uid 的 Node 进程
 * 直接读写 FUSE 路径）。后端定位 characters 完全由 DATA_ROOT 拼接决定，
 * [isPathSafe] 用 path.resolve 不解析符号链接，因此对后端透明，无需改源码。
 *
 * 调用时机：NodeService 启动 Node 之前（目录已就绪、无并发 IO）。幂等。
 */
object PublicCharactersLinker {

    private const val TAG = "PublicCharsLinker"
    private const val PREFS = "storage_prefs"

    /** 版本标识：补丁版 */
    const val VARIANT_PATCH = "patch"
    /** 版本标识：原版（影子模式） */
    const val VARIANT_SHADOW = "shadow"

    private fun keyEnabled(variant: String) = "public_characters_enabled_$variant"

    fun isEnabled(context: Context, variant: String): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(keyEnabled(variant), false)

    fun setEnabled(context: Context, variant: String, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(keyEnabled(variant), enabled).apply()
    }

    /**
     * 是否有任意版本已启用（供 UI 判断是否已授权后有待启用项）。
     * 旧版 single-key 升级兼容：若旧 key 存在则视作补丁版已启用。
     */
    fun anyEnabled(context: Context): Boolean =
        isEnabled(context, VARIANT_PATCH) || isEnabled(context, VARIANT_SHADOW)

    /** 公有目录绝对路径：/storage/emulated/0/Download/SillyTavern/<variant>/characters */
    private fun publicDir(variant: String): File =
        File(Environment.getExternalStorageDirectory(), "Download/SillyTavern/$variant/characters")

    /**
     * 指定版本私有 characters 目录路径（dataRoot/default-user/characters）。
     *
     * - 补丁版：filesDir/sillytavern/data/default-user/characters
     * - 原版（影子）：filesDir/sillytavern-shadow/data/default-user/characters
     *
     * 该目录在酒馆首次成功运行后由后端创建（非 NodeService 的 mkdirs，后者只建 data/）。
     * 启用公共目录映射前应校验其存在，否则 [ensureLinked] 因父目录缺失而静默跳过。
     * 路径须与 NodeService 的 dataRoot 拼接保持一致。
     */
    fun privateCharactersDir(context: Context, variant: String): File {
        val dataRoot = if (variant == VARIANT_SHADOW)
            File(context.filesDir, "sillytavern-shadow/data")
        else
            File(context.filesDir, "sillytavern/data")
        return File(dataRoot, "default-user/characters")
    }

    /** 是否具备管理所有文件权限（Android 11+） */
    fun hasManagePermission(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
                Environment.isExternalStorageManager()

    /**
     * 在 Node 启动前调用，按开关与权限把 [charactersDir] 处理为符号链接/真目录。幂等。
     *
     * @param context  用于读取偏好
     * @param charactersDir  私有目录中的 characters 路径（dataRoot/default-user/characters）
     * @param variant  版本标识 [VARIANT_PATCH] 或 [VARIANT_SHADOW]
     *
     * - 已启用且有权限：charactersDir 指向该版本对应的公有子目录（迁移已有内容）。
     * - 未启用或无权限：若 charactersDir 是我们建的链接，回迁内容并恢复真目录。
     */
    fun applyForCharactersDir(context: Context, charactersDir: File, variant: String) {
        val pubDir = publicDir(variant)
        if (isEnabled(context, variant) && hasManagePermission()) {
            ensureLinked(charactersDir, pubDir, variant)
        } else {
            ensureUnlinked(charactersDir, pubDir, variant)
        }
    }

    private fun ensureLinked(link: File, target: File, variant: String) {
        // default-user 尚未创建（极罕见的首次启动前置场景）：跳过，下次再建
        val parent = link.parentFile
        if (parent == null || !parent.exists()) {
            Log.i(TAG, "[$variant] Parent not created yet, skip linking: ${link.path}")
            return
        }
        // 已是指向正确公有目录的链接：幂等完成
        if (isLinkTo(link, target)) {
            Log.i(TAG, "[$variant] Already linked to public dir: ${link.path}")
            return
        }
        // 指向别处的符号链接（如旧版共用目录迁移后路径变更）：先清理
        if (Files.isSymbolicLink(link.toPath())) {
            Log.w(TAG, "[$variant] Existing symlink points elsewhere, removing: ${link.path}")
            try { Files.delete(link.toPath()) } catch (e: Exception) {
                Log.e(TAG, "[$variant] Cannot remove stale symlink: ${e.message}")
                return
            }
        }
        // 真目录存在：迁移内容到公有目录，成功后再删私有目录
        if (link.isDirectory) {
            target.mkdirs()
            if (!copyContents(link, target)) {
                Log.e(TAG, "[$variant] Migration copy failed, keep private dir: ${link.path}")
                return
            }
            if (!link.deleteRecursively()) {
                Log.e(TAG, "[$variant] Failed to remove private dir after migration: ${link.path}")
                return
            }
            Log.i(TAG, "[$variant] Migrated characters to public dir: ${target.path}")
        }
        target.mkdirs()
        // 写入 .nomedia，防止角色卡 PNG 被相册等媒体应用扫描显示
        ensureNomedia(target)
        try {
            Files.createSymbolicLink(link.toPath(), target.toPath())
            Log.i(TAG, "[$variant] Symlink created: ${link.path} -> ${target.path}")
        } catch (e: Exception) {
            Log.e(TAG, "[$variant] Failed to create symlink: ${e.message}")
        }
    }

    /** 确保公有目录下存在 .nomedia，阻止媒体扫描器索引角色卡图片 */
    private fun ensureNomedia(dir: File) {
        val nomedia = File(dir, ".nomedia")
        if (nomedia.exists()) return
        try {
            nomedia.createNewFile()
            Log.i(TAG, "Created .nomedia in ${dir.path}")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to create .nomedia: ${e.message}")
        }
    }

    private fun ensureUnlinked(link: File, target: File, variant: String) {
        if (!Files.isSymbolicLink(link.toPath())) return
        if (!isLinkTo(link, target)) {
            // 指向旧版共用目录（Download/SillyTavern/characters）的遗留链接：
            // 也需要回迁，否则用户关闭后私有目录仍是指向公有目录的链接
            val oldShared = File(Environment.getExternalStorageDirectory(),
                "Download/SillyTavern/characters")
            if (!isLinkTo(link, oldShared)) {
                Log.w(TAG, "[$variant] Symlink points elsewhere, leave as-is: ${link.path}")
                return
            }
            // 旧版共用链接回迁到私有目录
            try { Files.delete(link.toPath()) } catch (e: Exception) {
                Log.e(TAG, "[$variant] Cannot remove legacy symlink: ${e.message}")
                return
            }
            link.mkdirs()
            val ok = copyContents(oldShared, link)
            if (ok) {
                oldShared.deleteRecursively()
                cleanupEmptyParents(oldShared)
                Log.i(TAG, "[$variant] Reverted legacy shared dir, removed public dir: ${link.path}")
            } else {
                Log.e(TAG, "[$variant] Legacy revert copy failed, keep public dir: ${oldShared.path}")
            }
            return
        }
        // 回迁：公有目录内容复制回私有真目录，成功后删除公有目录
        try { Files.delete(link.toPath()) } catch (e: Exception) {
            Log.e(TAG, "[$variant] Cannot remove symlink: ${e.message}")
            return
        }
        link.mkdirs()
        val ok = copyContents(target, link)
        if (ok) {
            target.deleteRecursively()
            cleanupEmptyParents(target)
            Log.i(TAG, "[$variant] Reverted: restored to private dir, removed public dir: ${link.path}")
        } else {
            Log.e(TAG, "[$variant] Revert copy failed, keep public dir: ${target.path}")
        }
    }

    private fun isLinkTo(link: File, target: File): Boolean {
        if (!Files.isSymbolicLink(link.toPath())) return false
        return try {
            Files.readSymbolicLink(link.toPath()) == target.toPath()
        } catch (e: Exception) {
            false
        }
    }

    /**
     * 递归复制 [src] 下所有内容到 [dest]（同名跳过，保留目录结构，跳过 .nomedia）。
     * 任一文件复制失败即返回 false，已复制的保留。源目录不被修改。
     */
    private fun copyContents(src: File, dest: File): Boolean {
        if (!src.exists()) return true
        return try {
            src.walkTopDown().forEach { f ->
                if (f == src) return@forEach
                if (f.name == ".nomedia") return@forEach
                val rel = f.relativeTo(src)
                val d = File(dest, rel.path)
                if (f.isDirectory) {
                    d.mkdirs()
                } else if (f.isFile && !d.exists()) {
                    d.parentFile?.mkdirs()
                    f.copyTo(d, overwrite = false)
                }
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "copyContents failed: ${e.message}")
            false
        }
    }

    /** 删除空父目录链（如 patch/ → SillyTavern/ → 不删 Download/） */
    private fun cleanupEmptyParents(dir: File) {
        var current = dir.parentFile
        while (current != null && current.path != "/" &&
               current.path != Environment.getExternalStorageDirectory().absolutePath) {
            if (current.isDirectory && (current.listFiles()?.isEmpty() != false)) {
                current.delete()
            } else {
                break
            }
            current = current.parentFile
        }
    }
}
