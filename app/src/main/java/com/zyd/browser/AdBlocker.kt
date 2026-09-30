package com.zyd.browser

import android.content.Context
import android.net.Uri
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import java.io.ByteArrayInputStream
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger

class AdBlocker(context: Context) {
    private val blockedDomains: Set<String> = context.assets
        .open("blocked_domains.txt")
        .bufferedReader()
        .useLines { lines ->
            lines.map(String::trim)
                .filter { it.isNotEmpty() && !it.startsWith("#") }
                .map { it.substringAfterLast(' ').lowercase(Locale.US).trimEnd('.') }
                .filter { '/' !in it }
                .toSet()
        }

    private val blockedCounter = AtomicInteger(0)

    @Volatile
    var enabled: Boolean = true

    fun resetCount() {
        blockedCounter.set(0)
    }

    fun blockedCount(): Int = blockedCounter.get()

    fun intercept(request: WebResourceRequest): WebResourceResponse? {
        if (!enabled || request.isForMainFrame || !shouldBlock(request.url)) return null
        blockedCounter.incrementAndGet()
        return EMPTY_RESPONSE
    }

    fun interceptServiceWorker(request: WebResourceRequest): WebResourceResponse? {
        if (!enabled || !shouldBlock(request.url)) return null
        blockedCounter.incrementAndGet()
        return EMPTY_RESPONSE
    }

    internal fun shouldBlock(uri: Uri): Boolean {
        val host = uri.host?.lowercase(Locale.US)?.trimEnd('.') ?: return false
        var candidate = host
        while (true) {
            if (candidate in blockedDomains) return true
            val dot = candidate.indexOf('.')
            if (dot < 0) return false
            candidate = candidate.substring(dot + 1)
        }
    }

    companion object {
        private val EMPTY_RESPONSE = WebResourceResponse(
            "text/plain",
            "utf-8",
            204,
            "Blocked",
            mapOf("Cache-Control" to "no-store"),
            ByteArrayInputStream(ByteArray(0))
        )

        val COSMETIC_FILTER_SCRIPT = """
            (() => {
              if (document.getElementById('__zyd_adblock_style')) return;
              const style = document.createElement('style');
              style.id = '__zyd_adblock_style';
              style.textContent = `
                .adsbygoogle, [id^="google_ads"], [id*="google_ads"],
                [class~="advertisement"], [class~="ad-container"],
                [class~="ad-wrapper"], [class~="ad-slot"], [class~="sponsored"],
                iframe[src*="doubleclick.net"], iframe[src*="googlesyndication.com"],
                iframe[src*="taboola.com"], iframe[src*="outbrain.com"] {
                  display: none !important; visibility: hidden !important;
                }
              `;
              (document.head || document.documentElement).appendChild(style);
            })();
        """.trimIndent()
    }
}
