package eu.kanade.tachiyomi.animeextension.tr.hentaizm

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

class HentaizmWebViewResolver(private val globalHeaders: Headers) {

    private val context: Application by injectLazy()
    private val handler by lazy { Handler(Looper.getMainLooper()) }

    class Result {
        var url: String? = null
        var cookie: String? = null
        var error: String? = null
    }

    private class JsInterface(
        private val result: Result,
        private val latch: CountDownLatch,
        private val cookieManager: CookieManager,
    ) {
        @JavascriptInterface
        fun passVideo(url: String) {
            if (result.url != null || result.error != null) return
            result.url = url
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
        val cookieManager = CookieManager.getInstance().apply { setAcceptCookie(true) }
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
                    if (
                        result.url == null &&
                        STREAM_REGEX.containsMatchIn(url) &&
                        BLOCKED_HOSTS.none { url.contains(it, ignoreCase = true) }
                    ) {
                        jsInterface.passVideo(url)
                    }
                    return super.shouldInterceptRequest(view, request)
                }

                override fun onPageFinished(view: WebView, url: String) {
                    super.onPageFinished(view, url)
                    view.evaluateJavascript(
                        """
                            (() => {
                              let attempts = 0;
                              const send = () => {
                                const video = document.querySelector('video');
                                const source = document.querySelector('video source[src]');
                                const stream = video?.currentSrc || video?.src || source?.src || '';
                                if (/^https?:\/\/.+\.(m3u8|mp4|mpd)(\?.*)?$/i.test(stream)) {
                                  window.$interfaceName.passVideo(stream);
                                  return;
                                }

                                const bodyText = document.body?.innerText || '';
                                const needsLogin = /giriş yap|üye girişi|login/i.test(bodyText) &&
                                  !document.querySelector('video, iframe[src]');
                                if (needsLogin && attempts >= 3) {
                                  window.$interfaceName.passError('Hentaizm hesabı gerekli. Anikku’da Hentaizm kaynağını WebView’de açıp giriş yapın, CAPTCHA’yı tamamlayın ve tekrar deneyin.');
                                  return;
                                }

                                attempts += 1;
                                try { video?.load(); } catch (_) {}
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
            result.error = "Hentaizm oynatıcısı zaman aşımına uğradı. WebView girişinizi kontrol edip tekrar deneyin."
        }

        return result
    }

    private fun randomString(length: Int = 12): String {
        val characters = ('a'..'z') + ('A'..'Z')
        return List(length) { characters.random() }.joinToString("")
    }

    companion object {
        private const val TIMEOUT_SECONDS = 20L
        private val STREAM_REGEX = Regex("https?://.+\\.(?:m3u8|mp4|mpd)(?:\\?.*)?$", RegexOption.IGNORE_CASE)
        private val BLOCKED_HOSTS = listOf("doubleclick", "google-analytics", "googletagmanager")
    }
}
