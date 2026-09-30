package com.zyd.browser

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.app.DownloadManager
import android.app.role.RoleManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.text.InputFilter
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.webkit.CookieManager
import android.webkit.DownloadListener
import android.webkit.GeolocationPermissions
import android.webkit.PermissionRequest
import android.webkit.SafeBrowsingResponse
import android.webkit.ServiceWorkerClient
import android.webkit.ServiceWorkerController
import android.webkit.SslErrorHandler
import android.webkit.URLUtil
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import android.net.http.SslError
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.PopupWindow
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import com.google.zxing.integration.android.IntentIntegrator

class MainActivity : Activity() {
    private lateinit var webView: WebView
    private lateinit var addressBar: EditText
    private lateinit var progressBar: ProgressBar
    private lateinit var backButton: Button
    private lateinit var forwardButton: Button
    private lateinit var reloadButton: Button
    private lateinit var schemeButton: Button
    private lateinit var shieldButton: Button
    private lateinit var favoriteButton: Button
    private lateinit var adBlocker: AdBlocker
    private lateinit var database: BrowserDatabase
    private lateinit var searchEngine: SearchEngine

    private var homeHtml = ""
    private var desktopMode = false
    private var filePathCallback: ValueCallback<Array<Uri>>? = null
    private var defaultUserAgent = ""
    private var selectedScheme = "http"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.rgb(247, 249, 252)
        window.navigationBarColor = Color.WHITE
        window.decorView.systemUiVisibility =
            View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR

        adBlocker = AdBlocker(this)
        database = BrowserDatabase(this)
        searchEngine = SearchEngine.fromId(
            getSharedPreferences(PREFERENCES, MODE_PRIVATE).getString(KEY_SEARCH_ENGINE, null)
        )
        homeHtml = assets.open("home.html").bufferedReader().use { it.readText() }
        buildUi()
        configureWebView()
        configureServiceWorkerBlocking()

        val restored = savedInstanceState != null && webView.restoreState(savedInstanceState) != null
        if (!restored) openFromIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        openFromIntent(intent)
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(247, 249, 252))
        }

        val toolbar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(6), dp(6), dp(6), dp(6))
        }

        backButton = toolbarButton("‹", "后退") { goBack() }
        forwardButton = toolbarButton("›", "前进") { if (webView.canGoForward()) webView.goForward() }
        val homeButton = toolbarButton("⌂", "主页") { loadHome() }

        schemeButton = Button(this).apply {
            text = "${getString(R.string.scheme_http)} ▾"
            contentDescription = "切换 HTTP 或 HTTPS"
            textSize = 12f
            isAllCaps = false
            minWidth = 0
            minimumWidth = 0
            minHeight = 0
            minimumHeight = 0
            setPadding(0, 0, 0, dp(2))
            setTextColor(Color.rgb(35, 43, 56))
            background = null
            setOnClickListener { showSchemeMenu(it) }
        }

        addressBar = EditText(this).apply {
            hint = "搜索或输入网址"
            isSingleLine = true
            textSize = 15f
            setSelectAllOnFocus(true)
            setPadding(dp(14), 0, dp(14), 0)
            background = roundedBackground(Color.WHITE, dp(18).toFloat())
            imeOptions = EditorInfo.IME_ACTION_GO
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_VARIATION_URI
            filters = arrayOf(InputFilter { source, start, end, _, _, _ ->
                val entered = source.subSequence(start, end).toString()
                val normalized = normalizeAddressPunctuation(entered)
                if (normalized == entered) null else normalized
            })
            setOnEditorActionListener { _, actionId, event ->
                val submitted = actionId == EditorInfo.IME_ACTION_GO ||
                    (event?.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN)
                if (submitted) {
                    navigate(addressBar.text.toString())
                    clearFocus()
                    hideKeyboard()
                }
                submitted
            }
        }

        reloadButton = toolbarButton("↻", "刷新") {
            if (progressBar.visibility == View.VISIBLE) webView.stopLoading() else webView.reload()
        }
        val menuButton = toolbarButton("⋮", "更多") { showMenu(it) }

        toolbar.addView(schemeButton, LinearLayout.LayoutParams(dp(64), dp(42)).apply {
            marginEnd = dp(4)
        })
        toolbar.addView(
            addressBar,
            LinearLayout.LayoutParams(0, dp(42), 1f).apply {
                marginEnd = dp(4)
            }
        )
        toolbar.addView(reloadButton)

        progressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            visibility = View.GONE
        }

        webView = WebView(this).apply {
            setBackgroundColor(Color.WHITE)
        }

        val bottomBar = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), dp(3), dp(4), dp(3))
            setBackgroundColor(Color.WHITE)
        }
        bottomBar.addView(backButton)
        bottomBar.addView(forwardButton)
        bottomBar.addView(homeButton)
        shieldButton = toolbarButton("⛨ 0", "广告拦截") { toggleAdBlocking() }.apply {
            textSize = 16f
            layoutParams = LinearLayout.LayoutParams(dp(64), dp(42))
        }
        updateShieldText()
        bottomBar.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        bottomBar.addView(shieldButton)
        favoriteButton = toolbarButton("☆", "收藏") { toggleBookmark() }.apply {
            textSize = 24f
        }
        updateFavoriteButton()
        bottomBar.addView(favoriteButton)
        bottomBar.addView(menuButton)

        root.addView(toolbar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(56)))
        root.addView(progressBar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(2)))
        root.addView(webView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        root.addView(bottomBar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)))
        setContentView(root)
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun configureWebView() {
        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(webView, false)
        }

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            loadsImagesAutomatically = true
            mediaPlaybackRequiresUserGesture = true
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            allowFileAccess = false
            allowContentAccess = true
            javaScriptCanOpenWindowsAutomatically = false
            setSupportMultipleWindows(false)
            builtInZoomControls = true
            displayZoomControls = false
            loadWithOverviewMode = true
            useWideViewPort = true
            safeBrowsingEnabled = true
            defaultUserAgent = userAgentString
        }

        webView.webViewClient = BrowserClient()
        webView.webChromeClient = BrowserChromeClient()
        webView.setDownloadListener(createDownloadListener())
    }

    private fun configureServiceWorkerBlocking() {
        ServiceWorkerController.getInstance().setServiceWorkerClient(object : ServiceWorkerClient() {
            override fun shouldInterceptRequest(request: WebResourceRequest): WebResourceResponse? {
                val response = adBlocker.interceptServiceWorker(request)
                if (response != null) runOnUiThread(::updateShieldText)
                return response
            }
        })
    }

    private inner class BrowserClient : WebViewClient() {
        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            val uri = request.url
            if (uri.scheme == INTERNAL_SCHEME) {
                if (uri.host == "scan" && view.url == HOME_URL) startScanner()
                return true
            }
            if (uri.scheme == "http" || uri.scheme == "https") return false
            if (!request.hasGesture()) return true
            return openExternalUri(uri)
        }

        override fun shouldInterceptRequest(
            view: WebView,
            request: WebResourceRequest
        ): WebResourceResponse? {
            val response = adBlocker.intercept(request)
            if (response != null) runOnUiThread(::updateShieldText)
            return response
        }

        override fun onPageStarted(view: WebView, url: String?, favicon: android.graphics.Bitmap?) {
            super.onPageStarted(view, url, favicon)
            adBlocker.resetCount()
            updateAddress(url)
            updateNavigationButtons()
            updateShieldText()
        }

        override fun onPageFinished(view: WebView, url: String?) {
            super.onPageFinished(view, url)
            updateAddress(url)
            updateNavigationButtons()
            updateFavoriteButton()
            if (url != null && url != HOME_URL &&
                (url.startsWith("https://") || url.startsWith("http://"))
            ) {
                database.recordVisit(view.title.orEmpty(), url, view.favicon)
            }
            if (adBlocker.enabled) view.evaluateJavascript(AdBlocker.COSMETIC_FILTER_SCRIPT, null)
        }

        override fun onReceivedError(
            view: WebView,
            request: WebResourceRequest,
            error: WebResourceError
        ) {
            super.onReceivedError(view, request, error)
            if (request.isForMainFrame) {
                Toast.makeText(this@MainActivity, "页面加载失败：${error.description}", Toast.LENGTH_SHORT).show()
            }
        }

        override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
            handler.cancel()
            Toast.makeText(this@MainActivity, "已阻止证书不安全的连接", Toast.LENGTH_LONG).show()
        }

        @SuppressLint("NewApi") // The framework only invokes this API on Android 8.1 and newer.
        override fun onSafeBrowsingHit(
            view: WebView,
            request: WebResourceRequest,
            threatType: Int,
            callback: SafeBrowsingResponse
        ) {
            callback.backToSafety(true)
            Toast.makeText(this@MainActivity, "安全浏览已阻止危险页面", Toast.LENGTH_LONG).show()
        }
    }

    private inner class BrowserChromeClient : WebChromeClient() {
        override fun onProgressChanged(view: WebView, newProgress: Int) {
            progressBar.progress = newProgress
            progressBar.visibility = if (newProgress in 1..99) View.VISIBLE else View.GONE
            reloadButton.text = if (newProgress in 1..99) "×" else "↻"
            reloadButton.contentDescription = if (newProgress in 1..99) "停止" else "刷新"
        }

        override fun onReceivedTitle(view: WebView, title: String?) {
            super.onReceivedTitle(view, title)
            updateNavigationButtons()
            updateFavoriteButton()
        }

        override fun onReceivedIcon(view: WebView, icon: Bitmap) {
            super.onReceivedIcon(view, icon)
            val url = view.url ?: return
            if (url != HOME_URL && (url.startsWith("https://") || url.startsWith("http://"))) {
                database.updateHistoryIcon(url, icon)
            }
        }

        override fun onPermissionRequest(request: PermissionRequest) {
            request.deny()
            Toast.makeText(this@MainActivity, "为保护隐私，网站权限默认关闭", Toast.LENGTH_SHORT).show()
        }

        override fun onGeolocationPermissionsShowPrompt(
            origin: String?,
            callback: GeolocationPermissions.Callback
        ) {
            callback.invoke(origin, false, false)
        }

        override fun onShowFileChooser(
            webView: WebView,
            filePathCallback: ValueCallback<Array<Uri>>,
            fileChooserParams: WebChromeClient.FileChooserParams
        ): Boolean {
            this@MainActivity.filePathCallback?.onReceiveValue(null)
            this@MainActivity.filePathCallback = filePathCallback
            return try {
                startActivityForResult(fileChooserParams.createIntent(), FILE_CHOOSER_REQUEST)
                true
            } catch (_: Exception) {
                this@MainActivity.filePathCallback = null
                Toast.makeText(this@MainActivity, "没有可用的文件选择器", Toast.LENGTH_SHORT).show()
                false
            }
        }
    }

    private fun createDownloadListener() = DownloadListener { url, userAgent, contentDisposition, mimeType, _ ->
        try {
            val filename = URLUtil.guessFileName(url, contentDisposition, mimeType)
            val request = DownloadManager.Request(Uri.parse(url)).apply {
                setMimeType(mimeType)
                addRequestHeader("User-Agent", userAgent)
                CookieManager.getInstance().getCookie(url)?.let { addRequestHeader("Cookie", it) }
                setTitle(filename)
                setDescription("正在下载")
                setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, filename)
                setAllowedOverMetered(true)
                setAllowedOverRoaming(false)
            }
            val manager = getSystemService(DOWNLOAD_SERVICE) as DownloadManager
            manager.enqueue(request)
            Toast.makeText(this, "已开始下载：$filename", Toast.LENGTH_LONG).show()
        } catch (_: Exception) {
            Toast.makeText(this, "无法开始下载", Toast.LENGTH_SHORT).show()
        }
    }

    private fun openFromIntent(intent: Intent?) {
        val uri = intent?.data
        if (intent?.action == Intent.ACTION_VIEW && uri != null &&
            (uri.scheme == "http" || uri.scheme == "https")
        ) {
            webView.loadUrl(uri.toString())
        } else {
            loadHome()
        }
    }

    private fun navigate(rawInput: String) {
        val input = normalizeAddressPunctuation(rawInput).trim()
        if (input.isEmpty()) return
        val url = when {
            input.startsWith("http://", true) || input.startsWith("https://", true) -> input
            input.contains(' ') -> searchUrl(input)
            looksLikeHost(input) -> "$selectedScheme://${stripScheme(input)}"
            else -> searchUrl(input)
        }
        webView.loadUrl(url)
    }

    private fun searchUrl(query: String): String = searchEngine.searchUrl(query)

    private fun looksLikeHost(input: String): Boolean {
        if (input.equals("localhost", true)) return true
        val candidate = input.substringBefore('/').substringBefore(':')
        return candidate.contains('.') && candidate.none(Char::isWhitespace)
    }

    private fun loadHome() {
        adBlocker.resetCount()
        selectedScheme = "http"
        updateSchemeButton()
        val renderedHome = homeHtml
            .replace("{{SEARCH_ACTION}}", searchEngine.searchAction)
            .replace("{{QUERY_PARAMETER}}", searchEngine.queryParameter)
            .replace("{{SEARCH_NAME}}", searchEngine.displayName)
        webView.loadDataWithBaseURL(HOME_URL, renderedHome, "text/html", "utf-8", HOME_URL)
        addressBar.setText("")
        updateShieldText()
        updateFavoriteButton()
    }

    private fun updateAddress(url: String?) {
        val uriScheme = url?.let { runCatching { Uri.parse(it).scheme }.getOrNull() }
        if (url != HOME_URL && (uriScheme == "http" || uriScheme == "https")) {
            selectedScheme = uriScheme
            updateSchemeButton()
        }
        if (addressBar.hasFocus()) return
        val displayUrl = when {
            url == HOME_URL || url == "about:blank" -> ""
            url?.startsWith("https://", true) == true -> url.substring(8)
            url?.startsWith("http://", true) == true -> url.substring(7)
            else -> url.orEmpty()
        }
        addressBar.setText(displayUrl)
    }

    private fun updateNavigationButtons() {
        backButton.isEnabled = webView.canGoBack()
        forwardButton.isEnabled = webView.canGoForward()
        backButton.alpha = if (backButton.isEnabled) 1f else 0.35f
        forwardButton.alpha = if (forwardButton.isEnabled) 1f else 0.35f
    }

    private fun updateShieldText() {
        shieldButton.text = if (adBlocker.enabled) {
            "⛨ ${adBlocker.blockedCount()}"
        } else {
            "⛨ ×"
        }
        shieldButton.contentDescription = if (adBlocker.enabled) {
            "广告拦截已开启，已拦截 ${adBlocker.blockedCount()} 项"
        } else {
            "广告拦截已关闭"
        }
    }

    private fun toggleAdBlocking() {
        adBlocker.enabled = !adBlocker.enabled
        updateShieldText()
        webView.reload()
    }

    private fun showSchemeMenu(anchor: View) {
        PopupMenu(this, anchor).apply {
            menu.add("HTTPS（推荐）").setOnMenuItemClickListener {
                switchScheme("https"); true
            }
            menu.add("HTTP（不加密）").setOnMenuItemClickListener {
                switchScheme("http"); true
            }
            show()
        }
    }

    private fun switchScheme(scheme: String) {
        selectedScheme = scheme
        updateSchemeButton()
        val address = addressBar.text.toString().trim()
        if (looksLikeHost(stripScheme(address))) {
            webView.loadUrl("$selectedScheme://${stripScheme(address)}")
            addressBar.clearFocus()
            hideKeyboard()
        }
    }

    private fun updateSchemeButton() {
        if (!::schemeButton.isInitialized) return
        val secure = selectedScheme == "https"
        val label = getString(if (secure) R.string.scheme_https else R.string.scheme_http)
        schemeButton.text = "$label ▾"
        schemeButton.contentDescription = "当前为 $label，点按切换协议"
        schemeButton.setTextColor(Color.rgb(35, 43, 56))
        schemeButton.background = null
    }

    private fun stripScheme(value: String): String = value
        .removePrefix("https://")
        .removePrefix("http://")

    private fun normalizeAddressPunctuation(value: String): String = value
        .replace('：', ':')
        .replace('。', '.')
        .replace('．', '.')

    private fun showMenu(anchor: View) {
        val items = listOf(
            MoreMenuItem("收藏夹", R.drawable.ic_bookmark_menu) {
                openLibrary(LibraryActivity.MODE_BOOKMARKS)
            },
            MoreMenuItem("浏览历史", R.drawable.ic_history_menu) {
                openLibrary(LibraryActivity.MODE_HISTORY)
            },
            MoreMenuItem("扫码", R.drawable.ic_scan_menu, ::startScanner),
            MoreMenuItem("设备互传", R.drawable.ic_devices_transfer, ::openTransfer),
            MoreMenuItem("搜索引擎：${searchEngine.displayName}") { showSearchEngineDialog() },
            MoreMenuItem("分享当前页面", action = ::shareCurrentPage),
            MoreMenuItem("复制链接", action = ::copyCurrentUrl),
            MoreMenuItem(if (desktopMode) "切换到手机版" else "切换到桌面版", action = ::toggleDesktopMode),
            MoreMenuItem("在其他浏览器中打开", action = ::openInOtherApp),
            MoreMenuItem("清除浏览数据", action = ::confirmClearBrowsingData),
            MoreMenuItem("设为默认浏览器", action = ::openDefaultBrowserSettings)
        )
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(6), dp(6), dp(6), dp(6))
            background = roundedBackground(Color.WHITE, dp(14).toFloat())
        }
        lateinit var popup: PopupWindow
        items.forEach { item ->
            content.addView(LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                isClickable = true
                isFocusable = true
                setPadding(dp(14), 0, dp(14), 0)
                setBackgroundResource(android.R.drawable.list_selector_background)
                addView(ImageView(this@MainActivity).apply {
                    if (item.iconRes != null) {
                        setImageResource(item.iconRes)
                        contentDescription = "${item.title}图标"
                    } else {
                        visibility = View.INVISIBLE
                    }
                }, LinearLayout.LayoutParams(dp(24), dp(24)).apply {
                    marginEnd = dp(14)
                })
                addView(TextView(this@MainActivity).apply {
                    text = item.title
                    textSize = 16f
                    setTextColor(Color.rgb(24, 33, 47))
                    gravity = Gravity.CENTER_VERTICAL
                    maxLines = 1
                }, LinearLayout.LayoutParams(0, -1, 1f))
                setOnClickListener {
                    popup.dismiss()
                    item.action()
                }
            }, LinearLayout.LayoutParams(-1, dp(52)))
        }
        val popupWidth = dp(292)
        content.measure(
            View.MeasureSpec.makeMeasureSpec(popupWidth, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        val popupHeight = content.measuredHeight
        popup = PopupWindow(content, popupWidth, popupHeight, true).apply {
            elevation = dp(10).toFloat()
            isOutsideTouchable = true
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        }
        val anchorLocation = IntArray(2)
        anchor.getLocationOnScreen(anchorLocation)
        val popupTop = (anchorLocation[1] - popupHeight).coerceAtLeast(dp(8))
        popup.showAtLocation(anchor.rootView, Gravity.TOP or Gravity.END, dp(6), popupTop)
    }

    private fun toggleBookmark() {
        val url = webView.url
        if (url.isNullOrBlank() || url == HOME_URL || url == "about:blank") {
            Toast.makeText(this, "主页无需收藏", Toast.LENGTH_SHORT).show()
            return
        }
        if (database.isBookmarked(url)) {
            database.removeBookmark(url)
            Toast.makeText(this, "已取消收藏", Toast.LENGTH_SHORT).show()
        } else {
            database.addBookmark(webView.title.orEmpty(), url)
            Toast.makeText(this, "已添加到收藏夹", Toast.LENGTH_SHORT).show()
        }
        updateFavoriteButton()
    }

    private fun updateFavoriteButton() {
        if (!::favoriteButton.isInitialized) return
        val bookmarked = database.isBookmarked(if (::webView.isInitialized) webView.url else null)
        favoriteButton.text = if (bookmarked) "★" else "☆"
        favoriteButton.contentDescription = if (bookmarked) "已收藏，点按取消收藏" else "收藏当前页面"
    }

    private fun openLibrary(mode: String) {
        startActivityForResult(
            Intent(this, LibraryActivity::class.java).putExtra(LibraryActivity.EXTRA_MODE, mode),
            LIBRARY_REQUEST
        )
    }

    private fun openTransfer() {
        startActivity(Intent(this, TransferActivity::class.java))
    }

    private fun startScanner() {
        IntentIntegrator(this)
            .setDesiredBarcodeFormats(IntentIntegrator.ALL_CODE_TYPES)
            .setPrompt("将二维码或条形码放入取景框")
            .setBeepEnabled(false)
            .setBarcodeImageEnabled(false)
            .setOrientationLocked(true)
            .initiateScan()
    }

    private fun handleScanResult(rawValue: String) {
        val value = rawValue.trim()
        if (value.startsWith("https://", true) || value.startsWith("http://", true) ||
            looksLikeHost(value)
        ) {
            navigate(value)
            return
        }
        AlertDialog.Builder(this)
            .setTitle("扫码结果")
            .setMessage(value)
            .setNegativeButton("关闭", null)
            .setNeutralButton("复制") { _, _ -> copyText("扫码结果", value) }
            .setPositiveButton("搜索") { _, _ -> webView.loadUrl(searchUrl(value)) }
            .show()
    }

    private fun showSearchEngineDialog() {
        val engines = SearchEngine.entries.toTypedArray()
        val selected = engines.indexOf(searchEngine)
        AlertDialog.Builder(this)
            .setTitle("选择搜索引擎")
            .setSingleChoiceItems(engines.map { it.displayName }.toTypedArray(), selected) { dialog, which ->
                searchEngine = engines[which]
                getSharedPreferences(PREFERENCES, MODE_PRIVATE)
                    .edit()
                    .putString(KEY_SEARCH_ENGINE, searchEngine.id)
                    .apply()
                dialog.dismiss()
                if (webView.url == HOME_URL) loadHome()
                Toast.makeText(this, "已切换到 ${searchEngine.displayName}", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun toggleDesktopMode() {
        desktopMode = !desktopMode
        webView.settings.userAgentString = if (desktopMode) {
            defaultUserAgent
                .replace("; wv", "")
                .replace(Regex("Android[^;)]*;?\\s*", RegexOption.IGNORE_CASE), "")
                .replace("Mobile", "")
        } else {
            defaultUserAgent
        }
        webView.settings.useWideViewPort = true
        webView.reload()
    }

    private fun shareCurrentPage() {
        val url = webView.url ?: return
        startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, url)
        }, "分享网页"))
    }

    private fun copyCurrentUrl() {
        val url = webView.url ?: return
        copyText("网页链接", url)
    }

    private fun copyText(label: String, text: String) {
        val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText(label, text))
        Toast.makeText(this, "已复制", Toast.LENGTH_SHORT).show()
    }

    private fun openInOtherApp() {
        val uri = webView.url?.let(Uri::parse) ?: return
        try {
            startActivity(Intent.createChooser(Intent(Intent.ACTION_VIEW, uri), "使用其他应用打开"))
        } catch (_: Exception) {
            Toast.makeText(this, "没有其他应用可以打开此链接", Toast.LENGTH_SHORT).show()
        }
    }

    private fun confirmClearBrowsingData() {
        AlertDialog.Builder(this)
            .setTitle("清除浏览数据")
            .setMessage("将清除 Cookie、缓存、表单数据与本地网页存储。")
            .setNegativeButton("取消", null)
            .setPositiveButton("清除") { _, _ -> clearBrowsingData() }
            .show()
    }

    private fun clearBrowsingData() {
        CookieManager.getInstance().removeAllCookies(null)
        CookieManager.getInstance().flush()
        WebStorage.getInstance().deleteAllData()
        webView.clearCache(true)
        webView.clearFormData()
        webView.clearHistory()
        database.clearHistory()
        Toast.makeText(this, "浏览数据已清除", Toast.LENGTH_SHORT).show()
        loadHome()
    }

    private fun openDefaultBrowserSettings() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val roleManager = getSystemService(RoleManager::class.java)
                if (roleManager.isRoleAvailable(RoleManager.ROLE_BROWSER) &&
                    !roleManager.isRoleHeld(RoleManager.ROLE_BROWSER)
                ) {
                    startActivity(roleManager.createRequestRoleIntent(RoleManager.ROLE_BROWSER))
                } else {
                    startActivity(Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS))
                }
            } else {
                startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
            }
        } catch (_: Exception) {
            Toast.makeText(this, "请在系统设置中选择默认浏览器", Toast.LENGTH_LONG).show()
        }
    }

    private fun openExternalUri(uri: Uri): Boolean {
        return try {
            val intent = if (uri.scheme == "intent") {
                Intent.parseUri(uri.toString(), Intent.URI_INTENT_SCHEME).apply {
                    component = null
                    selector = null
                }
            } else {
                Intent(Intent.ACTION_VIEW, uri)
            }
            startActivity(intent)
            true
        } catch (_: Exception) {
            Toast.makeText(this, "没有应用可以处理此链接", Toast.LENGTH_SHORT).show()
            true
        }
    }

    private fun goBack() {
        if (webView.canGoBack()) webView.goBack() else finish()
    }

    @Deprecated("Kept for Android 14 compatibility")
    override fun onBackPressed() = goBack()

    @Deprecated("Uses the platform file chooser without an AndroidX dependency")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val scanResult = IntentIntegrator.parseActivityResult(requestCode, resultCode, data)
        if (scanResult != null) {
            if (scanResult.contents != null) {
                handleScanResult(scanResult.contents)
            } else {
                Toast.makeText(this, "已取消扫码", Toast.LENGTH_SHORT).show()
            }
            return
        }
        if (requestCode == LIBRARY_REQUEST) {
            if (resultCode == RESULT_OK) {
                data?.getStringExtra(LibraryActivity.EXTRA_URL)?.let(webView::loadUrl)
            }
            return
        }
        if (requestCode != FILE_CHOOSER_REQUEST) return
        val result = if (resultCode == RESULT_OK) {
            WebChromeClient.FileChooserParams.parseResult(resultCode, data)
        } else {
            null
        }
        filePathCallback?.onReceiveValue(result)
        filePathCallback = null
    }

    override fun onSaveInstanceState(outState: Bundle) {
        webView.saveState(outState)
        super.onSaveInstanceState(outState)
    }

    override fun onPause() {
        webView.onPause()
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        webView.onResume()
    }

    override fun onDestroy() {
        filePathCallback?.onReceiveValue(null)
        filePathCallback = null
        (webView.parent as? ViewGroup)?.removeView(webView)
        webView.stopLoading()
        webView.webChromeClient = null
        webView.webViewClient = WebViewClient()
        webView.destroy()
        database.close()
        super.onDestroy()
    }

    private fun toolbarButton(label: String, description: String, action: (View) -> Unit): Button =
        Button(this).apply {
            text = label
            contentDescription = description
            textSize = 24f
            isAllCaps = false
            minWidth = 0
            minimumWidth = 0
            minHeight = 0
            minimumHeight = 0
            setPadding(0, 0, 0, dp(2))
            setTextColor(Color.rgb(35, 43, 56))
            background = null
            setOnClickListener(action)
            layoutParams = LinearLayout.LayoutParams(dp(42), dp(42))
        }

    private fun roundedBackground(color: Int, radius: Float) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = radius
        setStroke(dp(1), Color.rgb(224, 229, 238))
    }

    private fun hideKeyboard() {
        val manager = getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
        manager.hideSoftInputFromWindow(addressBar.windowToken, 0)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private data class MoreMenuItem(
        val title: String,
        val iconRes: Int? = null,
        val action: () -> Unit
    )

    companion object {
        private const val HOME_URL = "https://home.zyd.invalid/"
        private const val INTERNAL_SCHEME = "lvluo"
        private const val FILE_CHOOSER_REQUEST = 4107
        private const val LIBRARY_REQUEST = 4108
        private const val PREFERENCES = "browser_settings"
        private const val KEY_SEARCH_ENGINE = "search_engine"
    }
}
