/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.ads

import android.content.Context
import android.graphics.Typeface
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.google.android.gms.ads.nativead.NativeAdView

/**
 * Shared builders for small native-ad slots (news feed page, homepage
 * news section). The returned view starts GONE; callers show it only
 * after [GoogleSdk.loadNative] delivers an ad and [GoogleSdk.bindNativeAd]
 * binds it, so a failed load never leaves a gap.
 */
fun buildCompactNativeAdView(context: Context): NativeAdView {
    fun dp(value: Int) = (value * context.resources.displayMetrics.density).toInt()

    val nativeAdView = NativeAdView(context).apply {
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        )
    }
    val row = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(12), dp(8), dp(12), dp(8))
    }
    val iconView = ImageView(context).apply {
        layoutParams = LinearLayout.LayoutParams(dp(40), dp(40))
    }
    val textColumn = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
            marginStart = dp(8)
            marginEnd = dp(8)
        }
    }
    val headlineView = TextView(context).apply {
        setTypeface(typeface, Typeface.BOLD)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        maxLines = 1
    }
    val bodyView = TextView(context).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        maxLines = 1
    }
    textColumn.addView(headlineView)
    textColumn.addView(bodyView)
    val ctaView = TextView(context).apply {
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
