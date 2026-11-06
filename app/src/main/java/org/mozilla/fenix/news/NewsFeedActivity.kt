/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.news

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Xml
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import org.xmlpull.v1.XmlPullParser
import java.io.StringReader
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

/**
 * Brave News-style news feed, opened from Settings ("新闻资讯").
 * No key, no account, no Brave backend: it aggregates public Google News
 * RSS feeds (Chinese) per category, plus Hacker News for tech.
 * Tap an item to open the original article in the browser.
 */
class NewsFeedActivity : AppCompatActivity() {

    private lateinit var listView: LinearLayout
    private lateinit var scrollView: ScrollView
    private lateinit var statusView: TextView

    private val categories = listOf(
        "头条" to "https://news.google.com/rss?hl=zh-CN&gl=CN&ceid=CN:zh-Hans",
        "科技" to "https://news.google.com/rss/search?q=科技&hl=zh-CN&gl=CN&ceid=CN:zh-Hans",
        "体育" to "https://news.google.com/rss/search?q=体育&hl=zh-CN&gl=CN&ceid=CN:zh-Hans",
        "娱乐" to "https://news.google.com/rss/search?q=娱乐&hl=zh-CN&gl=CN&ceid=CN:zh-Hans",
        "财经" to "https://news.google.com/rss/search?q=财经&hl=zh-CN&gl=CN&ceid=CN:zh-Hans",
        "Hacker News" to "https://news.ycombinator.com/rss",
    )
    private var currentCategory = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }
        setContentView(root)

        root.addView(TextView(this).apply {
            text = "新闻资讯"
            textSize = 20f
            setPadding(0, 0, 0, dp(4))
        })
        root.addView(TextView(this).apply {
            text = "聚合公开新闻源，点标题看原文。无需登录。"
            setPadding(0, 0, 0, dp(12))
        })

        val chips = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        categories.forEachIndexed { index, (name, _) ->
            chips.addView(Button(this).apply {
                text = name
                setOnClickListener { loadCategory(index) }
            })
        }
        // Horizontal scroll for the chips row.
        val chipsScroll = android.widget.HorizontalScrollView(this).apply { addView(chips) }
        root.addView(chipsScroll)

        statusView = TextView(this).apply { setPadding(0, dp(8), 0, dp(4)) }
        root.addView(statusView)

        listView = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scrollView = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f,
            )
            addView(listView)
        }
        root.addView(scrollView)

        root.addView(Button(this).apply {
            text = "刷新"
            setOnClickListener { loadCategory(currentCategory) }
        })

        loadCategory(0)
    }

    private fun dp(value: Int) =
        (value * resources.displayMetrics.density).toInt()

    private fun loadCategory(index: Int) {
        currentCategory = index
        val (name, url) = categories[index]
        statusView.text = "正在加载 $name…"
        listView.removeAllViews()
        thread {
            val items = fetchFeed(url)
            runOnUiThread {
                if (items.isEmpty()) {
                    statusView.text = "$name：没拉到内容，请检查网络后点刷新重试。"
                } else {
                    statusView.text = "$name · ${items.size} 条"
                    items.forEach { item ->
                        listView.addView(buildItemView(item))
                    }
                }
            }
        }
    }

    private fun buildItemView(item: NewsItem): TextView {
        return TextView(this).apply {
            text = "${item.title}\n${item.source} · ${item.date}"
            textSize = 15f
            setPadding(0, dp(10), 0, dp(10))
            setOnClickListener {
                try {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(item.link)))
                } catch (e: Exception) {
                    Toast.makeText(this@NewsFeedActivity, "打不开链接：${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private data class NewsItem(val title: String, val link: String, val source: String, val date: String)

    private fun fetchFeed(feedUrl: String): List<NewsItem> {
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

    private fun parseRss(xml: String): List<NewsItem> {
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
