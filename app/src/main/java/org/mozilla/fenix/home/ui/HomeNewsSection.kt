/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.home.ui

import android.app.Activity
import android.content.Intent
import android.view.View
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.mozilla.fenix.BrowserDirection
import org.mozilla.fenix.HomeActivity
import org.mozilla.fenix.ads.GoogleSdk
import org.mozilla.fenix.ads.buildCompactNativeAdView
import org.mozilla.fenix.compose.home.HomeSectionHeader
import org.mozilla.fenix.ext.components
import org.mozilla.fenix.news.NewsFeedActivity
import org.mozilla.fenix.news.NewsRepository

private const val HOME_NEWS_MAX_ITEMS = 10

/**
 * News feed section on the homepage: the latest headlines (Google News
 * Chinese RSS, same source as the Settings "新闻资讯" page) with a native
 * ad slot after every 5th item. Fail-open: if the feed cannot be loaded
 * (offline, blocked), the whole section stays hidden and the rest of the
 * homepage is unaffected. Tapping an item opens the article in the browser.
 */
@Composable
fun HomeNewsSection() {
    val context = LocalContext.current
    val activity = context as? Activity

    val news by produceState<List<NewsRepository.NewsItem>?>(initialValue = null) {
        value = withContext(Dispatchers.IO) {
            try {
                NewsRepository.fetchFeed(NewsRepository.categories[0].second).take(HOME_NEWS_MAX_ITEMS)
            } catch (e: Exception) {
                emptyList()
            }
        }
    }

    val items = news
    if (items.isNullOrEmpty()) return

    Column(modifier = Modifier.padding(horizontal = horizontalMargin)) {
        Spacer(modifier = Modifier.height(40.dp))

        HomeSectionHeader(
            headerText = "新闻资讯",
            buttonText = "更多",
            onButtonClick = {
                context.startActivity(Intent(context, NewsFeedActivity::class.java))
            },
        )

        Spacer(modifier = Modifier.height(8.dp))

        items.forEachIndexed { index, item ->
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        // openToBrowserAndLoad is deprecated and -Werror turns the
                        // warning into a build error; use openToBrowser +
                        // loadUrlOrSearch instead (the recommended replacement).
                        (context as? HomeActivity)?.let { homeActivity ->
                            homeActivity.openToBrowser(BrowserDirection.FromHome)
                            context.components.useCases.fenixBrowserUseCases.loadUrlOrSearch(
                                searchTermOrURL = item.link,
                                newTab = true,
                            )
                        }
                    }
                    .padding(vertical = 10.dp),
            ) {
                Text(
                    text = item.title,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = "${item.source} · ${item.date}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            if ((index + 1) % 5 == 0 && activity != null) {
                AndroidView(
                    factory = { ctx ->
                        buildCompactNativeAdView(ctx).apply {
                            visibility = View.GONE
                            try {
                                GoogleSdk.loadNative(activity) { ad ->
                                    if (ad != null) {
                                        GoogleSdk.bindNativeAd(ad, this)
                                        visibility = View.VISIBLE
                                    }
                                }
                            } catch (e: Exception) {
                                // Ad stays hidden; the news list is unaffected.
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}
