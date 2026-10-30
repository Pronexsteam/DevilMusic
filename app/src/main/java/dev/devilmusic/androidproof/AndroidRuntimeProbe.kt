package dev.devilmusic.androidproof

import android.content.Context
import androidx.javascriptengine.IsolateStartupParameters
import androidx.javascriptengine.JavaScriptSandbox
import androidx.javascriptengine.Message
import androidx.javascriptengine.MessagePortClient
import androidx.javascriptengine.MessagePort
import androidx.javascriptengine.JavaScriptIsolate
import com.google.common.util.concurrent.MoreExecutors
import dev.devilmusic.proof.RuntimeRequirements
import dev.devilmusic.proof.OfflineBridgePolicy
import org.json.JSONObject
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** One short-lived, bounded sandbox. No network bridge and no remotely supplied script. */
class AndroidRuntimeProbe(context: Context) : AutoCloseable {
    private val context = context.applicationContext
    private val executor = Executors.newSingleThreadExecutor()
    private val sandbox = AtomicReference<JavaScriptSandbox?>()
    private val stopped = AtomicBoolean()
    private val started = AtomicBoolean()
    private val result = CompletableFuture<String>()

    fun start(offlineBridge: Boolean = false, pinnedFixture: Boolean = false): CompletableFuture<String> {
        check(started.compareAndSet(false, true))
        executor.execute {
            try {
                if (!JavaScriptSandbox.isSupported()) {
                    result.complete("UNSUPPORTED_ENGINE: this WebView provider does not expose JavaScriptSandbox.")
                    return@execute
                }
                val connected = JavaScriptSandbox.createConnectedInstanceAsync(context)
                // A connection completing after timeout/cancellation must not leak a service.
                connected.addListener({ if (stopped.get()) runCatching { connected.get().close() } }, MoreExecutors.directExecutor())
                val engine = connected.get(8, TimeUnit.SECONDS)
                sandbox.set(engine)
                if (stopped.get()) return@execute
                val features = listOf(JavaScriptSandbox.JS_FEATURE_MESSAGE_PORTS,
                    JavaScriptSandbox.JS_FEATURE_PROMISE_RETURN,
                    JavaScriptSandbox.JS_FEATURE_ISOLATE_MAX_HEAP_SIZE,
                    JavaScriptSandbox.JS_FEATURE_ISOLATE_TERMINATION,
                    JavaScriptSandbox.JS_FEATURE_EVALUATE_WITHOUT_TRANSACTION_LIMIT)
                val missing = features.filterNot(engine::isFeatureSupported)
                if (missing.isNotEmpty()) {
                    result.complete("MISSING_ENGINE_FEATURES: ${missing.joinToString()}")
                    return@execute
                }
                val params = IsolateStartupParameters().apply {
                    setMaxHeapSizeBytes(32L * 1024 * 1024)
                    setMaxEvaluationReturnSizeBytes(16 * 1024)
                }
                engine.createIsolate(params).use { isolate ->
                    if (pinnedFixture) {
                        PinnedParserFixtureHost(context, engine).use { host -> result.complete(host.run(isolate)) }
                        return@use
                    }
                    if (offlineBridge) {
                        checkOfflineBridge(isolate)
                        return@use
                    }
                    val reply = CompletableFuture<String>()
                    val port = isolate.createMessageChannel("runtime-proof", MoreExecutors.directExecutor(), object : MessagePortClient {
                        override fun onMessage(message: Message) {
                            if (message.type == Message.TYPE_STRING && message.string == "isolate-response-v1") reply.complete(message.string)
                            else reply.completeExceptionally(IllegalStateException("Unexpected probe response"))
                        }
                    })
                    val script = context.assets.open("runtime-probe.js").bufferedReader().use { it.readText() }
                    val inventory = JSONObject(isolate.evaluateJavaScriptAsync(script).get(5, TimeUnit.SECONDS)).getJSONObject("apis")
                    port.postMessage(Message.createStringMessage("native-challenge-v1"))
                    reply.get(3, TimeUnit.SECONDS)
                    val apis = RuntimeRequirements.requiredApis.associateWith { inventory.optBoolean(it, false) }
                    val assessment = RuntimeRequirements.assess(true, true, apis)
                    result.complete("${assessment.stage}\nMessage-port round trip: PASS\nMissing Web APIs: ${assessment.missing.joinToString().ifEmpty { "none detected; conformance untested" }}\nYouTube search/source/playback: NOT TESTED")
                }
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt(); result.cancel(false)
            } catch (error: Exception) {
                // No arbitrary exception message/URLs/script data reaches the UI.
                result.complete("Runtime probe failed: ${error.javaClass.simpleName}. YouTube was not contacted.")
            } finally { close() }
        }
        return result
    }

    private fun checkOfflineBridge(isolate: JavaScriptIsolate) {
        val policy = OfflineBridgePolicy()
        val reference = AtomicReference<MessagePort?>()
        val port = isolate.createMessageChannel("offline-json-proof", MoreExecutors.directExecutor(), object : MessagePortClient {
            override fun onMessage(message: Message) {
                if (stopped.get()) return
                var id = 0
                val response = try {
                    require(message.type == Message.TYPE_STRING && message.string.length <= 1024)
                    val request = JSONObject(message.string)
                    require(request.opt("id") is Int && request.opt("v") is Int)
                    id = request.getInt("id")
                    val error = policy.check(request.getInt("v"), id, request.optString("op"), request.optString("path"),
                        request.keys().asSequence().toSet() == setOf("v", "id", "op", "path"))
                    JSONObject().put("v", 1).put("id", id).put("ok", error == null).apply {
                        if (error != null) put("error", error)
                        else { put("status", 200); put("body", JSONObject("""{"items":[{"id":"fixture:evening","title":"Тихий вечер · 夜"},{"id":"fixture:morning","title":"Morning"}]}""")) }
                    }
                } catch (_: Exception) { JSONObject().put("v", 1).put("id", id).put("ok", false).put("error", "BAD_REQUEST") }
                // Only application-owned fixture code is connected to this port; no HTTP, tokens or files.
                runCatching { reference.get()?.postMessage(Message.createStringMessage(response.toString())) }
            }
        })
        reference.set(port)
        try {
            val script = listOf("offline-json-bridge.js", "offline-json-probe.js").joinToString("\n") { asset ->
                context.assets.open(asset).bufferedReader().use { it.readText() }
            }
            val report = JSONObject(isolate.evaluateJavaScriptAsync(script).get(5, TimeUnit.SECONDS))
            check(report.getString("contract") == "offline-json-v1" && report.getBoolean("fixture") && report.getBoolean("unicode") && report.getBoolean("denied"))
            result.complete("OFFLINE_JSON_BRIDGE: PASS\nFixture JSON and Unicode: PASS\nExternal URL denied: PASS\nNo HTTP implementation or Web API polyfills.\nYouTube.js compatibility and online playback: NOT TESTED")
        } finally { reference.set(null); port.close() }
    }

    override fun close() {
        stopped.set(true)
        sandbox.getAndSet(null)?.close()
        if (!result.isDone) result.cancel(false)
        executor.shutdownNow()
    }
}
