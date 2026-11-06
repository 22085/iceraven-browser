/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.ads

import android.app.Activity
import android.content.Context
import android.util.Log
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import com.google.android.gms.ads.AdListener
import com.google.android.gms.ads.AdLoader
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.AdSize
import com.google.android.gms.ads.AdView
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.MobileAds
import com.google.android.gms.ads.OnUserEarnedRewardListener
import com.google.android.gms.ads.appopen.AppOpenAd
import com.google.android.gms.ads.interstitial.InterstitialAd
import com.google.android.gms.ads.interstitial.InterstitialAdLoadCallback
import com.google.android.gms.ads.nativead.NativeAd
import com.google.android.gms.ads.nativead.NativeAdOptions
import com.google.android.gms.ads.nativead.NativeAdView
import com.google.android.gms.ads.rewarded.RewardedAd
import com.google.android.gms.ads.rewarded.RewardedAdLoadCallback
import com.google.android.gms.ads.rewardedinterstitial.RewardedInterstitialAd
import com.google.android.gms.ads.rewardedinterstitial.RewardedInterstitialAdLoadCallback

/**
 * Google Ads (AdMob) for the Iceraven fork.
 *
 * All six AdMob formats are supported. For now these are Google's official
 * TEST IDs (no revenue, test ads only). Swap them for Iceraven-only formal
 * IDs before any release build: never reuse another app's formal IDs.
 * Everything here is fail-safe: AdMob initialization failure logs and skips
 * instead of crashing startup, and every load reports failure through its
 * callback instead of throwing.
 */
object GoogleSdk {

    private const val TAG = "GoogleSdk"

    /** Google official TEST AdMob App ID. */
    const val ADMOB_APP_ID = "ca-app-pub-3940256099942544~3347511713"

    /** Google official TEST banner unit ID. */
    const val BANNER_AD_UNIT_ID = "ca-app-pub-3940256099942544/6300978111"

    /** Google official TEST interstitial unit ID. */
    const val INTERSTITIAL_AD_UNIT_ID = "ca-app-pub-3940256099942544/1033173712"

    /** Google official TEST rewarded unit ID. */
    const val REWARDED_AD_UNIT_ID = "ca-app-pub-3940256099942544/5224354917"

    /** Google official TEST rewarded interstitial unit ID. */
    const val REWARDED_INTERSTITIAL_AD_UNIT_ID = "ca-app-pub-3940256099942544/5354046379"

    /** Google official TEST native unit ID. */
    const val NATIVE_AD_UNIT_ID = "ca-app-pub-3940256099942544/2247696110"

    /** Google official TEST app open unit ID. */
    const val APP_OPEN_AD_UNIT_ID = "ca-app-pub-3940256099942544/9257395921"

    fun initialize(context: Context) {
        try {
            MobileAds.initialize(context)
            Log.d(TAG, "AdMob initialized")
        } catch (e: Exception) {
            Log.w(TAG, "AdMob initialization failed, ads disabled: ${e.message}")
        }
    }

    /** Loads a banner into [parent]. Returns the AdView, or null if loading failed. */
    fun loadBanner(activity: Activity, parent: ViewGroup): AdView? {
        return try {
            val adView = AdView(activity).apply {
                setAdSize(AdSize.BANNER)
                adUnitId = BANNER_AD_UNIT_ID
            }
            parent.addView(adView)
            adView.loadAd(AdRequest.Builder().build())
            adView
        } catch (e: Exception) {
            Log.w(TAG, "banner failed: ${e.message}")
            null
        }
    }

    /** Loads an interstitial. [onLoaded] receives the ad, or null if loading failed. */
    fun loadInterstitial(activity: Activity, onLoaded: (InterstitialAd?) -> Unit) {
        try {
            InterstitialAd.load(
                activity,
                INTERSTITIAL_AD_UNIT_ID,
                AdRequest.Builder().build(),
                object : InterstitialAdLoadCallback() {
                    override fun onAdLoaded(ad: InterstitialAd) {
                        onLoaded(ad)
                    }

                    override fun onAdFailedToLoad(error: LoadAdError) {
                        Log.w(TAG, "interstitial failed to load: ${error.message}")
                        onLoaded(null)
                    }
                },
            )
        } catch (e: Exception) {
            Log.w(TAG, "interstitial failed: ${e.message}")
            onLoaded(null)
        }
    }

    /** Loads a rewarded ad. [onLoaded] receives the ad, or null if loading failed. */
    fun loadRewarded(activity: Activity, onLoaded: (RewardedAd?) -> Unit) {
        try {
            RewardedAd.load(
                activity,
                REWARDED_AD_UNIT_ID,
                AdRequest.Builder().build(),
                object : RewardedAdLoadCallback() {
                    override fun onAdLoaded(ad: RewardedAd) {
                        onLoaded(ad)
                    }

                    override fun onAdFailedToLoad(error: LoadAdError) {
                        Log.w(TAG, "rewarded failed to load: ${error.message}")
                        onLoaded(null)
                    }
                },
            )
        } catch (e: Exception) {
            Log.w(TAG, "rewarded failed: ${e.message}")
            onLoaded(null)
        }
    }

    /** Shows a loaded rewarded ad. [onRewarded] fires when the user earns the reward. */
    fun showRewarded(activity: Activity, ad: RewardedAd, onRewarded: (rewardType: String, rewardAmount: Int) -> Unit) {
        try {
            ad.show(activity, OnUserEarnedRewardListener { reward ->
                onRewarded(reward.type, reward.amount)
            })
        } catch (e: Exception) {
            Log.w(TAG, "rewarded failed to show: ${e.message}")
        }
    }

    /** Loads a rewarded interstitial ad. [onLoaded] receives the ad, or null if loading failed. */
    fun loadRewardedInterstitial(activity: Activity, onLoaded: (RewardedInterstitialAd?) -> Unit) {
        try {
            RewardedInterstitialAd.load(
                activity,
                REWARDED_INTERSTITIAL_AD_UNIT_ID,
                AdRequest.Builder().build(),
                object : RewardedInterstitialAdLoadCallback() {
                    override fun onAdLoaded(ad: RewardedInterstitialAd) {
                        onLoaded(ad)
                    }

                    override fun onAdFailedToLoad(error: LoadAdError) {
                        Log.w(TAG, "rewarded interstitial failed to load: ${error.message}")
                        onLoaded(null)
                    }
                },
            )
        } catch (e: Exception) {
            Log.w(TAG, "rewarded interstitial failed: ${e.message}")
            onLoaded(null)
        }
    }

    /** Shows a loaded rewarded interstitial ad. [onRewarded] fires when the user earns the reward. */
    fun showRewardedInterstitial(activity: Activity, ad: RewardedInterstitialAd, onRewarded: (rewardType: String, rewardAmount: Int) -> Unit) {
        try {
            ad.show(activity, OnUserEarnedRewardListener { reward ->
                onRewarded(reward.type, reward.amount)
            })
        } catch (e: Exception) {
            Log.w(TAG, "rewarded interstitial failed to show: ${e.message}")
        }
    }

    /** Loads a native ad. [onLoaded] receives the ad, or null if loading failed. */
    fun loadNative(activity: Activity, onLoaded: (NativeAd?) -> Unit) {
        try {
            AdLoader.Builder(activity, NATIVE_AD_UNIT_ID)
                .forNativeAd { ad -> onLoaded(ad) }
                .withNativeAdOptions(NativeAdOptions.Builder().build())
                .withAdListener(object : AdListener() {
                    override fun onAdFailedToLoad(error: LoadAdError) {
                        Log.w(TAG, "native failed to load: ${error.message}")
                        onLoaded(null)
                    }
                })
                .build()
                .loadAd(AdRequest.Builder().build())
        } catch (e: Exception) {
            Log.w(TAG, "native failed: ${e.message}")
            onLoaded(null)
        }
    }

    /**
     * Binds a loaded native ad into a [NativeAdView]. The view must already carry
     * the headline, body, call-to-action, icon, and media asset views.
     */
    fun bindNativeAd(nativeAd: NativeAd, adView: NativeAdView) {
        try {
            adView.headlineView?.let { (it as? TextView)?.text = nativeAd.headline }
            adView.bodyView?.let { (it as? TextView)?.text = nativeAd.body }
            adView.callToActionView?.let { (it as? TextView)?.text = nativeAd.callToAction }
            adView.iconView?.let { (it as? ImageView)?.setImageDrawable(nativeAd.icon?.drawable) }
            adView.mediaView?.let { mediaView ->
                nativeAd.mediaContent?.let { mediaView.mediaContent = it }
            }
            adView.setNativeAd(nativeAd)
        } catch (e: Exception) {
            Log.w(TAG, "binding native ad failed: ${e.message}")
        }
    }

    /** Loads an app open ad. [onLoaded] receives the ad, or null if loading failed. */
    fun loadAppOpen(activity: Activity, onLoaded: (AppOpenAd?) -> Unit) {
        try {
            AppOpenAd.load(
                activity,
                APP_OPEN_AD_UNIT_ID,
                AdRequest.Builder().build(),
                object : AppOpenAd.AppOpenAdLoadCallback() {
                    override fun onAdLoaded(ad: AppOpenAd) {
                        onLoaded(ad)
                    }

                    override fun onAdFailedToLoad(error: LoadAdError) {
                        Log.w(TAG, "app open failed to load: ${error.message}")
                        onLoaded(null)
                    }
                },
            )
        } catch (e: Exception) {
            Log.w(TAG, "app open failed: ${e.message}")
            onLoaded(null)
        }
    }

    /** Shows a loaded app open ad. */
    fun showAppOpen(activity: Activity, ad: AppOpenAd) {
        try {
            ad.show(activity)
        } catch (e: Exception) {
            Log.w(TAG, "app open failed to show: ${e.message}")
        }
    }
}
