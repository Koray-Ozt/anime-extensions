package eu.kanade.tachiyomi.animeextension.tr.animpow

import android.annotation.SuppressLint
import android.app.Application
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import okhttp3.Headers
import uy.kohesive.injekt.injectLazy
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class AnimpowWebViewResolver(private val globalHeaders: Headers) {

    private val context: Application by injectLazy()
    private val handler by lazy { Handler(Looper.getMainLooper()) }

    class Result {
        var url: String? = null
        var quality: String? = null
        var cookie: String? = null
        var error: String? = null
    }

    private class JsInterface(
        private val result: Result,
        private val latch: CountDownLatch,
        private val cookieManager: CookieManager,
    ) {
        @JavascriptInterface
        fun passVideo(url: String, quality: String) {
            if (result.url != null) return
            result.url = url
            result.quality = quality.takeIf(String::isNotBlank)
            result.cookie = cookieManager.getCookie(url)
            latch.countDown()
        }

        @JavascriptInterface
        fun passError(message: String) {
            if (result.url != null || result.error != null) return
            result.error = message
            latch.countDown()
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    fun resolve(pageUrl: String): Result {
        val latch = CountDownLatch(1)
        val result = Result()
        var webView: WebView? = null
        val interfaceName = randomString()
        val cookieManager = CookieManager.getInstance().apply {
            setAcceptCookie(true)
        }
        val jsInterface = JsInterface(result, latch, cookieManager)

        handler.post {
            val view = WebView(context)
            webView = view

            with(view.settings) {
                javaScriptEnabled = true
                domStorageEnabled = true
                databaseEnabled = true
                mediaPlaybackRequiresUserGesture = false
                userAgentString = globalHeaders["User-Agent"]
            }

            cookieManager.setAcceptThirdPartyCookies(view, true)
            view.addJavascriptInterface(jsInterface, interfaceName)
            view.webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(
                    view: WebView,
                    request: WebResourceRequest,
                ): WebResourceResponse? {
                    val url = request.url.toString()
                    if (result.url == null && VIDEO_URL_REGEX.containsMatchIn(url)) {
                        jsInterface.passVideo(url, "Otomatik")
                    }
                    return super.shouldInterceptRequest(view, request)
                }

                override fun onPageFinished(view: WebView, url: String) {
                    super.onPageFinished(view, url)
                    view.evaluateJavascript(
                        """
                            (() => {
                              const send = () => {
                                const bodyText = document.body?.innerText || '';
                                if (bodyText.includes('Günlük İzleme Limit') || bodyText.includes('Gunluk Izleme Limit')) {
                                  window.$interfaceName.passError('Animpow gunluk izleme limitine ulasildi. Limit yenilendiginde tekrar deneyin.');
                                  return;
                                }
                                const video = document.querySelector("video[src], source[src]");
                                if (video?.src) {
                                  const quality = Array.from(document.querySelectorAll('button'))
                                    .map(button => (button.textContent || '').trim())
                                    .find(text => /^\d{3,4}p$/.test(text)) || 'Otomatik';
                                  window.$interfaceName.passVideo(video.src, quality);
                                  return;
                                }
                                setTimeout(send, 500);
                              };
                              send();
                            })();
                        """.trimIndent(),
                        null,
                    )
                }
            }

            val requestHeaders = globalHeaders.toMultimap().mapValues { it.value.firstOrNull().orEmpty() }
            view.loadUrl(pageUrl, requestHeaders)
        }

        latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)

        handler.post {
            webView?.stopLoading()
            webView?.removeJavascriptInterface(interfaceName)
            webView?.destroy()
            webView = null
        }

        if (result.url == null && result.error == null) {
            result.error = "Animpow oynaticisi zaman asimina ugradi. Lutfen tekrar deneyin."
        }

        return result
    }

    private fun randomString(length: Int = 12): String {
        val characters = ('a'..'z') + ('A'..'Z')
        return List(length) { characters.random() }.joinToString("")
    }

    companion object {
        private const val TIMEOUT_SECONDS = 30L
        private val VIDEO_URL_REGEX = Regex("https?://[^\\s\"']+(?:/stream/\\?token=|\\.m3u8(?:\\?|$)|\\.mp4(?:\\?|$))")
    }
}
