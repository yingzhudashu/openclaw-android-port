package ai.openclaw.poc

import android.util.Log
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * v1.3.0 Comprehensive Test Suite
 * Tests all APIs from user perspective: functionality + stability + edge cases.
 * Runs on background thread, outputs a structured report.
 */
object ComprehensiveTest {
    private const val TAG = "ComprehensiveTest"
    private const val GW = "http://127.0.0.1:18789"
    private const val BR = "http://127.0.0.1:18790"
    private const val DC = "http://127.0.0.1:18791"

    private val results = mutableListOf<TestResult>()
    private var totalTests = 0
    private var passed = 0
    private var failed = 0

    data class TestResult(val name: String, val passed: Boolean, val detail: String)

    fun runAll(): String {
        results.clear()
        totalTests = 0
        passed = 0
        failed = 0

        addHeader("=== v1.3.0 Comprehensive Test Report ===")
        addHeader("Date: ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())}")
        addHeader("")

        // 1. Core Gateway
        addHeader("── 1. Core Gateway ──")
        testGet("$GW/health", "Health endpoint", expectCode = 200) { body ->
            val json = JSONObject(body)
            check(json.getString("status") == "ok", "status is 'ok'")
            check(json.has("version"), "has 'version' field")
            check(json.getString("version").contains("1.3.0"), "version contains 1.3.0")
        }
        testGet("$GW/api/status", "Status endpoint", expectCode = 200) { body ->
            val json = JSONObject(body)
            check(json.has("version"), "has 'version'")
            check(json.has("sessions"), "has 'sessions'")
            if (json.has("memory_mb")) {
                val mem = json.getJSONObject("memory_mb")
                if (mem.has("rss")) {
                    val rss = mem.getDouble("rss")
                    check(rss < 500, "rss < 500MB (current: ${String.format("%.1f", rss)}MB)")
                }
            }
        }
        testGet("$GW/api/models", "Models list", expectCode = 200) { body ->
            val json = JSONObject(body)
            check(json.has("models"), "has 'models'")
            val models = json.getJSONArray("models")
            check(models.length() > 0, "models count > 0 (found ${models.length()})")
        }
        testGet("$GW/api/config", "Config (redacted)", expectCode = 200) { body ->
            val json = JSONObject(body)
            check(json.has("config"), "has 'config'")
            // Verify API keys are redacted
            val config = json.getJSONObject("config")
            val providers = config.getJSONObject("providers")
            val keys = providers.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                val prov = providers.getJSONObject(key)
                if (prov.has("api_key")) {
                    val ak = prov.getString("api_key")
                    if (ak.isNotEmpty()) {
                        check(ak.contains("***"), "API key for $key is redacted")
                    }
                }
            }
        }

        // 2. Session Management
        addHeader("── 2. Session Management ──")
        testPost("$GW/api/sessions", "{\"title\":\"Test Session\"}", "Create session", expectCode = 201) { body ->
            val json = JSONObject(body)
            check(json.has("session"), "has 'session'")
            check(json.getJSONObject("session").has("id"), "session has 'id'")
        }
        testGet("$GW/api/sessions", "List sessions", expectCode = 200) { body ->
            val json = JSONObject(body)
            check(json.has("sessions"), "has 'sessions'")
        }

        // Delete session
        var sessionId = ""
        try {
            val resp = httpGet("$GW/api/sessions")
            val json = JSONObject(resp.second)
            val sessions = json.getJSONArray("sessions")
            if (sessions.length() > 0) {
                sessionId = sessions.getJSONObject(0).getString("id")
            }
        } catch (_: Exception) {}
        if (sessionId.isNotEmpty()) {
            testDelete("$GW/api/sessions/$sessionId", "Delete session", expectCode = 200) { body ->
                val json = JSONObject(body)
                check(json.getBoolean("deleted"), "deleted is true")
            }
        }

        // 3. Cron API
        addHeader("── 3. Cron API ──")
        // Clean up first
        try {
            testGet("$GW/api/cron/list", "Cron list (clean)", expectCode = 200)
        } catch (_: Exception) {}

        testPost("$GW/api/cron/add",
            """{"name":"test_weather","prompt":"查天气","interval_minutes":30}""",
            "Add cron task", expectCode = 201) { body ->
            val json = JSONObject(body)
            check(json.has("task"), "has 'task'")
            check(json.getJSONObject("task").has("id"), "task has 'id'")
            check(json.getJSONObject("task").getString("name") == "test_weather", "name matches")
            check(json.getJSONObject("task").getInt("interval_minutes") == 30, "interval matches")
        }
        testPost("$GW/api/cron/add",
            """{"name":"test_stock","prompt":"查股价","interval_minutes":60}""",
            "Add 2nd cron task", expectCode = 201)
        testPost("$GW/api/cron/add",
            """{"name":"test_news","prompt":"看新闻","interval_minutes":120}""",
            "Add 3rd cron task", expectCode = 201)

        testGet("$GW/api/cron/list", "Cron list (3 items)", expectCode = 200) { body ->
            val json = JSONObject(body)
            check(json.has("tasks"), "has 'tasks'")
            val tasks = json.getJSONArray("tasks")
            check(tasks.length() >= 3, "tasks count >= 3 (found ${tasks.length()})")
        }

        // Test invalid interval
        testPost("$GW/api/cron/add",
            """{"name":"bad","prompt":"test","interval_minutes":5}""",
            "Reject interval < 15", expectCode = 400)
        // Test missing name
        testPost("$GW/api/cron/add",
            """{"prompt":"no name","interval_minutes":30}""",
            "Reject missing name", expectCode = 400)

        // Test remove
        var removeId = ""
        try {
            val resp = httpGet("$GW/api/cron/list")
            val json = JSONObject(resp.second)
            val tasks = json.getJSONArray("tasks")
            if (tasks.length() > 0) {
                removeId = tasks.getJSONObject(0).getString("id")
            }
        } catch (_: Exception) {}
        if (removeId.isNotEmpty()) {
            testPost("$GW/api/cron/remove", """{"id":"$removeId"}""",
                "Remove cron task", expectCode = 200) { body ->
                val json = JSONObject(body)
                check(json.getBoolean("deleted"), "deleted is true")
            }
            testGet("$GW/api/cron/list", "Cron list after remove", expectCode = 200) { body ->
                val json = JSONObject(body)
                val tasks = json.getJSONArray("tasks")
                check(tasks.length() >= 2, "tasks count >= 2 after remove")
            }
        }

        // Test remove non-existent
        testPost("$GW/api/cron/remove", """{"id":"nonexistent_123"}""",
            "Remove non-existent", expectCode = 404)

        // Cleanup: remove all test cron tasks
        try {
            val resp = httpGet("$GW/api/cron/list")
            val json = JSONObject(resp.second)
            val tasks = json.getJSONArray("tasks")
            for (i in 0 until tasks.length()) {
                val id = tasks.getJSONObject(i).getString("id")
                httpPost("$GW/api/cron/remove", """{"id":"$id"}""")
            }
        } catch (_: Exception) {}

        // Test cron_run
        testPost("$GW/api/cron/run", """{"id":"nonexistent"}""",
            "Run non-existent cron", expectCode = 404)

        // 4. Browser Bridge
        addHeader("── 4. Browser Bridge ──")
        testGet("$BR/browser/url", "Browser current URL", expectCode = 200) { body ->
            val json = JSONObject(body)
            check(json.has("url"), "has 'url'")
        }
        testGet("$BR/browser/tabs", "Browser tabs", expectCode = 200) { body ->
            val json = JSONObject(body)
            check(json.has("tabs"), "has 'tabs'")
            check(json.has("count"), "has 'count'")
        }
        testPost("$BR/browser/navigate", """{"url":"https://example.com"}""",
            "Navigate to URL", expectCode = 200)
        testGet("$BR/browser/screenshot", "Screenshot", expectCode = 200) { body ->
            val json = JSONObject(body)
            check(json.has("image_base64") || json.has("error"), "has 'image_base64' or 'error'")
        }
        testPost("$BR/browser/snapshot", """{"max_elements":50}""",
            "Browser snapshot (accessibility)", expectCode = 200) { body ->
            val json = JSONObject(body)
            check(json.has("snapshot") || json.has("url"), "has 'snapshot' or 'url'")
        }

        // 5. Device Control API
        addHeader("── 5. Device Control API ──")
        testGet("$DC/device/location", "Get location", expectCode = 200) { body ->
            val json = JSONObject(body)
            check(json.has("status") || json.has("error"), "has status or error")
        }
        testGet("$DC/device/notifications?limit=5", "List notifications", expectCode = 200) { body ->
            val json = JSONObject(body)
            check(json.has("status"), "has 'status'")
            check(json.has("notifications"), "has 'notifications'")
        }
        testGet("$DC/device/location", "Get location (duplicate)", expectCode = 200)
        testGet("$DC/device/location", "Get location (3rd call)", expectCode = 200)

        // 6. 404 handling
        addHeader("── 6. Error Handling ──")
        testGet("$GW/api/nonexistent", "404 unknown route", expectCode = 404)
        testGet("$BR/nonexistent", "404 bridge route", expectCode = 404)

        // 7. Stability: Rapid requests
        addHeader("── 7. Stability Tests ──")
        stabilityTest()

        // 8. Memory usage
        addHeader("── 8. Resource Usage ──")
        testGet("$GW/api/status", "Memory check", expectCode = 200) { body ->
            val json = JSONObject(body)
            if (json.has("memory_mb")) {
                val mem = json.getJSONObject("memory_mb")
                if (mem.has("rss")) {
                    val rss = mem.getDouble("rss")
                    check(rss < 500, "rss < 500MB (current: ${String.format("%.1f", rss)}MB)")
                }
            }
        }

        // Summary
        addHeader("")
        addHeader("── Summary ──")
        addHeader("Total: $totalTests | Passed: $passed | Failed: $failed")
        val passRate = if (totalTests > 0) String.format("%.1f", passed * 100.0 / totalTests) else "0"
        addHeader("Pass rate: $passRate%")

        if (failed > 0) {
            addHeader("")
            addHeader("── Failed Tests ──")
            results.filter { !it.passed }.forEach {
                addHeader("❌ ${it.name}: ${it.detail}")
            }
        }

        addHeader("")
        addHeader("=== End Report ===")
        val report = results.joinToString("\n") { r ->
            val icon = if (r.passed) "✅" else "❌"
            "$icon ${r.name}" + if (r.detail.isNotEmpty()) " — ${r.detail}" else ""
        }

        Log.i(TAG, "Test complete: $passed/$totalTests passed")
        return report
    }

    private fun testGet(url: String, label: String, expectCode: Int = 200, validator: ((String) -> Unit)? = null) {
        totalTests++
        try {
            val (code, body) = httpGet(url)
            if (code == expectCode) {
                if (validator != null) {
                    try { validator(body) } catch (e: Exception) {
                        failed++
                        results.add(TestResult(label, false, "Validation failed: ${e.message}"))
                        return
                    }
                }
                passed++
                results.add(TestResult(label, true, "HTTP $code"))
            } else {
                failed++
                results.add(TestResult(label, false, "Expected $expectCode, got $code"))
            }
        } catch (e: Exception) {
            failed++
            results.add(TestResult(label, false, e::class.java.simpleName + " - " + (e.message ?: "unknown")))
        }
    }

    private fun testPost(url: String, body: String, label: String, expectCode: Int = 200, validator: ((String) -> Unit)? = null) {
        totalTests++
        try {
            val (code, respBody) = httpPost(url, body)
            if (code == expectCode) {
                if (validator != null) {
                    try { validator(respBody) } catch (e: Exception) {
                        failed++
                        results.add(TestResult(label, false, "Validation failed: ${e.message}"))
                        return
                    }
                }
                passed++
                results.add(TestResult(label, true, "HTTP $code"))
            } else {
                failed++
                results.add(TestResult(label, false, "Expected $expectCode, got $code — ${respBody.take(100)}"))
            }
        } catch (e: Exception) {
            failed++
            results.add(TestResult(label, false, e::class.java.simpleName + " - " + (e.message ?: "unknown")))
        }
    }

    private fun testDelete(url: String, label: String, expectCode: Int = 200, validator: ((String) -> Unit)? = null) {
        totalTests++
        try {
            val urlObj = URL(url)
            val conn = urlObj.openConnection() as HttpURLConnection
            conn.connectTimeout = 10000
            conn.readTimeout = 15000
            conn.requestMethod = "DELETE"
            val code = conn.responseCode
            val respBody = try {
                conn.inputStream.bufferedReader().use { it.readText() }
            } catch (e: Exception) {
                conn.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
            }
            conn.disconnect()
            if (code == expectCode) {
                if (validator != null) {
                    try { validator(respBody) } catch (e: Exception) {
                        failed++
                        results.add(TestResult(label, false, "Validation failed: ${e.message}"))
                        return
                    }
                }
                passed++
                results.add(TestResult(label, true, "HTTP $code"))
            } else {
                failed++
                results.add(TestResult(label, false, "Expected $expectCode, got $code — ${respBody.take(100)}"))
            }
        } catch (e: Exception) {
            failed++
            results.add(TestResult(label, false, e::class.java.simpleName + " - " + (e.message ?: "unknown")))
        }
    }

    private fun stabilityTest() {
        // Rapid concurrent health checks
        val latch = CountDownLatch(10)
        val successCount = AtomicInteger(0)
        val failCount = AtomicInteger(0)

        repeat(10) { i ->
            Thread {
                try {
                    val (code, _) = httpGet("$GW/health")
                    if (code == 200) successCount.incrementAndGet()
                    else failCount.incrementAndGet()
                } catch (_: Exception) {
                    failCount.incrementAndGet()
                } finally {
                    latch.countDown()
                }
            }.start()
        }

        latch.await(15, TimeUnit.SECONDS)
        totalTests++
        if (failCount.get() == 0) {
            passed++
            results.add(TestResult("Stability: 10x health", true, "All 10 requests succeeded"))
        } else {
            failed++
            results.add(TestResult("Stability: 10x health", false, "${successCount.get()}/10 succeeded, ${failCount.get()} failed"))
        }

        // Rapid cron list calls
        totalTests++
        var cronStable = true
        repeat(5) {
            try {
                val (code, _) = httpGet("$GW/api/cron/list")
                if (code != 200) cronStable = false
            } catch (_: Exception) {
                cronStable = false
            }
        }
        if (cronStable) {
            passed++
            results.add(TestResult("Stability: 5x cron list", true, "All 5 requests succeeded"))
        } else {
            failed++
            results.add(TestResult("Stability: 5x cron list", false, "Some requests failed"))
        }

        // Rapid device calls
        totalTests++
        var deviceStable = true
        repeat(5) {
            try {
                val (code, _) = httpGet("$DC/device/location")
                if (code != 200) deviceStable = false
            } catch (_: Exception) {
                deviceStable = false
            }
        }
        if (deviceStable) {
            passed++
            results.add(TestResult("Stability: 5x device location", true, "All 5 requests succeeded"))
        } else {
            failed++
            results.add(TestResult("Stability: 5x device location", false, "Some requests failed"))
        }
    }

    private fun check(condition: Boolean, detail: String) {
        if (!condition) throw Exception(detail)
    }

    private fun addHeader(line: String) {
        results.add(TestResult("[H]", true, line))
    }

    private fun httpGet(urlStr: String): Pair<Int, String> {
        val url = URL(urlStr)
        val conn = url.openConnection() as HttpURLConnection
        conn.connectTimeout = 10000
        conn.readTimeout = 15000
        conn.requestMethod = "GET"
        conn.instanceFollowRedirects = false
        val code = conn.responseCode
        val body = try {
            conn.inputStream.bufferedReader().use { it.readText() }
        } catch (e: Exception) {
            conn.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
        }
        conn.disconnect()
        return Pair(code, body)
    }

    private fun httpPost(urlStr: String, body: String): Pair<Int, String> {
        val url = URL(urlStr)
        val conn = url.openConnection() as HttpURLConnection
        conn.connectTimeout = 10000
        conn.readTimeout = 15000
        conn.requestMethod = "POST"
        conn.doOutput = true
        conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
        val bytes = body.toByteArray(StandardCharsets.UTF_8)
        conn.setRequestProperty("Content-Length", bytes.size.toString())
        conn.outputStream.use { it.write(bytes) }
        val code = conn.responseCode
        val respBody = try {
            conn.inputStream.bufferedReader().use { it.readText() }
        } catch (e: Exception) {
            conn.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
        }
        conn.disconnect()
        return Pair(code, respBody)
    }
}
