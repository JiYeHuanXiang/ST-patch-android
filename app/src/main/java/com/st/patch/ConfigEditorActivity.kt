package com.st.patch

import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.st.patch.common.ConfigPatcher
import java.io.File

/**
 * 酒馆配置编辑器：编辑 app 自带内核的 config.yaml（filesDir/sillytavern/app/config.yaml）。
 * 应用私有目录，外部文件管理器不可见，故 app 内置编辑器（这是打开它的唯一途径）。
 *
 * config.yaml 由服务端首次启动时生成并补全全部配置项；不存在时预填最小覆盖模板
 * （服务端按默认值合并，只写覆盖项也合法）。修改保存后需停止并重新「启动」才生效。
 */
class ConfigEditorActivity : AppCompatActivity() {

    private lateinit var configFile: File
    private lateinit var editor: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_config_editor)

        configFile = ConfigPatcher.configFile(filesDir)
        editor = findViewById(R.id.configEditor)

        val existed = configFile.exists()
        editor.setText(if (existed) configFile.readText() else ConfigPatcher.MINIMAL_TEMPLATE)
        findViewById<TextView>(R.id.editorHint).text =
            "文件：sillytavern/app/config.yaml（app 私有目录）\n" +
            (if (existed) "" else "config.yaml 尚未生成，当前为最小覆盖模板（其余项由服务端按默认值补全）。\n") +
            "保存后需停止并重新「启动」才生效；酒馆正在运行时请勿同时改动。"

        findViewById<Button>(R.id.btnLazyLoad).setOnClickListener { applyLazyLoad() }
        findViewById<Button>(R.id.btnSaveConfig).setOnClickListener { save() }
    }

    /** 把 performance.lazyLoadCharacters 置 true（大量角色卡时不开启会报「角色长度已达上限」） */
    private fun applyLazyLoad() {
        val text = editor.text.toString()
        if (!ConfigPatcher.hasLazyLoadKey(text)) {
            Toast.makeText(this, "未找到 lazyLoadCharacters，请在 performance: 下手动添加", Toast.LENGTH_LONG).show()
            return
        }
        val patched = ConfigPatcher.setLazyLoadCharacters(text)
        if (patched == text) {
            Toast.makeText(this, "lazyLoadCharacters 已是 true", Toast.LENGTH_SHORT).show()
        } else {
            editor.setText(patched)
            Toast.makeText(this, "已置 true，请点「保存」", Toast.LENGTH_SHORT).show()
        }
    }

    private fun save() {
        try {
            configFile.parentFile?.mkdirs()
            configFile.writeText(editor.text.toString())
            Toast.makeText(this, "已保存。重启「启动」模式后生效。", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Toast.makeText(this, "保存失败：${e.message}", Toast.LENGTH_LONG).show()
        }
    }
}
