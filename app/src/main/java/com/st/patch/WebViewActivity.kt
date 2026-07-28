package com.st.patch

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.ContentValues
import android.content.Intent
import android.net.Uri
import android.net.http.SslError
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.util.Base64
import android.view.KeyEvent
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.webkit.*
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import java.io.File

/**
 * 全屏 WebView Activity，加载本地 SillyTavern 服务器页面。
 * 替代外部浏览器，统一渲染引擎，消除浏览器兼容性差异。
 */
class WebViewActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_URL = "target_url"
        const val DEFAULT_URL = "http://127.0.0.1:8000"
    }

    private lateinit var webView: WebView
    private lateinit var progressBar: ProgressBar
    private lateinit var loadingText: TextView
    private var filePathCallback: ValueCallback<Array<Uri>>? = null
    private val FILE_CHOOSER_REQUEST_CODE = 10001

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContentView(R.layout.activity_webview)

        // 全屏沉浸式（兼容 adjustResize，键盘弹出时窗口可调整）
        // 必须在 setContentView 之后调用，否则 decorView 未初始化
        WindowCompat.setDecorFitsSystemWindows(window, false)
        hideSystemBars()

        webView = findViewById(R.id.webView)
        progressBar = findViewById(R.id.progressBar)
        loadingText = findViewById(R.id.loadingText)

        // 核心修复：监听 IME (键盘) insets 变化，键盘弹出时压缩 WebView 底部可用空间，
        // 从而 WebView 内部 CSS 100vh 重新计算，输入框自然上抬。
        // 比 adjustResize 更可靠（部分 ROM 不生效），比 JS 注入更底层（SPA 不会覆盖）。
        ViewCompat.setOnApplyWindowInsetsListener(webView) { _, insets ->
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            val params = webView.layoutParams as android.widget.FrameLayout.LayoutParams
            params.bottomMargin = ime.bottom
            webView.layoutParams = params
            insets
        }

        val targetUrl = intent.getStringExtra(EXTRA_URL) ?: DEFAULT_URL

        configureWebView()
        configureWebViewClient()
        configureWebChromeClient()
        configureDownloadListener()

        // 注册 JavaScript 桥接，用于文件导出
        webView.addJavascriptInterface(DownloadInterface(this), "AndroidDownload")

        // 返回键处理：优先 WebView 历史后退，无历史时确认退出
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (webView.canGoBack()) {
                    webView.goBack()
                } else {
                    // 确认退出
                    AlertDialog.Builder(this@WebViewActivity)
                        .setTitle("退出 SillyTavern")
                        .setMessage("确定要停止服务器并退出吗？")
                        .setPositiveButton("退出") { _, _ ->
                            // 停止 NodeService
                            val serviceIntent = Intent(this@WebViewActivity, NodeService::class.java)
                            stopService(serviceIntent)
                            finish()
                        }
                        .setNegativeButton("取消", null)
                        .show()
                }
            }
        })

        if (savedInstanceState != null) {
            webView.restoreState(savedInstanceState)
        } else {
            webView.loadUrl(targetUrl)
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun configureWebView() {
        val settings = webView.settings

        // 核心：启用 JavaScript
        settings.javaScriptEnabled = true

        // DOM Storage（localStorage + sessionStorage）
        settings.domStorageEnabled = true

        // 数据库
        settings.databaseEnabled = true

        // 文件访问
        settings.allowFileAccess = true
        settings.allowContentAccess = true

        // 视口适配
        settings.useWideViewPort = true
        settings.loadWithOverviewMode = true

        // 缓存优先（离线场景）
        settings.cacheMode = WebSettings.LOAD_DEFAULT

        // 编码
        settings.defaultTextEncodingName = "UTF-8"

        // 自适应屏幕
        settings.layoutAlgorithm = WebSettings.LayoutAlgorithm.TEXT_AUTOSIZING

        // User-Agent 附加标识，便于前端/服务端识别 Android 环境
        settings.userAgentString = settings.userAgentString + " SillyTavernPatch/Android"

        // 允许混合内容（localhost http 场景）
        settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
    }

    private fun configureWebViewClient() {
        webView.webViewClient = object : WebViewClient() {

            override fun shouldOverrideUrlLoading(
                view: WebView,
                request: WebResourceRequest
            ): Boolean {
                val host = request.url.host
                // 只允许 localhost / 127.0.0.1 在 WebView 内加载
                // 其他外部链接用系统浏览器打开
                if (host == "localhost" || host == "127.0.0.1" || host == "0.0.0.0") {
                    return false // 在 WebView 内继续加载
                }
                // 外部链接跳系统浏览器
                try {
                    val intent = Intent(Intent.ACTION_VIEW, request.url)
                    startActivity(intent)
                } catch (e: Exception) {
                    // 没有浏览器可用时忽略
                }
                return true
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                // 页面加载完成后隐藏加载指示器
                progressBar.visibility = View.GONE
                loadingText.visibility = View.GONE

                // 注入 viewport 修复 JS + 文件导出桥接 JS
                view?.evaluateJavascript("""
                    (function() {
                        // --- viewport 修复 ---
                        var updateHeight = function() {
                            var h = window.visualViewport ? window.visualViewport.height : window.innerHeight;
                            document.documentElement.style.setProperty('--doc-height', h + 'px');
                            document.body.style.setProperty('height', h + 'px', 'important');
                        };
                        updateHeight();
                        if (window.visualViewport) {
                            window.visualViewport.addEventListener('resize', updateHeight);
                            window.visualViewport.addEventListener('scroll', updateHeight);
                        }
                        window.addEventListener('resize', updateHeight);

                        // --- 文件导出桥接 ---
                        if (typeof window.AndroidDownload !== 'undefined') {
                            // 辅助：将 Blob 通过桥接保存
                            var saveBlobViaBridge = function(blob, fileName) {
                                var reader = new FileReader();
                                reader.onload = function() {
                                    var dataUrl = reader.result;
                                    var base64 = dataUrl.substring(dataUrl.indexOf(',') + 1);
                                    window.AndroidDownload.saveFile(base64, fileName, blob.type || 'application/octet-stream');
                                };
                                reader.readAsDataURL(blob);
                            };

                            // 重写 download 函数（聊天导出、预设导出等场景）
                            window.download = function(content, fileName, contentType) {
                                var blob = new Blob([content], { type: contentType || 'application/octet-stream' });
                                saveBlobViaBridge(blob, fileName);
                            };

                            // 拦截 Blob URL 下载（角色卡导出场景：fetch blob → createObjectURL → <a>.click()）
                            var _blobMap = {};
                            var _createObjectURL = URL.createObjectURL;
                            URL.createObjectURL = function(blob) {
                                var url = _createObjectURL.call(URL, blob);
                                _blobMap[url] = blob;
                                return url;
                            };
                            var _revokeObjectURL = URL.revokeObjectURL;
                            URL.revokeObjectURL = function(url) {
                                delete _blobMap[url];
                                _revokeObjectURL.call(URL, url);
                            };
                            var _anchorClick = HTMLAnchorElement.prototype.click;
                            HTMLAnchorElement.prototype.click = function() {
                                if (this.href && this.href.indexOf('blob:') === 0 && this.download) {
                                    var blob = _blobMap[this.href];
                                    if (blob) {
                                        saveBlobViaBridge(blob, this.download);
                                        return;
                                    }
                                }
                                _anchorClick.call(this);
                            };
                        }
                    })();
                """.trimIndent(), null)
            }

            override fun onReceivedSslError(view: WebView?, handler: SslErrorHandler?, error: SslError?) {
                // 本地 localhost 连接，信任自签名证书
                if (error?.url?.contains("127.0.0.1") == true || error?.url?.contains("localhost") == true) {
                    handler?.proceed()
                } else {
                    handler?.cancel()
                }
            }
        }
    }

    private fun configureWebChromeClient() {
        webView.webChromeClient = object : WebChromeClient() {

            /**
             * 处理 <input type="file"> 文件选择器
             * SillyTavern 大量使用：角色卡导入、背景上传、预设导入等
             */
            override fun onShowFileChooser(
                webView: WebView?,
                filePathCallback: ValueCallback<Array<Uri>>?,
                fileChooserParams: FileChooserParams?
            ): Boolean {
                // 取消上一次未完成的选择
                this@WebViewActivity.filePathCallback?.onReceiveValue(null)
                this@WebViewActivity.filePathCallback = filePathCallback

                try {
                    val intent = fileChooserParams?.createIntent()
                    if (intent != null) {
                        startActivityForResult(intent, FILE_CHOOSER_REQUEST_CODE)
                    } else {
                        this@WebViewActivity.filePathCallback = null
                        return false
                    }
                } catch (e: Exception) {
                    this@WebViewActivity.filePathCallback = null
                    return false
                }
                return true
            }

            /** 进度条更新 */
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                super.onProgressChanged(view, newProgress)
                if (newProgress < 100) {
                    progressBar.visibility = View.VISIBLE
                    progressBar.progress = newProgress
                    loadingText.visibility = View.VISIBLE
                    loadingText.text = "正在加载 SillyTavern... $newProgress%"
                }
            }

            /** JS alert → 原生对话框 */
            override fun onJsAlert(
                view: WebView?, url: String?, message: String?, result: JsResult?
            ): Boolean {
                // 简单实现：使用系统 AlertDialog
                AlertDialog.Builder(this@WebViewActivity)
                    .setMessage(message)
                    .setPositiveButton("确定") { _, _ -> result?.confirm() }
                    .setOnCancelListener { result?.cancel() }
                    .show()
                return true
            }

            /** JS confirm → 原生确认框 */
            override fun onJsConfirm(
                view: WebView?, url: String?, message: String?, result: JsResult?
            ): Boolean {
                AlertDialog.Builder(this@WebViewActivity)
                    .setMessage(message)
                    .setPositiveButton("确定") { _, _ -> result?.confirm() }
                    .setNegativeButton("取消") { _, _ -> result?.cancel() }
                    .setOnCancelListener { result?.cancel() }
                    .show()
                return true
            }

            /** JS prompt → 原生输入框 */
            override fun onJsPrompt(
                view: WebView?, url: String?, message: String?, defaultValue: String?, result: JsPromptResult?
            ): Boolean {
                val input = android.widget.EditText(this@WebViewActivity)
                input.setText(defaultValue)
                AlertDialog.Builder(this@WebViewActivity)
                    .setMessage(message)
                    .setView(input)
                    .setPositiveButton("确定") { _, _ -> result?.confirm(input.text.toString()) }
                    .setNegativeButton("取消") { _, _ -> result?.cancel() }
                    .setOnCancelListener { result?.cancel() }
                    .show()
                return true
            }
        }
    }

    private fun configureDownloadListener() {
        webView.setDownloadListener { url, userAgent, contentDisposition, mimetype, contentLength ->
            // 下载请求：聊天导出、备份下载等
            // 通过系统浏览器或下载管理器处理
            try {
                val uri = Uri.parse(url)
                if (uri.host == "127.0.0.1" || uri.host == "localhost") {
                    // 本地服务器下载：直接用浏览器 intent 打开
                    val intent = Intent(Intent.ACTION_VIEW, uri)
                    startActivity(intent)
                } else {
                    // 外部下载
                    val intent = Intent(Intent.ACTION_VIEW, uri)
                    startActivity(intent)
                }
            } catch (e: Exception) {
                // 无法处理下载
            }
        }
    }

    /**
     * 文件选择器回调
     */
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == FILE_CHOOSER_REQUEST_CODE) {
            val result = WebChromeClient.FileChooserParams.parseResult(resultCode, data)
            filePathCallback?.onReceiveValue(result)
            filePathCallback = null
        }
    }

    /**
     * 返回键：优先 WebView 历史后退，无历史时确认退出
     */
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK && webView.canGoBack()) {
            webView.goBack()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }



    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        webView.saveState(outState)
    }

    override fun onResume() {
        super.onResume()
        // 重新应用沉浸式
        hideSystemBars()
    }

    /**
     * 隐藏系统栏（状态栏 + 导航栏），兼容 adjustResize。
     * Android 11+：使用 WindowInsetsController（避免 LAYOUT_* flag 阻止 adjustResize）
     * Android 9-10：使用 systemUiVisibility（LAYOUT_* flag 在此版本与 adjustResize 兼容）
     */
    private fun hideSystemBars() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.insetsController?.apply {
                hide(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
                systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                or View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            )
        }
    }

    /**
     * JavaScript 桥接接口：将文件保存到设备 Downloads 目录。
     * 前端通过 window.AndroidDownload.saveFile(base64, fileName, mimeType) 调用。
     */
    inner class DownloadInterface(private val activity: Activity) {

        @JavascriptInterface
        fun saveFile(base64Data: String, fileName: String, mimeType: String) {
            try {
                val decodedBytes = Base64.decode(base64Data, Base64.DEFAULT)

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    // Android 10+：使用 MediaStore
                    saveViaMediaStore(decodedBytes, fileName, mimeType)
                } else {
                    // Android 9：使用传统文件写入
                    saveViaLegacyFile(decodedBytes, fileName, mimeType)
                }
            } catch (e: Exception) {
                activity.runOnUiThread {
                    Toast.makeText(activity, "导出失败: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }

        private fun saveViaMediaStore(data: ByteArray, fileName: String, mimeType: String) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                put(MediaStore.Downloads.MIME_TYPE, mimeType)
                put(MediaStore.Downloads.IS_PENDING, 1)
            }

            val resolver = activity.contentResolver
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: throw Exception("无法创建下载文件")

            resolver.openOutputStream(uri)?.use { outputStream ->
                outputStream.write(data)
            } ?: throw Exception("无法写入文件")

            // 标记写入完成
            values.clear()
            values.put(MediaStore.Downloads.IS_PENDING, 0)
            resolver.update(uri, values, null, null)

            activity.runOnUiThread {
                Toast.makeText(activity, "已导出: $fileName", Toast.LENGTH_LONG).show()
            }
        }

        @Suppress("DEPRECATION")
        private fun saveViaLegacyFile(data: ByteArray, fileName: String, mimeType: String) {
            val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            if (!downloadsDir.exists()) {
                downloadsDir.mkdirs()
            }

            // 处理重名：追加 (1), (2) 等
            var destFile = File(downloadsDir, fileName)
            var counter = 1
            val dotIndex = fileName.lastIndexOf('.')
            val baseName = if (dotIndex > 0) fileName.substring(0, dotIndex) else fileName
            val ext = if (dotIndex > 0) fileName.substring(dotIndex) else ""
            while (destFile.exists()) {
                destFile = File(downloadsDir, "${baseName} (${counter})${ext}")
                counter++
            }

            destFile.writeBytes(data)

            // 通知系统扫描文件
            val intent = Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE)
            intent.data = Uri.fromFile(destFile)
            activity.sendBroadcast(intent)

            activity.runOnUiThread {
                Toast.makeText(activity, "已导出: ${destFile.name}", Toast.LENGTH_LONG).show()
            }
        }
    }

    override fun onDestroy() {
        webView.destroy()
        super.onDestroy()
    }
}
