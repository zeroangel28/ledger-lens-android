package io.ledgerlens.app.ledger

import android.annotation.SuppressLint
import android.content.Context
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import io.ledgerlens.app.crypto.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.util.UUID

/** Local bundled codecs only: no network, file URLs, navigation, or user-supplied JavaScript. */
class SigningEngine(private val context: Context) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mutex = Mutex()
    private var webView: WebView? = null
    private var pending: CompletableDeferred<String>? = null
    private var operationId: String? = null
    private var transport: LedgerTransport? = null
    private var permit: LedgerPermit? = null
    private var loaded: CompletableDeferred<Unit>? = null
    @SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
    private suspend fun load() = withContext(Dispatchers.Main.immediate) {
        if (webView == null) {
            val ready = CompletableDeferred<Unit>(); loaded = ready
            webView = WebView(context).apply {
                settings.javaScriptEnabled = true
                settings.allowFileAccess = false; settings.allowContentAccess = false
                settings.blockNetworkLoads = true; settings.domStorageEnabled = false
                addJavascriptInterface(Bridge(), "NativeLedger")
                webViewClient = object: WebViewClient() {
                    override fun shouldOverrideUrlLoading(view: WebView?, request: android.webkit.WebResourceRequest?) = true
                }
                val bundle = context.assets.open("signing-engine.js").bufferedReader().use { it.readText() }
                loadDataWithBaseURL("https://ledger-lens.invalid/", "<!doctype html><meta charset=utf-8><script>window.onerror=m=>NativeLedger.loadFailure(String(m));</script><script>" + bundle.replace("</script", "<\\/script") + "</script>", "text/html", "UTF-8", null)
            }
        }
        withTimeout(30_000) { loaded!!.await() }
    }
    suspend fun run(operation: String, payload: JSONObject, session: LedgerTransport? = null, authorization: LedgerPermit? = null): String = mutex.withLock {
        require(operation in setOf("build", "sign", "validate", "verifyAddress"))
        load()
        val id = UUID.randomUUID().toString(); val result = CompletableDeferred<String>()
        withContext(Dispatchers.Main.immediate) {
            operationId = id; pending = result; transport = session; permit = authorization
            webView!!.evaluateJavascript("runLedgerOperation(${JSONObject.quote(id)},${JSONObject.quote(operation)},$payload)", null)
        }
        try { withTimeout(300_000) { result.await() } }
        finally { withContext(Dispatchers.Main.immediate) { authorization?.revoke(); pending = null; operationId = null; transport = null; permit = null } }
    }
    private inner class Bridge {
        @JavascriptInterface fun ready() { scope.launch { loaded?.complete(Unit) } }
        @JavascriptInterface fun loadFailure(message: String) { scope.launch { loaded?.completeExceptionally(IllegalStateException("Offline transaction engine: " + message.take(250))) } }
        @JavascriptInterface fun complete(id: String, json: String, error: String) { scope.launch {
            if (operationId != id) return@launch
            if (error.isNotBlank()) pending?.completeExceptionally(IllegalStateException(error.take(500))) else pending?.complete(json)
        } }
        @JavascriptInterface fun exchange(id: String, commandHex: String) { scope.launch {
            var response = ""; var message = ""
            val activeOperation = operationId
            try {
                require(id.matches(Regex("[0-9]{1,12}")) && commandHex.length <= 520)
                val session = transport ?: error("Ledger is not connected for this operation")
                val authorization = permit ?: error("Ledger operation was not approved")
                response = session.exchangeAuthorized(commandHex.unhex(), authorization).hex()
            } catch (e: Exception) { message = e.message ?: "Ledger exchange failed" }
            if (operationId == activeOperation && activeOperation != null) webView?.evaluateJavascript("ledgerReply(${JSONObject.quote(id)},${JSONObject.quote(response)},${JSONObject.quote(message)})", null)
        } }
    }
    fun close() { scope.cancel(); permit?.revoke(); webView?.destroy(); webView = null }
}
