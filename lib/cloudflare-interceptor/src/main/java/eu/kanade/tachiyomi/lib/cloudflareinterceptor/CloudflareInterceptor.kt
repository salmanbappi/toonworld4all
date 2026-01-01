package eu.kanade.tachiyomi.lib.cloudflareinterceptor

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import okhttp3.Cookie
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class CloudflareInterceptor(private val context: Context) : Interceptor {

    private val handler by lazy { Handler(Looper.getMainLooper()) }

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val response = chain.proceed(request)

        // Check if Cloudflare protection is active
        if (response.code !in listOf(403, 503) || response.header("Server") !in listOf("cloudflare", "Cloudflare")) {
            return response
        }

        return try {
            resolveWithWebView(request)
            response.close()
            chain.proceed(request)
        } catch (e: Exception) {
            response
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun resolveWithWebView(request: Request) {
        val latch = CountDownLatch(1)
        var webView: WebView? = null

        handler.post {
            webView = WebView(context).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                
                webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView?, url: String?) {
                        val cookies = android.webkit.CookieManager.getInstance().getCookie(url)
                        if (cookies != null && "cf_clearance" in cookies) {
                            latch.countDown()
                        }
                    }
                }
                loadUrl(request.url.toString())
            }
        }

        latch.await(30, TimeUnit.SECONDS)
        handler.post { webView?.destroy() }
    }
}