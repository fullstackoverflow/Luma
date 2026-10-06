package dev.luma.monitor

import android.content.Intent
import android.net.Uri
import android.net.http.SslError
import android.os.Bundle
import android.os.Message
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.SslErrorHandler
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.card.MaterialCardView
import com.google.android.material.dialog.MaterialAlertDialogBuilder

class MainActivity : AppCompatActivity() {
    private lateinit var store: LumaStore
    private var web: WebView? = null
    private var activeEndpoint: Endpoint? = null
    private var settingsVisible = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = LumaStore(this)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                val browser = web
                when {
                    browser?.canGoBack() == true -> browser.goBack()
                    !settingsVisible -> showSettings()
                    else -> { isEnabled = false; onBackPressedDispatcher.onBackPressed() }
                }
            }
        })
        if (savedInstanceState?.getBoolean("web_visible") == true) showWeb() else handleLaunch()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleLaunch()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("web_visible", !settingsVisible)
        super.onSaveInstanceState(outState)
    }

    private fun handleLaunch() {
        val instance = store.instance(intent.getStringExtra("instance_id"))
        if (!intent.getBooleanExtra("settings", false) && instance != null) {
            disposeWeb()
            store.selectInstance(instance.id)
            showWeb()
        } else showSettings()
    }

    private fun syncSession() {
        val endpoint = activeEndpoint ?: return
        val cookie = CookieManager.getInstance().getCookie(endpoint.api("api/rpc2")).orEmpty()
        // Only the server session is needed by the native API client, never unrelated cookies.
        val session = cookie.split(';').map { it.trim() }.filter { it.startsWith("session_token=") }.joinToString("; ")
        store.saveCookie(endpoint, session)
        CookieManager.getInstance().flush()
    }

    override fun onStop() { syncSession(); super.onStop() }

    override fun onDestroy() { disposeWeb(); super.onDestroy() }

    private fun disposeWeb() {
        syncSession()
        web?.let { browser ->
            browser.stopLoading()
            (browser.parent as? ViewGroup)?.removeView(browser)
            browser.destroy()
        }
        web = null
        activeEndpoint = null
    }

    private fun showSettings() {
        disposeWeb()
        settingsVisible = true
        val root = column()
        val content = column(24)
        val scroll = ScrollView(this).apply { isFillViewport = true; addView(content) }
        root.add(scroll)
        content.add(label("Luma", 36f, bold = true), 14)
        content.add(label("你的看板，你的桌面。", 17f, muted = true), 4)

        content.add(button("新增配置") { showAddInstance() }, 24)
        if (store.instances.isEmpty()) {
            content.add(label("还没有实例，添加一个 Komari 服务器开始使用。", 14f, muted = true), 20)
        } else {
            store.instances.forEach { instance ->
                val details = column(20).apply {
                    add(label(instance.name, 20f, bold = true))
                    add(label(instance.endpoint.baseUrl, 14f, muted = true), 6)
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
                }
                val card = MaterialCardView(this).apply {
                    radius = dp(20).toFloat()
                    cardElevation = 0f
                    strokeWidth = dp(1)
                    strokeColor = getColor(R.color.stroke)
                    setCardBackgroundColor(getColor(R.color.surface))
                    isClickable = true
                    isFocusable = true
                    contentDescription = "${instance.name}，${instance.endpoint.baseUrl}，打开看板"
                    addView(details)
                    setOnClickListener {
                        store.selectInstance(instance.id)
                        intent.removeExtra("node_id")
                        showWeb()
                    }
                }
                content.add(card, 16)
            }
        }
        installRoot(root)
    }

    private fun showAddInstance() {
        val form = column(24)
        val nameLayout = TextInputLayout(this).apply {
            hint = "实例名称"
            boxBackgroundMode = TextInputLayout.BOX_BACKGROUND_OUTLINE
        }
        val name = TextInputEditText(nameLayout.context).apply {
            setSingleLine()
            importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
        }
        nameLayout.addView(name)
        form.add(nameLayout)
        val inputLayout = TextInputLayout(this).apply { hint = "Komari 服务根地址"; boxBackgroundMode = TextInputLayout.BOX_BACKGROUND_OUTLINE }
        val address = TextInputEditText(inputLayout.context).apply {
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_URI
            setSingleLine()
            importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
        }
        inputLayout.addView(address)
        form.add(inputLayout, 16)
        form.add(label("例如 https://monitor.example.com", 12f, muted = true), 6)
        val dialog = MaterialAlertDialogBuilder(this).setTitle("新增配置").setView(form)
            .setNegativeButton("取消", null).setPositiveButton("保存", null).create()
        dialog.setOnShowListener {
            dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE).setOnClickListener {
                nameLayout.error = null
                inputLayout.error = null
                if (name.text.toString().isBlank()) {
                    nameLayout.error = "请填写实例名称"
                    return@setOnClickListener
                }
                val endpoint = try { Endpoint.parse(address.text.toString()) }
                catch (e: Exception) { inputLayout.error = e.message ?: "地址无效"; return@setOnClickListener }
                try { store.addInstance(name.text.toString(), endpoint) }
                catch (e: IllegalArgumentException) { inputLayout.error = e.message; return@setOnClickListener }
                dialog.dismiss()
                showSettings()
            }
        }
        dialog.show()
    }

    private fun showWeb() {
        val endpoint = store.endpoint ?: return showSettings()
        disposeWeb()
        settingsVisible = false
        activeEndpoint = endpoint
        val root = column()
        val toolbar = MaterialToolbar(this).apply {
            setNavigationIcon(R.drawable.ic_arrow_back)
            setNavigationIconTint(getColor(R.color.ink))
            navigationContentDescription = "返回"
            setBackgroundColor(getColor(R.color.surface))
            setNavigationOnClickListener { onBackPressedDispatcher.onBackPressed() }
        }
        root.add(toolbar, height = dp(56))
        val progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 100 }
        root.add(progress, height = dp(2))
        val error = label("", 13f, muted = true).apply { visibility = View.GONE; setPadding(dp(16), dp(8), dp(16), dp(8)); setOnClickListener { web?.reload() } }
        root.add(error)
        val browser = WebView(this)
        web = browser
        browser.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            setSupportMultipleWindows(true)
            javaScriptCanOpenWindowsAutomatically = false
            mediaPlaybackRequiresUserGesture = true
        }
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(browser, false)
        browser.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                if (!request.isForMainFrame) return false
                return navigate(request.url.toString(), endpoint)
            }
            override fun onPageStarted(view: WebView, url: String, favicon: android.graphics.Bitmap?) {
                error.visibility = View.GONE
            }
            override fun onPageFinished(view: WebView, url: String) {
                syncSession()
                WidgetScheduler.refreshAll(this@MainActivity)
            }
            override fun onReceivedError(view: WebView, request: WebResourceRequest, issue: WebResourceError) {
                if (request.isForMainFrame) { error.text = "网页加载失败，请检查网络和服务地址。点击重试。"; error.visibility = View.VISIBLE }
            }
            override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, response: WebResourceResponse) {
                if (request.isForMainFrame) { error.text = "服务器返回 HTTP ${response.statusCode}，点击重试。"; error.visibility = View.VISIBLE }
            }
            override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, sslError: SslError) {
                handler.cancel()
                error.text = "HTTPS 证书验证失败，请修复服务器证书。"; error.visibility = View.VISIBLE
            }
        }
        browser.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, value: Int) {
                progress.progress = value; progress.visibility = if (value == 100) View.GONE else View.VISIBLE
            }
            override fun onCreateWindow(view: WebView, dialog: Boolean, gesture: Boolean, message: Message): Boolean {
                if (!gesture) return false
                val popup = WebView(this@MainActivity)
                popup.webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                        val url = request.url.toString()
                        if (endpoint.permits(url)) browser.loadUrl(url) else openExternal(url)
                        popup.post { popup.destroy() }
                        return true
                    }
                }
                (message.obj as WebView.WebViewTransport).webView = popup
                message.sendToTarget()
                return true
            }
        }
        root.addView(browser, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        installRoot(root)
        val nodeId = intent.getStringExtra("node_id")
        intent.removeExtra("node_id")
        browser.loadUrl(endpoint.baseUrl + if (!nodeId.isNullOrBlank() && nodeId != "demo") "/instance/${Uri.encode(nodeId)}" else "/")
    }

    private fun navigate(url: String, endpoint: Endpoint): Boolean {
        if (endpoint.permits(url)) return false
        openExternal(url)
        return true
    }

    private fun openExternal(url: String) {
        val uri = Uri.parse(url)
        if (uri.scheme !in listOf("https", "http")) { toast("不支持此链接类型"); return }
        runCatching { startActivity(Intent(Intent.ACTION_VIEW, uri)) }.onFailure { toast("没有可打开链接的应用") }
    }

    private fun toast(text: String) { Toast.makeText(this, text, Toast.LENGTH_SHORT).show() }
}
