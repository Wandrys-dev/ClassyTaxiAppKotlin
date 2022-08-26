/**
 * Copyright 2018 Google LLC. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.example.subscriptions.ui
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.databinding.BindingAdapter
import com.bumptech.glide.Glide
import com.bumptech.glide.request.target.Target
import com.example.subscriptions.Constants
import com.example.subscriptions.R
import com.example.subscriptions.billing.isAccountHold
import com.example.subscriptions.billing.isBasicContent
import com.example.subscriptions.billing.isGracePeriod
import com.example.subscriptions.billing.isPaused
import com.example.subscriptions.billing.isPremiumContent
import com.example.subscriptions.billing.isPrepaid
import com.example.subscriptions.billing.isSubscriptionRestore
import com.example.subscriptions.billing.isTransferRequired
import com.example.subscriptions.data.ContentResource
import com.example.subscriptions.data.SubscriptionStatus
import com.example.subscriptions.utils.basicTextForSubscription
import com.example.subscriptions.utils.premiumTextForSubscription
import java.text.SimpleDateFormat
import java.util.Calendar

private const val TAG = "BindingAdapter"

/**
 * Update a loading progress bar when the status changes.
 *
 * When the network state changes, the binding adapter triggers this view in the layout XML.
 * See the layout XML files for the app:loadingProgressBar attribute.
 */
@BindingAdapter("loadingProgressBar")
fun ProgressBar.loadingProgressBar(loading: Boolean?) {
    visibility = if (loading == true) View.VISIBLE else View.GONE
}

/**
 * Load image with original size. If url is null, hide [ImageView]
 */

@BindingAdapter("loadImageOrHide")
fun ImageView.loadImageOrHide(url: String?) {
    if (url != null) {
        Log.d(TAG, "Loading image for content: $url")
        visibility = View.VISIBLE
        Glide.with(context)
            .load(url)
            .override(Target.SIZE_ORIGINAL)
            .into(this)
    } else {
        visibility = View.GONE
    }
}

/**
 * Update basic content when the URL changes.
 */
@BindingAdapter("basicContent")
fun TextView.updateBasicContent(basicContent: ContentResource?) {
    text = if (basicContent?.url != null)
        resources.getString(R.string.basic_auto_message)
    else
        resources.getString(R.string.no_basic_content)
}

/**
 * Update premium content on the Premium fragment when the URL changes.
 */
@BindingAdapter("premiumContent")
fun TextView.updatePremiumContent(premiumContent: ContentResource?) {
    text = if (premiumContent?.url != null)
        resources.getString(R.string.premium_content_text)
    else
        resources.getString(R.string.no_premium_content)
}

/**
 * Update subscription views on the Home fragment when the subscription changes.
 *
 * When the subscription changes, the binding adapter triggers this view in the layout XML.
 * See the layout XML files for the app:updateHomeViews attribute.
 */
@BindingAdapter("updateHomeViews")
fun LinearLayout.updateHomeViews(subscriptions: List<SubscriptionStatus>?) {
    val paywallMessage = findViewById<View>(R.id.home_paywall_message)
    val restoreMessage = findViewById<TextView>(R.id.home_restore_message)
    val gracePeriodMessage = findViewById<View>(R.id.home_grace_period_message)
    val transferMessage = findViewById<View>(R.id.home_transfer_message)
    val accountHoldMessage = findViewById<View>(R.id.home_account_hold_message)
    val accountPausedMessage = findViewById<View>(R.id.home_account_paused_message)
    val basicMessage = findViewById<View>(R.id.home_basic_message)
    val downgradeMessage = findViewById<View>(R.id.basic_downgrade_message)
    val prepaidMessage = findViewById<View>(R.id.prepaid_basic_content)
    val accountPausedMessageText = findViewById<TextView>(R.id.home_account_paused_message_text)
    // Set visibility assuming no subscription is available.
    // If a subscription is found that meets certain criteria, then the visibility of the paywall
    // will be changed to View.GONE.
    paywallMessage.visibility = View.VISIBLE
    // The remaining views start hidden. If a subscription is found that meets each criteria,
    // then the visibility will be changed to View.VISIBLE.
    listOf(
        restoreMessage, gracePeriodMessage, transferMessage, accountHoldMessage,
        accountPausedMessage, basicMessage, prepaidMessage, downgradeMessage
    ).forEach { it.visibility = View.GONE }
    // Update based on subscription information.
    subscriptions?.forEach { subscription ->
        if (subscription.isBasicContent && isSubscriptionRestore(subscription) && !subscription.isPrepaid) {
            Log.d(TAG, "restore VISIBLE")
            restoreMessage.run {
                visibility = View.VISIBLE
                val expiryDate = getHumanReadableDate(subscription.activeUntilMillisec)
                text = resources.getString(R.string.restore_message_with_date, expiryDate)
            }
            paywallMessage.visibility = View.GONE // Paywall gone.
        }
        if (isGracePeriod(subscription)) {
            Log.d(TAG, "grace period VISIBLE")
            gracePeriodMessage.visibility = View.VISIBLE
            paywallMessage.visibility = View.GONE // Paywall gone.
        }
        if (isTransferRequired(subscription) && subscription.product == Constants.BASIC_PRODUCT) {
            Log.d(TAG, "transfer VISIBLE")
            transferMessage.visibility = View.VISIBLE
            paywallMessage.visibility = View.GONE // Paywall gone.
        }
        if (isAccountHold(subscription)) {
            Log.d(TAG, "account hold VISIBLE")
            accountHoldMessage.visibility = View.VISIBLE
            paywallMessage.visibility = View.GONE // Paywall gone.
        }
        if (isPaused(subscription)) {
            Log.d(TAG, "account paused VISIBLE")
            accountPausedMessageText.run {
                val autoResumeDate = getHumanReadableDate(subscription.autoResumeTimeMillis)
                text = resources.getString(R.string.account_paused_message, autoResumeDate)
            }
            accountPausedMessage.visibility = View.VISIBLE
            paywallMessage.visibility = View.GONE // Paywall gone.
        }
        if (subscription.isBasicContent && !isPremiumContent(subscription) && !subscription.isPrepaid) {
            Log.d(TAG, "Downgrade VISIBLE")
            basicMessage.visibility = View.VISIBLE
            paywallMessage.visibility = View.GONE // Paywall gone.
        }
        if (subscription.isPrepaid && subscription.isBasicContent && !isPremiumContent(subscription)) {
            Log.d(TAG, "Basic Prepaid VISIBLE")
            prepaidMessage.visibility = View.VISIBLE
            paywallMessage.visibility = View.GONE
        }
        if (!subscription.isBasicContent && isPremiumContent(subscription)) {
            Log.d(TAG, "Downgrade VISIBLE")
            downgradeMessage.visibility = View.VISIBLE
            paywallMessage.visibility = View.GONE
        }
    }
}

/**
 * Update subscription views on the Premium fragment when the subscription changes.
 *
 * When the subscription changes, the binding adapter triggers this view in the layout XML.
 * See the layout XML files for the app:updatePremiumViews attribute.
 */
@BindingAdapter("updatePremiumViews")
fun LinearLayout.updatePremiumViews(subscriptions: List<SubscriptionStatus>?) {
    val paywallMessage = findViewById<View>(R.id.premium_paywall_message)
    val restoreMessage = findViewById<TextView>(R.id.premium_restore_message)
    val gracePeriodMessage = findViewById<TextView>(R.id.premium_grace_period_message)
    val transferMessage = findViewById<View>(R.id.premium_transfer_message)
    val accountHoldMessage = findViewById<View>(R.id.premium_account_hold_message)
    val accountPausedMessage = findViewById<View>(R.id.premium_account_paused_message)
    val premiumContent = findViewById<View>(R.id.premium_premium_content)
    val upgradeMessage = findViewById<View>(R.id.premium_upgrade_message)
    val prepaidMessage = findViewById<View>(R.id.prepaid_premium_content)
    val accountPausedMessageText = findViewById<TextView>(R.id.premium_account_paused_message_text)
    // Set visibility assuming no subscription is available.
    // If a subscription is found that meets certain criteria, then the visibility of the paywall
    // will be changed to View.GONE.
    paywallMessage.visibility = View.VISIBLE
    // The remaining views start hidden. If a subscription is found that meets each criteria,
    // then the visibility will be changed to View.VISIBLE.
    listOf(
        restoreMessage, gracePeriodMessage, transferMessage, accountHoldMessage,
        accountPausedMessage, premiumContent, upgradeMessage, prepaidMessage
    ).forEach { it.visibility = View.GONE }
    // The Upgrade button should appear if the user has a basic subscription, but does not
    // have a premium subscription. This variable keeps track of whether a premium subscription
    // has been found when looking through the list of subscriptions.
    // Update based on subscription information.
    subscriptions?.let {
        for (subscription in subscriptions) {
            if (isPremiumContent(subscription) && isSubscriptionRestore(subscription) && !subscription.isPrepaid) {
                Log.d(TAG, "restore VISIBLE")
                restoreMessage.run {
                    visibility = View.VISIBLE
                    val expiryDate = getHumanReadableDate(subscription.activeUntilMillisec)
                    text = resources.getString(R.string.restore_message_with_date, expiryDate)
                }
                paywallMessage.visibility = View.GONE // Paywall gone.
            }
            if (isGracePeriod(subscription)) {
                Log.d(TAG, "grace period VISIBLE")
                gracePeriodMessage.visibility = View.VISIBLE
                paywallMessage.visibility = View.GONE // Paywall gone.
            }
            if (isTransferRequired(subscription) && subscription.product == Constants.PREMIUM_PRODUCT) {
                Log.d(TAG, "transfer VISIBLE")
                transferMessage.visibility = View.VISIBLE
                paywallMessage.visibility = View.GONE // Paywall gone.
            }
            if (isAccountHold(subscription)) {
                Log.d(TAG, "account hold VISIBLE")
                accountHoldMessage.visibility = View.VISIBLE
                paywallMessage.visibility = View.GONE // Paywall gone.
            }
            if (isPaused(subscription)) {
                Log.d(TAG, "account paused VISIBLE")
                accountPausedMessageText.run {
                    val autoResumeDate = getHumanReadableDate(subscription.autoResumeTimeMillis)
                    text = resources.getString(R.string.account_paused_message, autoResumeDate)
                }
                accountPausedMessage.visibility = View.VISIBLE
                paywallMessage.visibility = View.GONE // Paywall gone.
            }
            // The upgrade message must be shown if there is a basic subscription
            // and there are zero premium subscriptions. We need to keep track of the premium
            // subscriptions and hide the upgrade message if we find any.
            if (isPremiumContent(subscription) && !subscription.isPrepaid && !subscription.isBasicContent) {
                Log.d(TAG, "premium VISIBLE")
                premiumContent.visibility = View.VISIBLE
                paywallMessage.visibility = View.GONE // Paywall gone.
            }
            if (isPremiumContent(subscription) && !subscription.isBasicContent && subscription.isPrepaid) {
                Log.d(TAG, "Premium Prepaid VISIBLE")
                prepaidMessage.visibility = View.VISIBLE
                paywallMessage.visibility = View.GONE
            }
            if (!isPremiumContent(subscription) && subscription.isBasicContent) {
                Log.d(TAG, "Upgrade VISIBLE")
                upgradeMessage.visibility = View.VISIBLE
                paywallMessage.visibility = View.GONE
            }
        }
    }
}

/**
 * Update views on the Settings fragment when the subscription changes.
 *
 * When the subscription changes, the binding adapter triggers this view in the layout XML.
 * See the layout XML files for the app:updateSettingsViews attribute.
 * TODO(232165789): update method to update settings view
 */
@BindingAdapter("updateSettingsViews")
fun LinearLayout.updateSettingsViews(subscriptions: List<SubscriptionStatus>?) {

}

/**
 * Get a readable date from the time in milliseconds.
 */
fun getHumanReadableDate(milliSeconds: Long): String {
    val formatter = SimpleDateFormat.getDateInstance()
    val calendar = Calendar.getInstance()
    calendar.timeInMillis = milliSeconds
    if (milliSeconds == 0L) {
        Log.d(TAG, "Suspicious time: 0 milliseconds.")
    } else {
        Log.d(TAG, "Milliseconds: $milliSeconds")
    }
    return formatter.format(calendar.time)
}