/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.news

import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.StringReader
import java.net.HttpURLConnection
import java.net.URL

/**
 * Shared RSS news source for the news feed page and the homepage news
 * section. No key, no account: public Google News RSS feeds (Chinese)
 * plus Hacker News. Blocking network call — always call off the main
 * thread (or from a coroutine on Dispatchers.IO).
 */
object NewsRepository {

    data class NewsItem(val title: String, val link: String, val source: String, val date: String)

    val categories = listOf(
        "头条" to "https://news.google.com/rss?hl=zh-CN&gl=CN&ceid=CN:zh-Hans",
        "科技" to "https://news.google.com/rss/search?q=科技&hl=zh-CN&gl=CN&ceid=CN:zh-Hans",
        "体育" to "https://news.google.com/rss/search?q=体育&hl=zh-CN&gl=CN&ceid=CN:zh-Hans",
        "娱乐" to "https://news.google.com/rss/search?q=娱乐&hl=zh-CN&gl=CN&ceid=CN:zh-Hans",
        "财经" to "https://news.google.com/rss/search?q=财经&hl=zh-CN&gl=CN&ceid=CN:zh-Hans",
        "Hacker News" to "https://news.ycombinator.com/rss",
    )

    fun fetchFeed(feedUrl: String): List<NewsItem> {
        return try {
            val connection = URL(feedUrl).openConnection() as HttpURLConnection
            connection.setRequestProperty(
                "User-Agent",
                "Mozilla/5.0 (Android 14; Mobile) Iceraven/159.0",
            )
            connection.connectTimeout = 15000
            connection.readTimeout = 15000
            val xml = connection.inputStream.bufferedReader(Charsets.UTF_8).readText()
            connection.disconnect()
            parseRss(xml)
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun parseRss(xml: String): List<NewsItem> {
        val items = mutableListOf<NewsItem>()
        try {
            val parser = Xml.newPullParser()
            parser.setInput(StringReader(xml))
            var event = parser.eventType
            var inItem = false
            var title = ""
            var link = ""
            var source = ""
            var date = ""
            var text = ""
            while (event != XmlPullParser.END_DOCUMENT && items.size < 30) {
                when (event) {
                    XmlPullParser.START_TAG -> {
                        if (parser.name == "item") {
                            inItem = true
                            title = ""
                            link = ""
                            source = ""
                            date = ""
                        }
                    }
                    XmlPullParser.TEXT -> text = parser.text ?: ""
                    XmlPullParser.END_TAG -> {
                        if (inItem) {
                            when (parser.name) {
                                "title" -> title = text.trim()
                                "link" -> link = text.trim()
                                "source" -> source = text.trim()
                                "pubDate" -> date = text.trim().take(25)
                                "item" -> {
                                    inItem = false
                                    if (title.isNotBlank() && link.isNotBlank()) {
                                        items.add(NewsItem(title, link, source.ifBlank { "新闻" }, date))
                                    }
                                }
                            }
                        }
                    }
                }
                event = parser.next()
            }
        } catch (e: Exception) {
            // Return whatever parsed so far.
        }
        return items
    }
}
