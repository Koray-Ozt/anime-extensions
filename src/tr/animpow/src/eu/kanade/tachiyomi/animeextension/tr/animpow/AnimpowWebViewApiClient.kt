package eu.kanade.tachiyomi.animeextension.tr.animpow

import android.annotation.SuppressLint
import android.app.Application
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONObject
import uy.kohesive.injekt.injectLazy
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class AnimpowWebViewApiClient(
    private val json: Json,
    private val globalHeaders: Headers,
) {

    private val context: Application by injectLazy()
    private val handler by lazy { Handler(Looper.getMainLooper()) }
    private val requestMutex = Mutex()
    private val ready = CompletableDeferred<Unit>()
    private val pending = ConcurrentHashMap<String, CompletableDeferred<String>>()

    @Volatile
    private var webView: WebView? = null

    private val bridge = JsBridge()

    suspend fun get(path: String, vararg queryParameters: Pair<String, String?>): JsonObject = requestMutex.withLock {
        ensureWebView()

        val url = "$API_BASE$path".toHttpUrl().newBuilder().apply {
            queryParameters.forEach { (key, value) ->
                if (value != null) addQueryParameter(key, value)
            }
        }.build().toString()

        val requestId = UUID.randomUUID().toString()
        val result = CompletableDeferred<String>()
        pending[requestId] = result

        handler.post {
            webView?.evaluateJavascript(fetchScript(requestId, url), null)
                ?: result.completeExceptionally(Exception("Animpow WebView baslatilamadi."))
        }

        try {
            val response = withTimeout(REQUEST_TIMEOUT_MS) { result.await() }
            json.parseToJsonElement(response).jsonObject
        } finally {
            pending.remove(requestId)
        }
    }

    @SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
    private suspend fun ensureWebView() {
        if (webView == null) {
            handler.post {
                if (webView != null) return@post

                val view = WebView(context)
                webView = view
                with(view.settings) {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    databaseEnabled = true
                    userAgentString = globalHeaders["User-Agent"]
                }
                view.addJavascriptInterface(bridge, BRIDGE_NAME)
                view.webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView, url: String) {
                        super.onPageFinished(view, url)
                        ready.complete(Unit)
                    }

                    override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                        super.onReceivedError(view, request, error)
                        if (request.isForMainFrame && !ready.isCompleted) {
                            ready.completeExceptionally(Exception("Animpow WebView yuklenemedi: ${error.description}"))
                        }
                    }
                }

                val requestHeaders = globalHeaders.toMultimap().mapValues { it.value.firstOrNull().orEmpty() }
                view.loadUrl(SITE_URL, requestHeaders)
            }
        }

        withTimeout(PAGE_TIMEOUT_MS) { ready.await() }
    }

    private fun fetchScript(requestId: String, url: String): String =
        """
            (() => {
              const requestId = ${JSONObject.quote(requestId)};
              fetch(${JSONObject.quote(url)}, { headers: { Accept: 'application/json' } })
                .then(async response => {
                  const text = await response.text();
                  if (!response.ok) throw new Error('HTTP ' + response.status + ': ' + text);
                  window.$BRIDGE_NAME.passResult(requestId, text);
                })
                .catch(error => window.$BRIDGE_NAME.passError(requestId, String(error?.message || error)));
            })();
        """.trimIndent()

    private inner class JsBridge {
        @JavascriptInterface
        fun passResult(requestId: String, response: String) {
            pending[requestId]?.complete(response)
        }

        @JavascriptInterface
        fun passError(requestId: String, message: String) {
            pending[requestId]?.completeExceptionally(Exception("Animpow WebView API hatasi: $message"))
        }
    }

    companion object {
        private const val SITE_URL = "https://animpow.com/"
        private const val API_BASE = "https://client-api.animpow.com/api/v1"
        private const val BRIDGE_NAME = "AnimpowExtensionBridge"
        private const val PAGE_TIMEOUT_MS = 30_000L
        private const val REQUEST_TIMEOUT_MS = 30_000L
    }
}
