package dev.devilmusic.androidproof

import android.content.Context
import android.os.SystemClock
import androidx.javascriptengine.IsolateStartupParameters
import androidx.javascriptengine.JavaScriptIsolate
import androidx.javascriptengine.JavaScriptSandbox
import androidx.javascriptengine.Message
import androidx.javascriptengine.MessagePort
import androidx.javascriptengine.MessagePortClient
import com.google.common.util.concurrent.MoreExecutors
import dev.devilmusic.proof.PinnedBridgeBudget
import dev.devilmusic.proof.PinnedFixtureSourceContract
import dev.devilmusic.proof.TrackId
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.FutureTask
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** Offline-only parser host. Owned fixtures substitute for HTTP; no live transport or media preparation. */
class PinnedParserFixtureHost(context: Context, private val engine: JavaScriptSandbox) : AutoCloseable {
    private val context = context.applicationContext
    private val budget = PinnedBridgeBudget()
    private val stopped = AtomicBoolean()
    private val messages = AtomicInteger()
    private val port = AtomicReference<MessagePort?>()
    private val worker = Executors.newSingleThreadExecutor()
    private val deadlines = Executors.newSingleThreadScheduledExecutor()
    private val jobs = ConcurrentHashMap<Int, FutureTask<Unit>>()
    private val timers = ConcurrentHashMap<Int, ScheduledFuture<*>>()
    private val evaluators = ConcurrentHashMap<Int, JavaScriptIsolate>()

    fun run(parser: JavaScriptIsolate): String {
        try {
            if (!engine.isFeatureSupported(JavaScriptSandbox.JS_FEATURE_MESSAGE_PORTS)) throw IllegalStateException()
            val manifest = JSONObject(asset("pinned/manifest.json", 4096))
            check(manifest.getString("inputSha256") == "2f0006f1d5c554b27ef3756c62bf7c12d139772804b85ab147cbb16770055fba")
            val bundle = asset("pinned/youtubei-18.1.0.js", 2 * 1024 * 1024)
            val hash = MessageDigest.getInstance("SHA-256").digest(bundle.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
            check(hash == manifest.getString("classicSha256"))
            port.set(parser.createMessageChannel("pinned-parser-proof", MoreExecutors.directExecutor(), object : MessagePortClient {
                override fun onMessage(message: Message) { receive(message) }
            }))
            val script = bundle + "\n" + listOf("narrow-url.js", "pinned-host-bridge.js", "pinned-parser-probe.js")
                .joinToString("\n") { asset(it, 32768) }
            val report = JSONObject(parser.evaluateJavaScriptAsync(script).get(8, TimeUnit.SECONDS))
            check(report.getString("contract") == "pinned-parser-v1" && report.getBoolean("parser") && report.getBoolean("evaluator"))
            val value = report.getJSONObject("lease")
            val headers = value.getJSONObject("headers")
            require(headers.length() <= 16)
            val copied = headers.keys().asSequence().associateWith { key ->
                require(headers.opt(key) is String); headers.getString(key)
            }
            // Reuse the existing Media3 lease type; do not create/prepare a player or request its URI here.
            val source = PinnedFixtureSourceContract.lease(TrackId.YouTube.parse("abcdefghijk"), value.getString("id"),
                value.getString("uri"), copied, value.getLong("expiresAtMs"), value.getString("mimeType"), System.currentTimeMillis())
            check(source.headersForRequest()["X-Fixture-Audio"] == "kept")
            return "PINNED_PARSER_FIXTURE: PASS\nYouTube.js 18.1.0 parser and synthetic evaluator: PASS\nStable ID, audio headers and exact expiry: PASS\nNetwork/media requests: NONE\nLive source acquisition and Android audio playback: NOT TESTED"
        } finally { close() }
    }

    private fun receive(message: Message) {
        if (stopped.get()) return
        if (messages.incrementAndGet() > 20) { close(); return }
        var id = 0
        try {
            require(message.type == Message.TYPE_STRING && message.string.length <= 131072)
            val request = JSONObject(message.string)
            require(request.opt("v") is Int && request.getInt("v") == 1)
            val op = request.getString("op")
            if (op == "close") { require(request.length() == 2); close(); return }
            require(request.opt("id") is Int); id = request.getInt("id")
            if (op == "cancel") { require(request.length() == 3); cancel(id); return }
            val error = budget.begin(1, id, message.string.length, SystemClock.elapsedRealtime())
            if (error != null) { send(id, null, error); return }
            require(request.keys().asSequence().toSet() == when (op) {
                "fixture" -> setOf("v", "id", "op", "name")
                "evaluate" -> setOf("v", "id", "op", "source")
                else -> emptySet()
            })
            require(request.opt(if (op == "fixture") "name" else "source") is String)
            val currentId = id
            val job = FutureTask<Unit> {
                try {
                    if (!budget.isActive(currentId, SystemClock.elapsedRealtime()) || stopped.get()) return@FutureTask Unit
                    val result = when (op) {
                        "fixture" -> fixture(request.getString("name"))
                        "evaluate" -> evaluate(currentId, request.getString("source"))
                        else -> throw IllegalArgumentException()
                    }
                    finish(currentId, result, null)
                } catch (_: java.util.concurrent.TimeoutException) {
                    finish(currentId, null, "TIMEOUT")
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt(); finish(currentId, null, "CANCELLED")
                } catch (_: Exception) {
                    finish(currentId, null, if (op == "evaluate") "EVAL_FAILED" else "DENIED")
                }
            }
            jobs[currentId] = job
            timers[currentId] = deadlines.schedule({
                val running = jobs[currentId]
                finish(currentId, null, "TIMEOUT")
                running?.cancel(true)
                evaluators.remove(currentId)?.let { runCatching { it.close() } }
            }, 1500, TimeUnit.MILLISECONDS)
            if (stopped.get()) cancel(currentId) else worker.execute(job)
        } catch (_: Exception) {
            budget.cancel(id); send(id, null, "BAD_REQUEST")
        }
    }

    private fun fixture(name: String): JSONObject {
        require(name == "search" || name == "player")
        val value = JSONObject(asset("pinned/$name-fixture.json", 128 * 1024))
        if (name == "player") {
            val expiresSeconds = System.currentTimeMillis() / 1000 + 3600
            value.getJSONObject("response").getJSONObject("streamingData").getJSONArray("adaptiveFormats").getJSONObject(0)
                .put("url", "https://media.fixture.invalid/audio?expire=$expiresSeconds&n=fixture-input")
        }
        return value
    }

    private fun evaluate(id: Int, source: String): JSONObject {
        require(source.length <= 120000)
        if (!engine.isFeatureSupported(JavaScriptSandbox.JS_FEATURE_ISOLATE_MAX_HEAP_SIZE)) throw IllegalStateException()
        if (!engine.isFeatureSupported(JavaScriptSandbox.JS_FEATURE_EVALUATE_WITHOUT_TRANSACTION_LIMIT)) throw IllegalStateException()
        val parameters = IsolateStartupParameters().apply {
            setMaxHeapSizeBytes(16L * 1024 * 1024)
            setMaxEvaluationReturnSizeBytes(16 * 1024)
        }
        // A different isolate with NO MessagePort. Untrusted code cannot reach the parser's fixture bridge.
        val isolate = engine.createIsolate(parameters)
        evaluators[id] = isolate
        try {
            check(!stopped.get() && budget.isActive(id, SystemClock.elapsedRealtime()))
            val code = asset("pinned-evaluator.js", 8192) + "\nrunBoundedPlayerEvaluation(" + JSONObject.quote(source) + ")"
            val result = isolate.evaluateJavaScriptAsync(code).get(750, TimeUnit.MILLISECONDS)
            require(result.length <= 16384)
            val value = JSONObject(result)
            require(value.length() in 1..2 && value.keys().asSequence().all { it in setOf("sig", "n") && value.opt(it) is String && value.getString(it).length <= 8192 })
            return value
        } finally { evaluators.remove(id); runCatching { isolate.close() } }
    }

    private fun finish(id: Int, value: JSONObject?, error: String?) {
        val completion = budget.finish(id, SystemClock.elapsedRealtime())
        jobs.remove(id); timers.remove(id)?.cancel(false)
        when (completion) {
            PinnedBridgeBudget.Completion.VALID -> send(id, value, error)
            PinnedBridgeBudget.Completion.TIMEOUT -> send(id, null, "TIMEOUT")
            PinnedBridgeBudget.Completion.STALE -> Unit
        }
    }
    private fun send(id: Int, value: JSONObject?, error: String?) {
        if (stopped.get()) return
        val response = JSONObject().put("v", 1).put("id", id).put("ok", error == null).apply {
            if (error == null) put("value", value) else put("error", error)
        }.toString()
        if (response.length > 262144) { close(); return }
        runCatching { port.get()?.postMessage(Message.createStringMessage(response)) }
    }
    private fun cancel(id: Int) {
        budget.cancel(id); timers.remove(id)?.cancel(false); jobs.remove(id)?.cancel(true)
        evaluators.remove(id)?.let { runCatching { it.close() } }
    }
    private fun asset(name: String, limit: Int): String = context.assets.open(name).use { input ->
        val output = ByteArrayOutputStream(); val chunk = ByteArray(8192)
        while (true) { val count = input.read(chunk); if (count < 0) break; require(output.size() + count <= limit); output.write(chunk, 0, count) }
        output.toString(Charsets.UTF_8.name())
    }
    override fun close() {
        if (!stopped.compareAndSet(false, true)) return
        budget.close(); port.getAndSet(null)?.let { runCatching { it.close() } }
        timers.values.forEach { it.cancel(false) }; timers.clear()
        jobs.values.forEach { it.cancel(true) }; jobs.clear()
        evaluators.values.forEach { runCatching { it.close() } }; evaluators.clear()
        worker.shutdownNow(); deadlines.shutdownNow()
    }
}
