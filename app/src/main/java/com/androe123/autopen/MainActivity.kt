package com.androe123.autopen

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.ContentValues
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.util.Base64
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.FileProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.webkit.WebViewAssetLoader
import java.io.File
import kotlin.concurrent.thread

/**
 * Android-версия «АвтоРучки».
 * Повторяет app.py: загружает index.html в WebView и предоставляет
 * API load_settings / save_settings / load_photos / save_photos.
 */
class MainActivity : ComponentActivity() {

    companion object {
        private const val ASSET_HOST = "appassets.androidplatform.net"
        private const val START_URL = "https://$ASSET_HOST/assets/index.html"
        private const val DOWNLOAD_SUBDIR = "АвтоРучка"
    }

    private lateinit var root: FrameLayout
    private lateinit var webView: WebView
    private lateinit var assetLoader: WebViewAssetLoader

    private var filePathCallback: ValueCallback<Array<Uri>>? = null
    private var cameraUri: Uri? = null
    private var cameraFile: File? = null

    private val storageDir: File by lazy { File(filesDir, "data").apply { mkdirs() } }

    private val pickFiles =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
            val cb = filePathCallback ?: return@registerForActivityResult
            filePathCallback = null
            val uris = mutableListOf<Uri>()
            if (res.resultCode == RESULT_OK) {
                val data = res.data
                val clip = data?.clipData
                if (clip != null) {
                    for (i in 0 until clip.itemCount) clip.getItemAt(i).uri?.let { uris.add(it) }
                } else {
                    data?.data?.let { uris.add(it) }
                }
                if (uris.isEmpty()) {
                    val f = cameraFile
                    val u = cameraUri
                    if (f != null && u != null && f.exists() && f.length() > 0) uris.add(u)
                }
            }
            cb.onReceiveValue(if (uris.isEmpty()) null else uris.toTypedArray())
        }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)

        root = FrameLayout(this).apply { setBackgroundColor(Color.parseColor("#111424")) }
        webView = WebView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
            )
            setBackgroundColor(Color.parseColor("#0C0E1A"))
        }
        root.addView(webView)
        setContentView(root)

        // Отступы под статус-бар, навигацию и клавиатуру
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            v.setPadding(bars.left, bars.top, bars.right, maxOf(bars.bottom, ime.bottom))
            WindowInsetsCompat.CONSUMED
        }

        assetLoader = WebViewAssetLoader.Builder()
            .setDomain(ASSET_HOST)
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this))
            .build()

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            @Suppress("DEPRECATION")
            databaseEnabled = true
            allowFileAccess = false
            allowContentAccess = true
            loadWithOverviewMode = true
            useWideViewPort = true
            setSupportZoom(false)
            builtInZoomControls = false
            displayZoomControls = false
            textZoom = 100
            cacheMode = WebSettings.LOAD_DEFAULT
            mediaPlaybackRequiresUserGesture = true
        }

        webView.addJavascriptInterface(Bridge(), "AndroidBridge")
        webView.webViewClient = AppWebViewClient()
        webView.webChromeClient = AppChromeClient()

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = onBack()
        })

        if (savedInstanceState != null) webView.restoreState(savedInstanceState)
        if (webView.url == null) webView.loadUrl(START_URL)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        webView.saveState(outState)
    }

    override fun onResume() {
        super.onResume()
        webView.onResume()
    }

    override fun onPause() {
        webView.onPause()
        super.onPause()
    }

    override fun onDestroy() {
        webView.destroy()
        super.onDestroy()
    }

    // ---------------- Кнопка «Назад» ----------------

    private fun onBack() {
        webView.evaluateJavascript(
            "(window.__autopenBackState ? window.__autopenBackState() : 'clean')"
        ) { result ->
            when (result?.trim('"')) {
                "modal" -> Unit // модальное окно закрыто
                "dirty" -> AlertDialog.Builder(this)
                    .setTitle("Несохранённые изменения")
                    .setMessage("Изменения могут быть не применены, если не нажать «Сохранить». Сохранить перед выходом?")
                    .setPositiveButton("Сохранить и выйти") { _, _ ->
                        webView.evaluateJavascript("window.__autopenSaveAndExit()", null)
                    }
                    .setNegativeButton("Выйти") { _, _ -> finish() }
                    .setNeutralButton("Отмена", null)
                    .show()
                else -> finish()
            }
        }
    }

    // ---------------- WebView ----------------

    private inner class AppWebViewClient : WebViewClient() {
        override fun shouldInterceptRequest(
            view: WebView, request: WebResourceRequest
        ): WebResourceResponse? = assetLoader.shouldInterceptRequest(request.url)

        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            val url = request.url
            if (url.host == ASSET_HOST) return false
            openExternal(url)
            return true
        }
    }

    private inner class AppChromeClient : WebChromeClient() {
        override fun onShowFileChooser(
            webView: WebView,
            callback: ValueCallback<Array<Uri>>,
            params: FileChooserParams
        ): Boolean {
            filePathCallback?.onReceiveValue(null)
            filePathCallback = callback

            val accept = params.acceptTypes.joinToString(",").lowercase()
            val imagesOnly = accept.isNotBlank() &&
                accept.split(',').map { it.trim() }.filter { it.isNotEmpty() }
                    .all { it.startsWith("image/") }
            val multiple = params.mode == FileChooserParams.MODE_OPEN_MULTIPLE

            val content = Intent(Intent.ACTION_GET_CONTENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = if (imagesOnly) "image/*" else "*/*"
                putExtra(Intent.EXTRA_ALLOW_MULTIPLE, multiple)
            }

            val chooser = Intent.createChooser(
                content, if (imagesOnly) "Выберите фото страниц" else "Выберите файл"
            )

            // Для фото страниц можно сразу сфотографировать тетрадь камерой
            cameraUri = null
            cameraFile = null
            if (imagesOnly) {
                try {
                    val dir = File(cacheDir, "camera").apply { mkdirs() }
                    val f = File(dir, "page_${System.currentTimeMillis()}.jpg")
                    val uri = FileProvider.getUriForFile(
                        this@MainActivity, "$packageName.fileprovider", f
                    )
                    val cam = Intent(MediaStore.ACTION_IMAGE_CAPTURE).apply {
                        putExtra(MediaStore.EXTRA_OUTPUT, uri)
                        addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    if (cam.resolveActivity(packageManager) != null) {
                        cameraFile = f
                        cameraUri = uri
                        chooser.putExtra(Intent.EXTRA_INITIAL_INTENTS, arrayOf(cam))
                    }
                } catch (_: Exception) {
                }
            }

            return try {
                pickFiles.launch(chooser)
                true
            } catch (e: ActivityNotFoundException) {
                filePathCallback = null
                toast("Не найдено приложение для выбора файлов")
                false
            }
        }
    }

    // ---------------- Хранилище (аналог DesktopApi) ----------------

    private fun readText(name: String): String? {
        val f = File(storageDir, name)
        return if (f.exists()) try { f.readText(Charsets.UTF_8) } catch (_: Exception) { null } else null
    }

    private fun writeText(name: String, text: String): Boolean = try {
        val tmp = File(storageDir, "$name.tmp")
        tmp.writeText(text, Charsets.UTF_8)
        val dst = File(storageDir, name)
        if (dst.exists()) dst.delete()
        tmp.renameTo(dst)
    } catch (_: Exception) {
        false
    }

    // ---------------- Сохранение PNG / PDF ----------------

    private fun saveToDownloads(name: String, mime: String, bytes: ByteArray): Uri {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, mime)
                put(
                    MediaStore.MediaColumns.RELATIVE_PATH,
                    "${Environment.DIRECTORY_DOWNLOADS}/$DOWNLOAD_SUBDIR"
                )
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val resolver = contentResolver
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: throw IllegalStateException("MediaStore insert failed")
            resolver.openOutputStream(uri)?.use { it.write(bytes) }
                ?: throw IllegalStateException("Cannot open output stream")
            values.clear()
            values.put(MediaStore.MediaColumns.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            return uri
        } else {
            // Android 8–9: папка приложения, без запроса разрешений
            val dir = File(getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "").apply { mkdirs() }
            var f = File(dir, name)
            var i = 1
            while (f.exists()) {
                f = File(dir, name.substringBeforeLast('.') + " ($i)." + name.substringAfterLast('.'))
                i++
            }
            f.writeBytes(bytes)
            return FileProvider.getUriForFile(this, "$packageName.fileprovider", f)
        }
    }

    private fun onFileSaved(name: String, mime: String, uri: Uri) {
        val where = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
            "Загрузки/$DOWNLOAD_SUBDIR" else "папку приложения"
        if (mime == "application/pdf") {
            AlertDialog.Builder(this)
                .setTitle("PDF сохранён")
                .setMessage("Файл «$name» сохранён в $where.")
                .setPositiveButton("Открыть") { _, _ -> openFile(uri, mime) }
                .setNeutralButton("Поделиться") { _, _ -> shareFile(uri, mime) }
                .setNegativeButton("OK", null)
                .show()
        } else {
            toast("Сохранено: $where/$name")
        }
    }

    private fun openFile(uri: Uri, mime: String) {
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mime)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try {
            startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            toast("Нет приложения для просмотра PDF")
        }
    }

    private fun shareFile(uri: Uri, mime: String) {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = mime
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(intent, "Поделиться"))
    }

    private fun openExternal(uri: Uri) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, uri))
        } catch (_: ActivityNotFoundException) {
            toast("Не найден браузер")
        }
    }

    private fun toast(msg: String) {
        runOnUiThread { Toast.makeText(this, msg, Toast.LENGTH_SHORT).show() }
    }

    // ---------------- JS-мост ----------------

    @Suppress("unused")
    private inner class Bridge {
        @JavascriptInterface
        fun loadSettings(): String? = readText("settings.json")

        @JavascriptInterface
        fun saveSettings(json: String): Boolean = writeText("settings.json", json)

        @JavascriptInterface
        fun loadPhotos(): String? = readText("photos.json")

        @JavascriptInterface
        fun savePhotos(json: String): Boolean = writeText("photos.json", json)

        @JavascriptInterface
        fun saveFile(name: String, mime: String, base64: String) {
            val safeName = name.replace(Regex("[\\\\/:*?\"<>|]"), "_").ifBlank { "file" }
            thread {
                try {
                    val bytes = Base64.decode(base64, Base64.DEFAULT)
                    val uri = saveToDownloads(safeName, mime, bytes)
                    runOnUiThread { onFileSaved(safeName, mime, uri) }
                } catch (e: Exception) {
                    toast("Ошибка сохранения: ${e.message}")
                }
            }
        }

        @JavascriptInterface
        fun openUrl(url: String) {
            runOnUiThread { openExternal(Uri.parse(url)) }
        }

        @JavascriptInterface
        fun toast(msg: String) = this@MainActivity.toast(msg)

        @JavascriptInterface
        fun setDarkTheme(dark: Boolean) {
            runOnUiThread {
                val panel = if (dark) "#111424" else "#F7F9FD"
                root.setBackgroundColor(Color.parseColor(panel))
                @Suppress("DEPRECATION")
                if (Build.VERSION.SDK_INT < 35) {
                    window.statusBarColor = Color.parseColor(panel)
                    window.navigationBarColor = Color.parseColor(if (dark) "#0C0E1A" else "#EEF1F8")
                }
                WindowCompat.getInsetsController(window, root).apply {
                    isAppearanceLightStatusBars = !dark
                    isAppearanceLightNavigationBars = !dark
                }
            }
        }

        @JavascriptInterface
        fun exitApp() {
            runOnUiThread { finish() }
        }
    }
}
