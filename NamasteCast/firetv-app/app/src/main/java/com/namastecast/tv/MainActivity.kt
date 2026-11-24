package com.namastecast.tv

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.Gravity
import android.view.InputDevice
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.PermissionRequest
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject
import java.security.SecureRandom

class MainActivity : Activity(), RemoteHost {

    companion object {
        const val BASE_PORT = 8765
        const val DEFAULT_HOME = "https://namastedev.com"
        private const val TAG = "NamasteCast"
        private const val MAX_HISTORY = 20
    }

    private val ui = Handler(Looper.getMainLooper())
    private val prefs by lazy { getSharedPreferences("namastecast", MODE_PRIVATE) }
    private val helperJs by lazy { assets.open("tv-helper.js").bufferedReader().use { it.readText() } }
    private val density by lazy { resources.displayMetrics.density }

    private lateinit var root: FrameLayout
    private lateinit var web: WebView
    private lateinit var fullscreenHolder: FrameLayout
    private lateinit var cursor: CursorView
    private lateinit var panel: LinearLayout
    private lateinit var panelUrl: TextView
    private lateinit var panelPin: TextView
    private lateinit var panelQr: ImageView
    private lateinit var toast: TextView

    private var customView: View? = null
    private var customCallback: WebChromeClient.CustomViewCallback? = null
    private var server: RemoteServer? = null
    private var port = BASE_PORT
    private var progress = 100
    private var pageState: JSONObject = JSONObject()
    private var lastBackAt = 0L
    private var desktopUa = ""
    private var defaultUa = ""

    @Volatile private var snapshot = "{}"

    private val pin: String by lazy {
        prefs.getString("pin", null) ?: String.format("%04d", SecureRandom().nextInt(10000))
            .also { prefs.edit().putString("pin", it).apply() }
    }

    // ---------------------------------------------------------------- lifecycle

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        buildViews()
        setupWebView()
        startServer()
        refreshPanel()

        val start = intent?.dataString?.let { CommandParser.normalizeUrl(it) }
            ?: prefs.getString("lastUrl", null)
            ?: home()
        web.loadUrl(start)
        showPanel(true)
        ui.postDelayed({ showPanel(false) }, 30_000)
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        intent?.dataString?.let { CommandParser.normalizeUrl(it) }?.let { web.loadUrl(it) }
    }

    override fun onResume() {
        super.onResume()
        web.onResume()
        ui.removeCallbacks(stateLoop)
        ui.post(stateLoop)
    }

    override fun onPause() {
        ui.removeCallbacks(stateLoop)
        web.onPause()
        super.onPause()
    }

    override fun onDestroy() {
        server?.stop()
        ui.removeCallbacksAndMessages(null)
        (web.parent as? ViewGroup)?.removeView(web)
        web.destroy()
        super.onDestroy()
    }

    // ---------------------------------------------------------------- views

    private fun dp(v: Int) = (v * density).toInt()

    private fun buildViews() {
        root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        web = WebView(this)
        fullscreenHolder = FrameLayout(this).apply { setBackgroundColor(Color.BLACK); visibility = View.GONE }
        cursor = CursorView(this)
        toast = TextView(this).apply {
            setTextColor(Color.WHITE); textSize = 18f; setPadding(dp(18), dp(10), dp(18), dp(10))
            background = rounded("#E61A1A1F", 24)
            visibility = View.GONE
        }
        panel = buildPanel()

        val match = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        root.addView(web, match)
        root.addView(fullscreenHolder, match)
        root.addView(panel, FrameLayout.LayoutParams(dp(420), ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.END or Gravity.CENTER_VERTICAL)
            .apply { marginEnd = dp(40) })
        root.addView(toast, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.CENTER_HORIZONTAL)
            .apply { topMargin = dp(28) })
        root.addView(cursor, match)
        setContentView(root)
    }

    private fun rounded(color: String, radiusDp: Int) = GradientDrawable().apply {
        setColor(Color.parseColor(color)); cornerRadius = radiusDp * density
    }

    private fun buildPanel(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        setPadding(dp(28), dp(24), dp(28), dp(24))
        background = rounded("#F214141A", 20)
        elevation = dp(12).toFloat()

        fun text(size: Float, color: String, bold: Boolean = false) = TextView(context).apply {
            textSize = size; setTextColor(Color.parseColor(color)); gravity = Gravity.CENTER
            if (bold) typeface = Typeface.DEFAULT_BOLD
        }

        addView(text(26f, "#FF7A1A", true).apply { text = "NamasteCast" })
        addView(text(15f, "#B8B8C0").apply { text = "Scan with your phone camera"; setPadding(0, dp(6), 0, dp(12)) })
        panelQr = ImageView(context)
        addView(panelQr, LinearLayout.LayoutParams(dp(220), dp(220)))
        addView(text(15f, "#B8B8C0").apply { text = "or open on phone / laptop (same Wi-Fi)"; setPadding(0, dp(14), 0, dp(4)) })
        panelUrl = text(22f, "#FFFFFF", true)
        addView(panelUrl)
        panelPin = text(20f, "#FFFFFF").apply { setPadding(0, dp(8), 0, dp(14)) }
        addView(panelPin)
        addView(text(13f, "#8A8A94").apply {
            text = "Fire remote:  arrows = pointer   OK = click\n" +
                "play/pause = video   back = back   menu = this card"
        })
    }

    private fun refreshPanel() {
        val ip = NetUtil.lanIp()
        if (ip == null) {
            panelUrl.text = "No Wi-Fi / network"
            panelPin.text = ""
            panelQr.setImageBitmap(null)
            return
        }
        val base = if (port == 80) "http://$ip" else "http://$ip:$port"
        panelUrl.text = base.removePrefix("http://")
        panelPin.text = "PIN  $pin"
        panelQr.setImageBitmap(NetUtil.qr("$base/?pin=$pin", dp(220)))
    }

    private fun showPanel(show: Boolean) {
        if (show) refreshPanel()
        panel.visibility = if (show) View.VISIBLE else View.GONE
    }

    private fun flash(msg: String) {
        toast.text = msg
        toast.visibility = View.VISIBLE
        toast.removeCallbacks(hideToast)
        toast.postDelayed(hideToast, 1800)
    }

    private val hideToast = Runnable { toast.visibility = View.GONE }

    // ---------------------------------------------------------------- web view

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        defaultUa = WebSettings.getDefaultUserAgent(this)
        val chrome = Regex("Chrome/([\\d.]+)").find(defaultUa)?.groupValues?.get(1) ?: "124.0.0.0"
        desktopUa = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/$chrome Safari/537.36"

        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            @Suppress("DEPRECATION")
            databaseEnabled = true
            mediaPlaybackRequiresUserGesture = false
            useWideViewPort = true
            loadWithOverviewMode = true
            setSupportZoom(true)
            builtInZoomControls = true
            displayZoomControls = false
            setSupportMultipleWindows(false)
            javaScriptCanOpenWindowsAutomatically = true
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            userAgentString = if (isDesktop()) desktopUa else defaultUa
        }
        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(web, true) // course players often live in a 3rd-party iframe
        }
        web.isFocusable = true
        web.isFocusableInTouchMode = true
        web.setBackgroundColor(Color.BLACK)

        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val scheme = request.url.scheme?.lowercase()
                return scheme != "http" && scheme != "https" // swallow intent://, market:// etc.
            }

            override fun onPageFinished(view: WebView, url: String) {
                view.evaluateJavascript(helperJs, null)
                if (url.startsWith("http")) {
                    prefs.edit().putString("lastUrl", url).apply()
                    remember(url, view.title)
                }
            }

            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                Log.w(TAG, "renderer gone, crashed=${if (Build.VERSION.SDK_INT >= 26) detail.didCrash() else "?"}")
                recreate()
                return true
            }
        }

        web.webChromeClient = object : WebChromeClient() {
            override fun onPermissionRequest(request: PermissionRequest) {
                // Only DRM (Widevine). Never camera/mic. This is what makes protected
                // course video show picture instead of the audio-only you get mirroring.
                val allowed = request.resources.filter { it == PermissionRequest.RESOURCE_PROTECTED_MEDIA_ID }
                runOnUiThread {
                    if (allowed.isNotEmpty()) request.grant(allowed.toTypedArray()) else request.deny()
                }
            }

            override fun onProgressChanged(view: WebView, newProgress: Int) { progress = newProgress }

            override fun onReceivedTitle(view: WebView, title: String?) {
                view.url?.let { remember(it, title) }
            }

            override fun onShowCustomView(view: View, callback: CustomViewCallback) {
                if (customView != null) { callback.onCustomViewHidden(); return }
                customView = view
                customCallback = callback
                fullscreenHolder.addView(view, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
                fullscreenHolder.visibility = View.VISIBLE
            }

            override fun onHideCustomView() = exitFullscreen()
        }
    }

    private fun exitFullscreen() {
        val v = customView ?: return
        fullscreenHolder.removeView(v)
        fullscreenHolder.visibility = View.GONE
        customView = null
        customCallback?.onCustomViewHidden()
        customCallback = null
    }

    private fun isDesktop() = prefs.getBoolean("desktop", true)
    private fun home() = prefs.getString("home", null) ?: DEFAULT_HOME

    private fun js(code: String, cb: ((String?) -> Unit)? = null) {
        web.evaluateJavascript("$helperJs\n;$code") { r -> cb?.invoke(r) }
    }

    private fun remember(url: String, title: String?) {
        val clean = url.substringBefore('#')
        val list = history()
        val out = JSONArray()
        out.put(JSONObject().put("url", clean).put("title", title?.takeIf { it.isNotBlank() && !it.startsWith("http") } ?: clean))
        for (i in 0 until list.length()) {
            val item = list.optJSONObject(i) ?: continue
            if (item.optString("url") != clean && out.length() < MAX_HISTORY) out.put(item)
        }
        prefs.edit().putString("history", out.toString()).apply()
    }

    private fun history(): JSONArray = try { JSONArray(prefs.getString("history", "[]")) } catch (e: Exception) { JSONArray() }

    // ---------------------------------------------------------------- state

    private val stateLoop = object : Runnable {
        override fun run() {
            js("window.__nc.state()") { r ->
                pageState = try { if (r == null || r == "null") JSONObject() else JSONObject(r) } catch (e: Exception) { JSONObject() }
                publishState()
            }
            ui.postDelayed(this, 1000)
        }
    }

    private fun publishState() {
        snapshot = JSONObject()
            .put("ok", true)
            .put("name", tvName())
            .put("url", web.url ?: "")
            .put("title", web.title ?: "")
            .put("progress", progress)
            .put("canGoBack", web.canGoBack())
            .put("desktop", isDesktop())
            .put("fullscreen", customView != null)
            .put("panel", panel.visibility == View.VISIBLE)
            .put("home", home())
            .put("video", pageState)
            .put("history", history())
            .toString()
    }

    override fun stateJson(): String = snapshot
    override fun tvName(): String = Build.MODEL ?: "Fire TV"

    // ---------------------------------------------------------------- server

    private fun startServer() {
        for (p in BASE_PORT..BASE_PORT + 5) {
            try {
                val s = RemoteServer(p, this, { pin }, { name -> try { assets.open(name) } catch (e: Exception) { null } })
                s.start(5000, true)
                server = s
                port = p
                Log.i(TAG, "remote server on $p")
                return
            } catch (e: Exception) {
                Log.w(TAG, "port $p busy: ${e.message}")
            }
        }
        flash("Could not start remote server")
    }

    override fun execute(cmd: Command) {
        ui.post {
            if (panel.visibility == View.VISIBLE && cmd != Command.TogglePanel) showPanel(false)
            handle(cmd)
        }
    }

    private fun handle(cmd: Command) {
        when (cmd) {
            is Command.Open -> { exitFullscreen(); web.loadUrl(cmd.url); flash("Opening on TV...") }
            is Command.Nav -> when (cmd.action) {
                NavAction.BACK -> goBack()
                NavAction.FORWARD -> if (web.canGoForward()) web.goForward()
                NavAction.RELOAD -> web.reload()
                NavAction.HOME -> { exitFullscreen(); web.loadUrl(home()) }
                NavAction.SETHOME -> web.url?.let { prefs.edit().putString("home", it).apply(); flash("Home page saved") }
            }
            is Command.Key -> sendKey(keyCode(cmd.key))
            is Command.Move -> cursor.moveBy(cmd.dx * 1.5f, cmd.dy * 1.5f)
            is Command.MoveTo -> cursor.moveTo(cmd.fx * cursor.width, cmd.fy * cursor.height)
            Command.Click -> tap(cursor.cx, cursor.cy)
            is Command.Scroll -> scrollAtCursor(cmd.dy)
            is Command.Text -> typeText(cmd.text)
            is Command.Video -> video(cmd.action, cmd.value)
            is Command.Mode -> setDesktop(cmd.desktop)
            is Command.Zoom -> if (cmd.delta > 0) web.zoomIn() else if (cmd.delta < 0) web.zoomOut()
            Command.TogglePanel -> showPanel(panel.visibility != View.VISIBLE)
        }
        publishState()
    }

    private fun setDesktop(desktop: Boolean) {
        prefs.edit().putBoolean("desktop", desktop).apply()
        web.settings.userAgentString = if (desktop) desktopUa else defaultUa
        web.reload()
        flash(if (desktop) "Desktop site" else "Mobile site")
    }

    // ---------------------------------------------------------------- input

    private fun target(): View = customView ?: web

    /** Real touch events, so they land inside cross-origin player iframes too. */
    private fun tap(x: Float, y: Float) {
        cursor.wake()
        val t = target()
        val down = SystemClock.uptimeMillis()
        val ev = MotionEvent.obtain(down, down, MotionEvent.ACTION_DOWN, x, y, 0).apply { source = InputDevice.SOURCE_TOUCHSCREEN }
        t.dispatchTouchEvent(ev); ev.recycle()
        ui.postDelayed({
            val up = MotionEvent.obtain(down, SystemClock.uptimeMillis(), MotionEvent.ACTION_UP, x, y, 0).apply { source = InputDevice.SOURCE_TOUCHSCREEN }
            t.dispatchTouchEvent(up); up.recycle()
        }, 60)
    }

    private fun sendKey(code: Int) {
        val t = target()
        if (t === web && !web.hasFocus()) web.requestFocus()
        t.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
        t.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
    }

    private fun keyCode(k: RemoteKey) = when (k) {
        RemoteKey.SPACE -> KeyEvent.KEYCODE_SPACE
        RemoteKey.ENTER -> KeyEvent.KEYCODE_ENTER
        RemoteKey.BACKSPACE -> KeyEvent.KEYCODE_DEL
        RemoteKey.TAB -> KeyEvent.KEYCODE_TAB
        RemoteKey.ESCAPE -> KeyEvent.KEYCODE_ESCAPE
        RemoteKey.LEFT -> KeyEvent.KEYCODE_DPAD_LEFT
        RemoteKey.RIGHT -> KeyEvent.KEYCODE_DPAD_RIGHT
        RemoteKey.UP -> KeyEvent.KEYCODE_DPAD_UP
        RemoteKey.DOWN -> KeyEvent.KEYCODE_DPAD_DOWN
        RemoteKey.PAGEUP -> KeyEvent.KEYCODE_PAGE_UP
        RemoteKey.PAGEDOWN -> KeyEvent.KEYCODE_PAGE_DOWN
        RemoteKey.F -> KeyEvent.KEYCODE_F
        RemoteKey.M -> KeyEvent.KEYCODE_M
        RemoteKey.K -> KeyEvent.KEYCODE_K
        RemoteKey.J -> KeyEvent.KEYCODE_J
        RemoteKey.L -> KeyEvent.KEYCODE_L
        RemoteKey.N -> KeyEvent.KEYCODE_N
        RemoteKey.P -> KeyEvent.KEYCODE_P
    }

    private fun typeText(text: String) {
        if (!web.hasFocus()) web.requestFocus()
        // 1) Write into the focused main-page input (React-safe: native setter + input event).
        // 2) If focus is inside an iframe, JS can't reach it: fall back to synthetic key presses.
        js("window.__nc.insertText(${JSONObject.quote(text)})") { r ->
            if (r == "true") return@js
            KeyCharacterMap.load(KeyCharacterMap.VIRTUAL_KEYBOARD).getEvents(text.toCharArray())
                ?.forEach { web.dispatchKeyEvent(it) }
                ?: flash("Couldn't type that here")
        }
    }

    private fun scrollAtCursor(dy: Int) {
        val fx = if (cursor.width > 0) cursor.cx / cursor.width else 0.5f
        val fy = if (cursor.height > 0) cursor.cy / cursor.height else 0.5f
        js("window.__nc.scroll($fx,$fy,$dy)")
    }

    private fun video(action: VideoAction, value: Double) {
        val name = action.name.lowercase()
        js("window.__nc.act('$name',$value)") { r ->
            val res = try { if (r == null || r == "null") JSONObject() else JSONObject(r) } catch (e: Exception) { JSONObject() }
            if (res.optBoolean("handled")) {
                when (action) {
                    VideoAction.FILL -> flash(if (res.optBoolean("filled")) "TV mode on" else "TV mode off")
                    VideoAction.RATE -> flash("Speed ${value}x")
                    else -> {}
                }
                return@js
            }
            // Player lives in a cross-origin iframe: act like a human would.
            val p = res.optJSONObject("player")
            when (action) {
                VideoAction.TOGGLE, VideoAction.PLAY, VideoAction.PAUSE ->
                    if (p != null) tap((p.optDouble("x") * web.width).toFloat(), (p.optDouble("y") * web.height).toFloat())
                    else sendKey(KeyEvent.KEYCODE_SPACE)
                VideoAction.SEEK -> {
                    val code = if (value < 0) KeyEvent.KEYCODE_DPAD_LEFT else KeyEvent.KEYCODE_DPAD_RIGHT
                    repeat(((kotlin.math.abs(value) / 10).toInt()).coerceIn(1, 6)) { sendKey(code) }
                }
                VideoAction.MUTE -> sendKey(KeyEvent.KEYCODE_M)
                VideoAction.FILL -> flash("No video player found on this page")
                VideoAction.RATE, VideoAction.SEEKTO -> flash("Use the player's own controls for this one")
            }
        }
    }

    private fun goBack() {
        when {
            panel.visibility == View.VISIBLE -> showPanel(false)
            customView != null -> exitFullscreen()
            web.canGoBack() -> web.goBack()
            SystemClock.uptimeMillis() - lastBackAt < 2000 -> finish()
            else -> { lastBackAt = SystemClock.uptimeMillis(); flash("Press back again to exit") }
        }
    }

    /** Physical Fire TV remote. Arrows drive the pointer, with acceleration on hold. */
    override fun dispatchKeyEvent(e: KeyEvent): Boolean {
        val down = e.action == KeyEvent.ACTION_DOWN
        val step = dp(12) * (1f + (e.repeatCount * 0.5f).coerceAtMost(6f))
        when (e.keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> { if (down) cursor.moveBy(-step, 0f); return true }
            KeyEvent.KEYCODE_DPAD_RIGHT -> { if (down) cursor.moveBy(step, 0f); return true }
            KeyEvent.KEYCODE_DPAD_UP -> {
                if (down) { if (cursor.cy <= dp(4)) scrollAtCursor(-dp(160)) else cursor.moveBy(0f, -step) }
                return true
            }
            KeyEvent.KEYCODE_DPAD_DOWN -> {
                if (down) { if (cursor.cy >= cursor.height - dp(6)) scrollAtCursor(dp(160)) else cursor.moveBy(0f, step) }
                return true
            }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                if (down && e.repeatCount == 0) { if (panel.visibility == View.VISIBLE) showPanel(false) else tap(cursor.cx, cursor.cy) }
                return true
            }
            KeyEvent.KEYCODE_BACK -> { if (!down) goBack(); return true }
            KeyEvent.KEYCODE_MENU -> { if (!down) showPanel(panel.visibility != View.VISIBLE); return true }
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PAUSE -> {
                if (down && e.repeatCount == 0) video(VideoAction.TOGGLE, 0.0); return true
            }
            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> { if (down) video(VideoAction.SEEK, 10.0); return true }
            KeyEvent.KEYCODE_MEDIA_REWIND -> { if (down) video(VideoAction.SEEK, -10.0); return true }
        }
        return super.dispatchKeyEvent(e)
    }
}
