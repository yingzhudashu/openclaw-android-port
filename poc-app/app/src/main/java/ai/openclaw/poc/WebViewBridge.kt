package ai.openclaw.poc

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.util.Log
import android.webkit.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.*
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * WebViewBridge — 隐藏 WebView + HTTP API
 *
 * 提供 HTTP 接口让 Node.js gateway 控制 WebView：
 * - POST /browser/navigate — 打开 URL
 * - POST /browser/eval     — 执行 JavaScript
 * - GET  /browser/content   — 获取页面文本
 * - GET  /browser/screenshot — 获取页面截图 (base64)
 * - POST /browser/click     — 点击元素
 * - POST /browser/type      — 输入文字
 * - GET  /browser/status    — 浏览器状态
 *
 * 零第三方依赖，纯 Java ServerSocket 实现 HTTP 服务。
 * HTTP 服务端口: 18790 (gateway 在 18789)
 */
class WebViewBridge(private val context: Context) {

    companion object {
        private const val TAG = "WebViewBridge"
        const val PORT = 18790
        private const val DEFAULT_TIMEOUT_MS = 30000L
    }

    private var webView: WebView? = null
    private var serverSocket: ServerSocket? = null
    private var serverThread: Thread? = null
    @Volatile private var running = false
    private val mainHandler = Handler(Looper.getMainLooper())

    // State
    @Volatile var currentUrl: String = "about:blank"
    @Volatile var isLoading: Boolean = false
    @Volatile var lastError: String? = null
    @Volatile var pageTitle: String = ""

    // Activity log — ring buffer for recent browser events
    data class ActivityEntry(val time: Long, val action: String, val detail: String)
    private val activityLog = java.util.concurrent.ConcurrentLinkedDeque<ActivityEntry>()
    private val maxActivityEntries = 50

    private fun recordActivity(action: String, detail: String = "") {
        activityLog.addFirst(ActivityEntry(System.currentTimeMillis(), action, detail))
        while (activityLog.size > maxActivityEntries) activityLog.removeLast()
        // Notify listeners
        onActivity?.invoke(action, detail)
    }

    /** Callback for real-time activity updates */
    var onActivity: ((action: String, detail: String) -> Unit)? = null

    /** Get recent activity entries as JSON array */
    fun getActivityJson(): JSONArray {
        val arr = JSONArray()
        for (entry in activityLog) {
            arr.put(JSONObject().apply {
                put("time", entry.time)
                put("action", entry.action)
                put("detail", entry.detail)
            })
        }
        return arr
    }

    /** Get current request count (total operations served) */
    @Volatile var requestCount: Long = 0
        private set

    var onLog: ((String) -> Unit)? = null

    private fun log(msg: String) {
        Log.d(TAG, msg)
        onLog?.invoke("[Browser] $msg")
    }

    /**
     * 初始化 WebView（必须在主线程调用）
     */
    @SuppressLint("SetJavaScriptEnabled")
    fun init() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            val latch = CountDownLatch(1)
            mainHandler.post {
                initWebView()
                latch.countDown()
            }
            latch.await(10, TimeUnit.SECONDS)
        } else {
            initWebView()
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun initWebView() {
        webView = WebView(context).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.databaseEnabled = true
            settings.cacheMode = WebSettings.LOAD_DEFAULT
            settings.userAgentString = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.0.0 Mobile Safari/537.36"
            settings.loadWithOverviewMode = true
            settings.useWideViewPort = true
            settings.setSupportMultipleWindows(false)
            settings.blockNetworkImage = false
            @SuppressLint("NewApi")
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW

            webViewClient = object : WebViewClient() {
                override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                    isLoading = true
                    currentUrl = url ?: "about:blank"
                    recordActivity("page_start", url ?: "")
                    log("Loading: $url")
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    isLoading = false
                    currentUrl = url ?: "about:blank"
                    pageTitle = view?.title ?: ""
                    recordActivity("page_loaded", "${pageTitle} | ${url ?: ""}")
                    log("Loaded: $url ($pageTitle)")
                }

                override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                    if (request?.isForMainFrame == true) {
                        lastError = "Error: ${error?.description}"
                        isLoading = false
                        recordActivity("error", error?.description?.toString() ?: "unknown")
                        log("Error: ${error?.description}")
                    }
                }
            }

            webChromeClient = object : WebChromeClient() {
                override fun onConsoleMessage(msg: ConsoleMessage?): Boolean {
                    log("Console: ${msg?.message()}")
                    return true
                }
            }
        }
        log("WebView initialized")
    }

    /**
     * 启动 HTTP 服务
     */
    fun startServer() {
        if (running) return
        running = true
        serverThread = Thread {
            try {
                serverSocket = ServerSocket(PORT, 10, java.net.InetAddress.getByName("127.0.0.1"))
                log("HTTP server started on port $PORT")
                while (running) {
                    try {
                        val socket = serverSocket?.accept() ?: break
                        Thread { handleClient(socket) }.start()
                    } catch (e: Exception) {
                        if (running) log("Accept error: ${e.message}")
                    }
                }
            } catch (e: Exception) {
                log("Server start failed: ${e.message}")
            }
        }.also { it.isDaemon = true; it.start() }
    }

    /**
     * 停止服务
     */
    fun stop() {
        running = false
        try { serverSocket?.close() } catch (_: Exception) {}
        mainHandler.post {
            webView?.destroy()
            webView = null
        }
        log("WebViewBridge stopped")
    }

    // ─── HTTP Request Handler ────────────────────────────────────────────

    private fun handleClient(socket: Socket) {
        try {
            socket.soTimeout = 60000
            val input = BufferedReader(InputStreamReader(socket.getInputStream()))
            val output = socket.getOutputStream()

            // Parse request line
            val requestLine = input.readLine() ?: return
            val parts = requestLine.split(" ", limit = 3)
            if (parts.size < 2) return
            val method = parts[0].uppercase()
            val fullPath = parts[1]
            val uri = fullPath.split("?")[0]
            val queryString = if (fullPath.contains("?")) fullPath.substringAfter("?") else ""

            // Parse headers
            val headers = mutableMapOf<String, String>()
            var line: String?
            while (input.readLine().also { line = it } != null && line!!.isNotEmpty()) {
                val colonIdx = line!!.indexOf(':')
                if (colonIdx > 0) {
                    headers[line!!.substring(0, colonIdx).trim().lowercase()] = line!!.substring(colonIdx + 1).trim()
                }
            }

            // Handle CORS preflight
            if (method == "OPTIONS") {
                sendResponse(output, 204, "")
                socket.close()
                return
            }

            // Parse body for POST
            var body = ""
            if (method == "POST") {
                val contentLength = headers["content-length"]?.toIntOrNull() ?: 0
                if (contentLength > 0) {
                    val buf = CharArray(contentLength)
                    var read = 0
                    while (read < contentLength) {
                        val n = input.read(buf, read, contentLength - read)
                        if (n == -1) break
                        read += n
                    }
                    body = String(buf, 0, read)
                }
            }

            // Parse query params
            val params = parseQuery(queryString)

            // Route
            val jsonBody = if (body.isNotEmpty()) {
                try { JSONObject(body) } catch (_: Exception) { JSONObject() }
            } else JSONObject()

            val result: JSONObject = when {
                uri == "/browser/status" -> {
                    JSONObject().apply {
                        put("status", "ok")
                        put("url", currentUrl)
                        put("title", pageTitle)
                        put("loading", isLoading)
                        put("error", lastError ?: JSONObject.NULL)
                        put("requests", requestCount)
                    }
                }

                uri == "/browser/activity" -> {
                    val limit = params["limit"]?.toIntOrNull() ?: 20
                    val arr = JSONArray()
                    var count = 0
                    for (entry in activityLog) {
                        if (count >= limit) break
                        arr.put(JSONObject().apply {
                            put("time", entry.time)
                            put("action", entry.action)
                            put("detail", entry.detail)
                        })
                        count++
                    }
                    JSONObject().apply {
                        put("activities", arr)
                        put("url", currentUrl)
                        put("title", pageTitle)
                        put("loading", isLoading)
                        put("requests", requestCount)
                    }
                }

                uri == "/browser/navigate" && method == "POST" -> {
                    val url = jsonBody.optString("url", "")
                    if (url.isEmpty()) {
                        sendResponse(output, 400, JSONObject().put("error", "URL is required").toString())
                        socket.close()
                        return
                    }
                    requestCount++
                    recordActivity("navigate", url)
                    val timeout = jsonBody.optLong("timeout_ms", DEFAULT_TIMEOUT_MS)
                    navigate(url, timeout)
                }

                uri == "/browser/eval" && method == "POST" -> {
                    val js = jsonBody.optString("script", jsonBody.optString("javascript", ""))
                    if (js.isEmpty()) {
                        sendResponse(output, 400, JSONObject().put("error", "Script is required").toString())
                        socket.close()
                        return
                    }
                    requestCount++
                    recordActivity("eval", js.take(60))
                    val result = evaluateJs(js)
                    JSONObject().apply {
                        put("result", result)
                        put("url", currentUrl)
                    }
                }

                uri == "/browser/content" -> {
                    val maxChars = params["max_chars"]?.toIntOrNull() ?: 50000
                    requestCount++
                    recordActivity("content", "max_chars=$maxChars")
                    val content = getPageContent(maxChars)
                    JSONObject().apply {
                        put("content", content)
                        put("url", currentUrl)
                        put("title", pageTitle)
                        put("chars", content.length)
                    }
                }

                uri == "/browser/html" -> {
                    val maxChars = params["max_chars"]?.toIntOrNull() ?: 100000
                    requestCount++
                    recordActivity("html", "max_chars=$maxChars")
                    val html = getPageHtml(maxChars)
                    JSONObject().apply {
                        put("html", html)
                        put("url", currentUrl)
                        put("chars", html.length)
                    }
                }

                uri == "/browser/screenshot" -> {
                    requestCount++
                    recordActivity("screenshot", currentUrl)
                    val base64 = takeScreenshot()
                    JSONObject().apply {
                        put("image_base64", if (base64.isEmpty()) JSONObject.NULL else base64)
                        put("format", "jpeg")
                        put("url", currentUrl)
                    }
                }

                uri == "/browser/click" && method == "POST" -> {
                    val selector = jsonBody.optString("selector", "")
                    if (selector.isEmpty()) {
                        sendResponse(output, 400, JSONObject().put("error", "Selector is required").toString())
                        socket.close()
                        return
                    }
                    requestCount++
                    recordActivity("click", selector)
                    val result = clickElement(selector)
                    JSONObject().apply {
                        put("result", result)
                        put("url", currentUrl)
                    }
                }

                uri == "/browser/type" && method == "POST" -> {
                    val selector = jsonBody.optString("selector", "")
                    val text = jsonBody.optString("text", "")
                    if (selector.isEmpty()) {
                        sendResponse(output, 400, JSONObject().put("error", "Selector is required").toString())
                        socket.close()
                        return
                    }
                    requestCount++
                    recordActivity("type", "$selector: ${text.take(30)}")
                    val result = typeText(selector, text)
                    JSONObject().apply {
                        put("result", result)
                        put("url", currentUrl)
                    }
                }

                uri == "/browser/url" -> {
                    JSONObject().apply {
                        put("url", currentUrl)
                        put("title", pageTitle)
                        put("loading", isLoading)
                    }
                }

                else -> {
                    sendResponse(output, 404, JSONObject().put("error", "Not found: $method $uri").toString())
                    socket.close()
                    return
                }
            }

            sendResponse(output, 200, result.toString())
            socket.close()

        } catch (e: Exception) {
            log("Handle error: ${e.message}")
            try { socket.close() } catch (_: Exception) {}
        }
    }

    private fun sendResponse(output: OutputStream, statusCode: Int, body: String) {
        val statusText = when (statusCode) {
            200 -> "OK"
            204 -> "No Content"
            400 -> "Bad Request"
            404 -> "Not Found"
            500 -> "Internal Server Error"
            else -> "Unknown"
        }
        val headers = buildString {
            append("HTTP/1.1 $statusCode $statusText\r\n")
            append("Content-Type: application/json; charset=utf-8\r\n")
            append("Content-Length: ${body.toByteArray(Charsets.UTF_8).size}\r\n")
            append("Access-Control-Allow-Origin: *\r\n")
            append("Access-Control-Allow-Methods: GET, POST, OPTIONS\r\n")
            append("Access-Control-Allow-Headers: Content-Type\r\n")
            append("Connection: close\r\n")
            append("\r\n")
        }
        output.write(headers.toByteArray(Charsets.UTF_8))
        output.write(body.toByteArray(Charsets.UTF_8))
        output.flush()
    }

    private fun parseQuery(qs: String): Map<String, String> {
        if (qs.isEmpty()) return emptyMap()
        return qs.split("&").mapNotNull {
            val parts = it.split("=", limit = 2)
            if (parts.size == 2) parts[0] to java.net.URLDecoder.decode(parts[1], "UTF-8")
            else null
        }.toMap()
    }

    // ─── WebView Operations (thread-safe) ────────────────────────────────

    fun navigate(url: String, timeoutMs: Long = DEFAULT_TIMEOUT_MS): JSONObject {
        val result = AtomicReference<JSONObject>()
        val latch = CountDownLatch(1)

        mainHandler.post {
            lastError = null
            isLoading = true
            webView?.loadUrl(url)

            val checkHandler = Handler(Looper.getMainLooper())
            val startTime = System.currentTimeMillis()
            val checker = object : Runnable {
                override fun run() {
                    if (!isLoading || System.currentTimeMillis() - startTime > timeoutMs) {
                        result.set(JSONObject().apply {
                            put("url", currentUrl)
                            put("title", pageTitle)
                            put("error", lastError ?: JSONObject.NULL)
                            put("loaded", !isLoading)
                            put("duration_ms", System.currentTimeMillis() - startTime)
                        })
                        latch.countDown()
                    } else {
                        checkHandler.postDelayed(this, 200)
                    }
                }
            }
            checkHandler.postDelayed(checker, 500)
        }

        latch.await(timeoutMs + 2000, TimeUnit.MILLISECONDS)
        return result.get() ?: JSONObject().put("error", "Timeout")
    }

    fun evaluateJs(script: String, timeoutMs: Long = 10000): String {
        val result = AtomicReference<String>("")
        val latch = CountDownLatch(1)

        mainHandler.post {
            webView?.evaluateJavascript(script) { value ->
                result.set(value ?: "null")
                latch.countDown()
            }
        }

        latch.await(timeoutMs, TimeUnit.MILLISECONDS)
        return result.get() ?: "null"
    }

    fun getPageContent(maxChars: Int = 50000): String {
        val js = "(function(){return document.body?document.body.innerText.substring(0,$maxChars):''})()"
        val raw = evaluateJs(js)
        return unquoteJs(raw)
    }

    fun getPageHtml(maxChars: Int = 100000): String {
        val js = "(function(){return document.documentElement.outerHTML.substring(0,$maxChars)})()"
        val raw = evaluateJs(js)
        return unquoteJs(raw)
    }

    fun takeScreenshot(): String {
        val result = AtomicReference<String>("")
        val latch = CountDownLatch(1)

        mainHandler.post {
            try {
                val wv = webView ?: run { result.set(""); latch.countDown(); return@post }
                wv.measure(
                    android.view.View.MeasureSpec.makeMeasureSpec(1080, android.view.View.MeasureSpec.EXACTLY),
                    android.view.View.MeasureSpec.makeMeasureSpec(1920, android.view.View.MeasureSpec.EXACTLY)
                )
                wv.layout(0, 0, 1080, 1920)
                val bitmap = Bitmap.createBitmap(1080, 1920, Bitmap.Config.ARGB_8888)
                val canvas = android.graphics.Canvas(bitmap)
                wv.draw(canvas)
                val baos = ByteArrayOutputStream()
                bitmap.compress(Bitmap.CompressFormat.JPEG, 60, baos)
                bitmap.recycle()
                result.set(Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP))
            } catch (e: Exception) {
                log("Screenshot failed: ${e.message}")
                result.set("")
            }
            latch.countDown()
        }

        latch.await(10, TimeUnit.SECONDS)
        return result.get() ?: ""
    }

    fun clickElement(selector: String): String {
        val escaped = selector.replace("'", "\\'")
        val js = "(function(){var el=document.querySelector('$escaped');if(!el)return 'Not found: $escaped';el.click();return 'Clicked: '+(el.tagName||'')+' '+(el.textContent||'').substring(0,50)})()"
        return unquoteJs(evaluateJs(js))
    }

    fun typeText(selector: String, text: String): String {
        val escapedSel = selector.replace("'", "\\'")
        val escapedText = text.replace("'", "\\'").replace("\n", "\\n")
        val js = "(function(){var el=document.querySelector('$escapedSel');if(!el)return 'Not found';el.value='$escapedText';el.dispatchEvent(new Event('input',{bubbles:true}));return 'Typed: '+el.value.substring(0,50)})()"
        return unquoteJs(evaluateJs(js))
    }

    private fun unquoteJs(raw: String): String {
        return try {
            if (raw.startsWith("\"") && raw.endsWith("\"")) {
                JSONArray("[$raw]").getString(0)
            } else raw
        } catch (_: Exception) { raw }
    }
}
