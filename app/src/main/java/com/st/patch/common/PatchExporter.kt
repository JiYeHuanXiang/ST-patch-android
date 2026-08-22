package com.st.patch.common

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import java.io.File

/**
 * 把文件写到公共下载目录 Download/st-patch/，供 Termux 自取：
 * - 修补：app 导出补丁版酒馆 app.zip + apply.sh 到此，Termux 读取并覆盖其识别到的原版酒馆。
 * - 数据迁移：app 写 export-data.sh 到此，Termux 读取并打包其 data 回此目录，app 用 SAF 导入。
 *
 * API 29+ 走 MediaStore.Downloads（免存储权限）；以下回退到公共 Download 目录直写
 * （WRITE_EXTERNAL_STORAGE 已声明 maxSdk=28）。
 */
object PatchExporter {

    private const val TAG = "PatchExporter"
    const val SUBDIR = "st-patch"
    const val PUBLIC_DIR = "/sdcard/Download/$SUBDIR"
    private const val REL_PATH = "Download/$SUBDIR"
    private const val ASSET_ZIP = "sillytavern/app.zip"

    /** 导出 assets 里的 app.zip（补丁版酒馆代码）到 Download/st-patch/app.zip，返回公共路径或 null。 */
    fun exportAppZip(context: Context, onProgress: ((Int) -> Unit)? = null): String? {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val resolver = context.contentResolver
                deleteExisting(context, "app.zip")
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, "app.zip")
                    put(MediaStore.Downloads.MIME_TYPE, "application/zip")
                    put(MediaStore.Downloads.RELATIVE_PATH, REL_PATH)
                    put(MediaStore.Downloads.IS_PENDING, 1)
                }
                val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                    ?: return null
                try {
                    resolver.openOutputStream(uri)?.use { out ->
                        context.assets.open(ASSET_ZIP).use { inp -> copyStream(inp, out, onProgress) }
                    } ?: run { resolver.delete(uri, null, null); return null }
                    values.clear()
                    values.put(MediaStore.Downloads.IS_PENDING, 0)
                    resolver.update(uri, values, null, null)
                } catch (e: Exception) {
                    resolver.delete(uri, null, null)
                    return null
                }
                "$PUBLIC_DIR/app.zip"
            } else {
                @Suppress("DEPRECATION")
                val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), SUBDIR)
                dir.mkdirs()
                val file = File(dir, "app.zip")
                file.outputStream().use { out ->
                    context.assets.open(ASSET_ZIP).use { inp -> copyStream(inp, out, onProgress) }
                }
                file.absolutePath
            }
        } catch (e: Exception) {
            Log.e(TAG, "exportAppZip failed: ${e.message}", e)
            null
        }
    }

    /** 写出的公共文件：path = 给 Termux/shell 用的绝对路径；uri = Q+ 下 MediaStore Uri（app 自己创建的文件可凭它免权限读回）。 */
    data class TextFile(val path: String, val uri: Uri?)

    /**
     * 写文本文件到 Download/st-patch/<name>，返回路径与（Q+ 的）ContentResolver Uri。
     * 供需要回读的场景（修补结果文件：app 建文件归自己 → Termux 经 FUSE 覆写内容 → app 轮询自己的 uri）。
     */
    fun writeTextFile(context: Context, name: String, content: String): TextFile? {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val resolver = context.contentResolver
                deleteExisting(context, name)
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, name)
                    put(MediaStore.Downloads.MIME_TYPE, "application/octet-stream")
                    put(MediaStore.Downloads.RELATIVE_PATH, REL_PATH)
                    put(MediaStore.Downloads.IS_PENDING, 1)
                }
                val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                    ?: return null
                try {
                    resolver.openOutputStream(uri)?.use { it.write(content.toByteArray()) }
                        ?: run { resolver.delete(uri, null, null); return null }
                    values.clear()
                    values.put(MediaStore.Downloads.IS_PENDING, 0)
                    resolver.update(uri, values, null, null)
                } catch (e: Exception) {
                    resolver.delete(uri, null, null)
                    return null
                }
                TextFile("$PUBLIC_DIR/$name", uri)
            } else {
                @Suppress("DEPRECATION")
                val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), SUBDIR)
                dir.mkdirs()
                val file = File(dir, name)
                file.writeText(content)
                TextFile(file.absolutePath, null)
            }
        } catch (e: Exception) {
            Log.e(TAG, "writeTextFile failed: ${e.message}", e)
            null
        }
    }

    /** 写文本文件到 Download/st-patch/<name>，返回公共绝对路径或 null。 */
    fun writeTextToDownloads(context: Context, name: String, content: String): String? =
        writeTextFile(context, name, content)?.path

    /** 删除 Download 下同名旧记录，避免 MediaStore 自增后缀导致路径漂移。 */
    private fun deleteExisting(context: Context, name: String) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        try {
            val resolver = context.contentResolver
            val coll = MediaStore.Downloads.EXTERNAL_CONTENT_URI
            resolver.query(
                coll, arrayOf(MediaStore.MediaColumns._ID),
                "${MediaStore.MediaColumns.DISPLAY_NAME}=?", arrayOf(name), null
            )?.use { c ->
                val idIdx = c.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                while (c.moveToNext()) {
                    resolver.delete(ContentUris.withAppendedId(coll, c.getLong(idIdx)), null, null)
                }
            }
        } catch (_: Exception) {
        }
    }

    private fun copyStream(
        inp: java.io.InputStream, out: java.io.OutputStream, onProgress: ((Int) -> Unit)?
    ) {
        val buf = ByteArray(65536)
        var total = 0L
        while (true) {
            val n = inp.read(buf)
            if (n <= 0) break
            out.write(buf, 0, n)
            total += n
            onProgress?.invoke((total / 1048576L).toInt())
        }
        out.flush()
    }
}
