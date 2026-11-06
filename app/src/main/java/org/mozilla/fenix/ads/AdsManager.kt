/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.ads

import android.app.Activity
import android.app.Application
import android.graphics.Typeface
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.gms.ads.AdError
import com.google.android.gms.ads.AdListener
import com.google.android.gms.ads.AdView
import com.google.android.gms.ads.FullScreenContentCallback
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.interstitial.InterstitialAd
import com.google.android.gms.ads.nativead.NativeAd
import com.google.android.gms.ads.nativead.NativeAdView
import java.lang.ref.WeakReference
import java.util.WeakHashMap

/**
 * Puts all six AdMob formats on every page of the Iceraven fork.
 *
 * Installed once from [org.mozilla.fenix.FenixApplication]; it observes
 * every activity through [Application.ActivityLifecycleCallbacks]:
 *
 * - Banner + compact native ad + rewarded / rewarded-interstitial buttons are
 *   attached as a bottom overlay bar to every activity. Each format loads
 *   independently and hides on failure, so a failed load never leaves a
 *   blank slot or crashes.
 * - Interstitial shows when entering a new activity, at most once per
 *   3 minutes, and never right after a rewarded or app-open ad.
 * - App open shows once per cold start, on the first resumed activity.
 *
 * Everything here is fail-safe: any exception is logged and swallowed, so ads
 * can never break the app.
 */
object AdsManager {

    private const val TAG = "AdsManager"
    private const val INTERSTITIAL_COOLDOWN_MS = 3 * 60 * 1000L

    private var installed = false
    private var appOpenShown = false
    private var firstResumeDone = false
    private var lastInterstitialShownAt = 0L
    private var lastRewardedShownAt = 0L
    private var lastAppOpenShownAt = 0L
    private var preloadedInterstitial: InterstitialAd? = null
    private var interstitialLoading = false
    private var lastActivityRef: WeakReference<Activity>? = null

    private val holders = WeakHashMap<Activity, ActivityAds>()

    private class ActivityAds {
        var bannerAdView: AdView? = null
        var nativeAd: NativeAd? = null

        fun destroy() {
            try {
                bannerAdView?.destroy()
            } catch (e: Exception) {
                Log.w(TAG, "banner destroy failed: ${e.message}")
            }
            try {
                nativeAd?.destroy()
            } catch (e: Exception) {
                Log.w(TAG, "native destroy failed: ${e.message}")
            }
        }
    }

    fun install(application: Application) {
        if (installed) return
        installed = true
        try {
            application.registerActivityLifecycleCallbacks(callbacks)
            Log.d(TAG, "installed")
        } catch (e: Exception) {
            Log.w(TAG, "install failed: ${e.message}")
        }
    }

    private val callbacks = object : Application.ActivityLifecycleCallbacks {
        override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
            try {
                attachAdsBar(activity)
            } catch (e: Exception) {
                Log.w(TAG, "attach bar failed: ${e.message}")
            }
        }

        override fun onActivityStarted(activity: Activity) {
            try {
                maybeShowInterstitial(activity)
            } catch (e: Exception) {
                Log.w(TAG, "interstitial check failed: ${e.message}")
            }
        }

        override fun onActivityResumed(activity: Activity) {
            lastActivityRef = WeakReference(activity)
            if (!firstResumeDone) {
                firstResumeDone = true
                if (!appOpenShown) {
                    appOpenShown = true
                    try {
                        GoogleSdk.loadAppOpen(activity) { ad ->
                            if (ad != null) {
                                lastAppOpenShownAt = SystemClock.elapsedRealtime()
                                GoogleSdk.showAppOpen(activity, ad)
                            }
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "app open failed: ${e.message}")
                    }
                }
            }
        }

        override fun onActivityPaused(activity: Activity) = Unit
        override fun onActivityStopped(activity: Activity) = Unit
        override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit

        override fun onActivityDestroyed(activity: Activity) {
            try {
                holders.remove(activity)?.destroy()
            } catch (e: Exception) {
                Log.w(TAG, "destroy failed: ${e.message}")
            }
            if (lastActivityRef?.get() === activity) {
                lastActivityRef = null
            }
        }
    }

    private fun attachAdsBar(activity: Activity) {
        val content = activity.findViewById<ViewGroup>(android.R.id.content) ?: return
        val bar = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM,
            )
            setBackgroundColor(resolveBackgroundColor(activity))
        }
        ViewCompat.setOnApplyWindowInsetsListener(bar) { view, insets ->
            val navBar = insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom
            view.setPadding(0, 0, 0, navBar)
            insets
        }

        val nativeAdView = buildCompactNativeView(activity).apply {
            visibility = View.GONE
        }
        bar.addView(nativeAdView)

        val bannerContainer = FrameLayout(activity).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
        }
        bar.addView(bannerContainer)

        val buttonsRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
        }
        val rewardedButton = buildActionButton(activity, "看激励广告").apply {
            setOnClickListener { view ->
                view.isEnabled = false
                showRewarded(activity) { view.isEnabled = true }
            }
        }
        val rewardedInterstitialButton = buildActionButton(activity, "看激励插屏广告").apply {
            setOnClickListener { view ->
                view.isEnabled = false
                showRewardedInterstitial(activity) { view.isEnabled = true }
            }
        }
        buttonsRow.addView(rewardedButton)
        buttonsRow.addView(rewardedInterstitialButton)
        bar.addView(buttonsRow)

        content.addView(bar)

        val holder = ActivityAds()
        holders[activity] = holder
        try {
            holder.bannerAdView = GoogleSdk.loadBanner(activity, bannerContainer)?.apply {
                adListener = object : AdListener() {
                    override fun onAdFailedToLoad(error: LoadAdError) {
                        bannerContainer.visibility = View.GONE
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "banner load failed: ${e.message}")
            bannerContainer.visibility = View.GONE
        }
        try {
            GoogleSdk.loadNative(activity) { ad ->
                if (ad != null) {
                    holder.nativeAd = ad
                    GoogleSdk.bindNativeAd(ad, nativeAdView)
                    nativeAdView.visibility = View.VISIBLE
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "native load failed: ${e.message}")
        }
    }

    private fun buildCompactNativeView(activity: Activity): NativeAdView {
        val nativeAdView = NativeAdView(activity).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
        }
        val row = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(activity, 12), dp(activity, 8), dp(activity, 12), dp(activity, 8))
        }
        val iconView = ImageView(activity).apply {
            layoutParams = LinearLayout.LayoutParams(dp(activity, 40), dp(activity, 40))
        }
        val textColumn = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = dp(activity, 8)
                marginEnd = dp(activity, 8)
            }
        }
        val headlineView = TextView(activity).apply {
            setTypeface(typeface, Typeface.BOLD)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            maxLines = 1
        }
        val bodyView = TextView(activity).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            maxLines = 1
        }
        textColumn.addView(headlineView)
        textColumn.addView(bodyView)
        val ctaView = TextView(activity).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setTypeface(typeface, Typeface.BOLD)
        }
        row.addView(iconView)
        row.addView(textColumn)
        row.addView(ctaView)
        nativeAdView.addView(row)
        nativeAdView.iconView = iconView
        nativeAdView.headlineView = headlineView
        nativeAdView.bodyView = bodyView
        nativeAdView.callToActionView = ctaView
        return nativeAdView
    }

    private fun buildActionButton(activity: Activity, text: String): TextView {
        return TextView(activity).apply {
            this.text = text
            gravity = Gravity.CENTER
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setTypeface(typeface, Typeface.BOLD)
            setPadding(dp(activity, 12), dp(activity, 10), dp(activity, 12), dp(activity, 10))
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            val outValue = TypedValue()
            if (activity.theme.resolveAttribute(android.R.attr.selectableItemBackground, outValue, true)) {
                setBackgroundResource(outValue.resourceId)
            }
            isClickable = true
            isFocusable = true
        }
    }

    private fun showRewarded(activity: Activity, onDone: () -> Unit) {
        try {
            GoogleSdk.loadRewarded(activity) { ad ->
                if (ad == null) {
                    onDone()
                    return@loadRewarded
                }
                lastRewardedShownAt = SystemClock.elapsedRealtime()
                ad.fullScreenContentCallback = object : FullScreenContentCallback() {
                    override fun onAdDismissedFullScreenContent() {
                        onDone()
                    }

                    override fun onAdFailedToShowFullScreenContent(error: AdError) {
                        onDone()
                    }
                }
                GoogleSdk.showRewarded(activity, ad) { _, _ -> }
            }
        } catch (e: Exception) {
            Log.w(TAG, "rewarded failed: ${e.message}")
            onDone()
        }
    }

    private fun showRewardedInterstitial(activity: Activity, onDone: () -> Unit) {
        try {
            GoogleSdk.loadRewardedInterstitial(activity) { ad ->
                if (ad == null) {
                    onDone()
                    return@loadRewardedInterstitial
                }
                lastRewardedShownAt = SystemClock.elapsedRealtime()
                ad.fullScreenContentCallback = object : FullScreenContentCallback() {
                    override fun onAdDismissedFullScreenContent() {
                        onDone()
                    }

                    override fun onAdFailedToShowFullScreenContent(error: AdError) {
                        onDone()
                    }
                }
                GoogleSdk.showRewardedInterstitial(activity, ad) { _, _ -> }
            }
        } catch (e: Exception) {
            Log.w(TAG, "rewarded interstitial failed: ${e.message}")
            onDone()
        }
    }

    private fun maybeShowInterstitial(activity: Activity) {
        if (!firstResumeDone) return
        val now = SystemClock.elapsedRealtime()
        if (now - lastInterstitialShownAt < INTERSTITIAL_COOLDOWN_MS) return
        if (now - lastRewardedShownAt < INTERSTITIAL_COOLDOWN_MS) return
        if (now - lastAppOpenShownAt < INTERSTITIAL_COOLDOWN_MS) return
        val ad = preloadedInterstitial
        if (ad == null) {
            preloadInterstitial(activity)
            return
        }
        preloadedInterstitial = null
        lastInterstitialShownAt = now
        ad.fullScreenContentCallback = object : FullScreenContentCallback() {
            override fun onAdDismissedFullScreenContent() {
                lastActivityRef?.get()?.let { preloadInterstitial(it) }
            }
        }
        try {
            ad.show(activity)
        } catch (e: Exception) {
            Log.w(TAG, "interstitial show failed: ${e.message}")
        }
        preloadInterstitial(activity)
    }

    private fun preloadInterstitial(activity: Activity) {
        if (interstitialLoading) return
        interstitialLoading = true
        try {
            GoogleSdk.loadInterstitial(activity) { ad ->
                interstitialLoading = false
                if (ad != null) {
                    preloadedInterstitial = ad
                }
            }
        } catch (e: Exception) {
            interstitialLoading = false
            Log.w(TAG, "interstitial preload failed: ${e.message}")
        }
    }

    private fun resolveBackgroundColor(activity: Activity): Int {
        val typedValue = TypedValue()
        return if (activity.theme.resolveAttribute(android.R.attr.colorBackground, typedValue, true)) {
            typedValue.data
        } else {
            0xFFFFFFFF.toInt()
        }
    }

    private fun dp(activity: Activity, value: Int): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            value.toFloat(),
            activity.resources.displayMetrics,
        ).toInt()
    }
}
