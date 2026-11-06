/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.aisearch

import android.os.Bundle
import android.text.Html
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlin.concurrent.thread

/**
 * AI search chat, opened from Settings. Ask a question, the app fetches
 * live web search results and shows a summarized answer with sources.
 *
 * No API key and no account are needed: results come from the public
 * DuckDuckGo HTML endpoint. Nothing about the question is sent anywhere
 * except the search query itself, exactly as if the user had searched
 * in the browser. Chat history lives only in memory for this session.
 */
class AiSearchActivity : AppCompatActivity() {

    private lateinit var historyView: LinearLayout
    private lateinit var scrollView: ScrollView
    private lateinit var input: EditText
    private lateinit var sendButton: Button

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
            text = "AI 搜索"
            textSize = 20f
            setPadding(0, 0, 0, dp(4))
        })
        root.addView(TextView(this).apply {
            text = "问一句，联网查最新结果，汇总回答并附来源。无需登录、无需密钥。"
            setPadding(0, 0, 0, dp(12))
        })

        val chips = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        listOf("今天有什么科技新闻", "Iceraven 最新版本", "明天天气怎么样").forEach { suggestion ->
            chips.addView(Button(this).apply {
                text = suggestion
                setOnClickListener { input.setText(suggestion); ask() }
            })
        }
        root.addView(chips)

        historyView = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scrollView = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f,
            )
            addView(historyView)
        }
        root.addView(scrollView)

        val inputRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(8), 0, 0)
        }
        input = EditText(this).apply {
            hint = "输入你的问题…"
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        sendButton = Button(this).apply {
            text = "搜索"
            setOnClickListener { ask() }
        }
        inputRow.addView(input)
        inputRow.addView(sendButton)
        root.addView(inputRow)

        addMessage("AI", "你好，我是 AI 搜索。问我任何问题，我会联网查最新资料再回答你。")
    }

    private fun dp(value: Int) =
        (value * resources.displayMetrics.density).toInt()

    private fun addMessage(who: String, text: String) {
        historyView.addView(TextView(this).apply {
            this.text = "$who：\n$text"
            setPadding(0, dp(8), 0, dp(8))
        })
        scrollView.post { scrollView.fullScroll(ScrollView.FOCUS_DOWN) }
    }

    private fun ask() {
        val question = input.text.toString().trim()
        if (question.isEmpty()) {
            Toast.makeText(this, "先输入问题", Toast.LENGTH_SHORT).show()
            return
        }
        input.setText("")
        sendButton.isEnabled = false
        addMessage("你", question)
        addMessage("AI", "正在联网搜索…")
        thread {
            val answer = search(question)
            runOnUiThread {
                sendButton.isEnabled = true
                // Replace the "正在联网搜索…" placeholder with the answer.
                val last = historyView.getChildAt(historyView.childCount - 1) as? TextView
                last?.text = "AI：\n$answer"
                scrollView.post { scrollView.fullScroll(ScrollView.FOCUS_DOWN) }
            }
        }
    }

    private fun search(question: String): String {
        return try {
            val url = URL("https://html.duckduckgo.com/html/?q=" + URLEncoder.encode(question, "UTF-8"))
            val connection = url.openConnection() as HttpURLConnection
            connection.setRequestProperty(
                "User-Agent",
                "Mozilla/5.0 (Android 14; Mobile) Iceraven/159.0",
            )
            connection.connectTimeout = 15000
            connection.readTimeout = 15000
            val html = connection.inputStream.bufferedReader(Charsets.UTF_8).readText()
            connection.disconnect()
            val results = parseResults(html)
            if (results.isEmpty()) {
                "没查到相关结果，换个问法试试。"
            } else {
                buildString {
                    append("根据最新搜索结果：\n\n")
                    results.take(5).forEachIndexed { index, result ->
                        append("${index + 1}. ${result.title}\n")
                        if (result.snippet.isNotBlank()) append("   ${result.snippet}\n")
                        append("   来源：${result.url}\n\n")
                    }
                    append("（以上为联网搜索汇总，仅供参考）")
                }
            }
        } catch (e: Exception) {
            "搜索失败，请检查网络后重试。（${e.message}）"
        }
    }

    private data class SearchResult(val title: String, val url: String, val snippet: String)

    private fun parseResults(html: String): List<SearchResult> {
        val results = mutableListOf<SearchResult>()
        val linkPattern = Regex(
            """<a[^>]*class="result__a"[^>]*href="([^"]+)"[^>]*>(.*?)</a>""",
            RegexOption.DOT_MATCHES_ALL,
        )
        val snippetPattern = Regex(
            """<a[^>]*class="result__snippet"[^>]*>(.*?)</a>""",
            RegexOption.DOT_MATCHES_ALL,
        )
        val links = linkPattern.findAll(html).toList()
        val snippets = snippetPattern.findAll(html).toList()
        links.forEachIndexed { index, match ->
            val rawUrl = match.groupValues[1]
            val url = rawUrl.substringAfter("uddg=").substringBefore("&").let {
                try {
                    java.net.URLDecoder.decode(it, "UTF-8")
                } catch (e: Exception) {
                    it
                }
            }.ifBlank { rawUrl }
            val title = clean(match.groupValues[2])
            val snippet = if (index < snippets.size) clean(snippets[index].groupValues[1]) else ""
            if (title.isNotBlank()) {
                results.add(SearchResult(title, url, snippet))
            }
        }
        return results
    }

    private fun clean(raw: String): String {
        val noTags = raw.replace(Regex("<[^>]+>"), "")
        @Suppress("DEPRECATION")
        return Html.fromHtml(noTags, Html.FROM_HTML_MODE_LEGACY).toString().trim()
    }
}
