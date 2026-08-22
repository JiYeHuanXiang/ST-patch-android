package com.st.patch.common

import java.io.File

/**
 * app 自带内核 config.yaml（filesDir/sillytavern/app/config.yaml）的读写/打补丁工具。
 * 该文件在应用私有目录，系统文件管理器不可见，app 内置编辑器（ConfigEditorActivity）负责改它。
 */
object ConfigPatcher {

    /** 最小覆盖模板：config.yaml 未生成时可先写入覆盖项，服务端读取时用默认值补全其余配置 */
    const val MINIMAL_TEMPLATE =
        "# SillyTavern 配置（仅覆盖项，其余由服务端按默认值补全）\n" +
        "performance:\n" +
        "    lazyLoadCharacters: true\n"

    private val LAZY_LOAD_FALSE = Regex("(lazyLoadCharacters:[ \\t]*)false")

    fun configFile(filesDir: File): File = File(filesDir, "sillytavern/app/config.yaml")

    fun hasLazyLoadKey(text: String): Boolean = text.contains("lazyLoadCharacters")

    /** 把文本中的 lazyLoadCharacters: false 置 true；无该键或已 true 则原样返回 */
    fun setLazyLoadCharacters(text: String): String =
        LAZY_LOAD_FALSE.replace(text, "$1true")

    /**
     * 确保角色懒加载开启。迁移导入大量角色卡后不开启会在启动时报「角色长度已达上限」，
     * 故导入成功后调用：config.yaml 不存在则创建最小覆盖模板，存在则把 false 改 true
     * （用户之后若手动改回 false，此处不会再次强行翻转——只在导入动作时执行一次）。
     * 返回是否写入了文件。
     */
    fun ensureLazyLoadCharacters(filesDir: File): Boolean {
        val f = configFile(filesDir)
        return try {
            if (!f.exists()) {
                f.parentFile?.mkdirs()
                f.writeText(MINIMAL_TEMPLATE)
                true
            } else {
                val text = f.readText()
                val patched = setLazyLoadCharacters(text)
                if (patched != text) {
                    f.writeText(patched)
                    true
                } else {
                    false
                }
            }
        } catch (e: Exception) {
            false
        }
    }
}
