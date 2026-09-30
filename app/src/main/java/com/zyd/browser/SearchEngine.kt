package com.zyd.browser

import java.net.URLEncoder
import java.nio.charset.StandardCharsets

enum class SearchEngine(
    val id: String,
    val displayName: String,
    val searchAction: String,
    val queryParameter: String
) {
    BING(
        id = "bing",
        displayName = "Bing 中国",
        searchAction = "https://cn.bing.com/search",
        queryParameter = "q"
    ),
    BAIDU(
        id = "baidu",
        displayName = "百度",
        searchAction = "https://www.baidu.com/s",
        queryParameter = "wd"
    );

    fun searchUrl(query: String): String =
        "$searchAction?$queryParameter=" +
            URLEncoder.encode(query, StandardCharsets.UTF_8.name())

    companion object {
        fun fromId(id: String?): SearchEngine = entries.firstOrNull { it.id == id } ?: BING
    }
}
