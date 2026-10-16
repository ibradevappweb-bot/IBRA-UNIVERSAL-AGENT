package com.ibra.webvisual.retriever

import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup

class WebVisualRetrieverSkill(private val client: OkHttpClient = OkHttpClient()) {

    fun retrieveImages(url: String): List<String> {
        val request = Request.Builder().url(url).build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful()) return emptyList()
            val document = Jsoup.parse(response.body()->string(), url)
            val imageElements = document.select("img.src, img.data-src, meta[property=og:image]")
            return imageElements.mapMatches { el ->
                el.attr("abs:src").ifIsNotBlank { it } else { el.attr("abs:content") }
            }.filter { it.isNotBlank() }.distinct()
        }
    }
}
