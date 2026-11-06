/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.news

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import org.mozilla.fenix.ads.GoogleSdk
import org.mozilla.fenix.ads.buildCompactNativeAdView
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

    private val categories = NewsRepository.categories
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
            val items = NewsRepository.fetchFeed(url)
            runOnUiThread {
                if (items.isEmpty()) {
                    statusView.text = "$name：没拉到内容，请检查网络后点刷新重试。"
                } else {
                    statusView.text = "$name · ${items.size} 条"
                    items.forEachIndexed { index, item ->
                        listView.addView(buildItemView(item))
                        // In-feed native ad every 5 items; loads independently
                        // and stays hidden on failure, so it never leaves a gap.
                        if ((index + 1) % 5 == 0) {
                            insertNativeAdSlot()
                        }
                    }
                }
            }
        }
    }

    private fun insertNativeAdSlot() {
        val nativeAdView = buildCompactNativeAdView(this).apply {
            visibility = android.view.View.GONE
        }
        listView.addView(nativeAdView)
        try {
            GoogleSdk.loadNative(this) { ad ->
                if (ad != null) {
                    GoogleSdk.bindNativeAd(ad, nativeAdView)
                    nativeAdView.visibility = android.view.View.VISIBLE
                }
            }
        } catch (e: Exception) {
            // Ad stays hidden; the feed is unaffected.
        }
    }

    private fun buildItemView(item: NewsRepository.NewsItem): TextView {
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
}
