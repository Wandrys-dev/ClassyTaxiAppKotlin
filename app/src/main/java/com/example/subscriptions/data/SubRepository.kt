/*
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

package com.example.subscriptions.data

import android.util.Log
import com.android.billingclient.api.Purchase
import com.example.subscriptions.Constants
import com.example.subscriptions.billing.BillingClientLifecycle
import com.example.subscriptions.data.disk.SubLocalDataSource
import com.example.subscriptions.data.network.SubRemoteDataSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

/**
 * Repository handling the work with subscriptions.
 */
class SubRepository private constructor(
    private val localDataSource: SubLocalDataSource,
    private val remoteDataSource: SubRemoteDataSource,
    private val billingClientLifecycle: BillingClientLifecycle,
    private val externalScope: CoroutineScope =
        CoroutineScope(SupervisorJob() + Dispatchers.Default)
) {

    /**
     * True when there are pending network requests.
     */
    val loading: StateFlow<Boolean> = remoteDataSource.loading

    /**
     * [MutableStateFlow] to coordinate updates from the database and the network.
     * Intended to be collected by ViewModel
     */
    val subscriptions: StateFlow<List<SubscriptionStatus>> =
        localDataSource.getSubscriptions()
            .stateIn(externalScope, SharingStarted.WhileSubscribed(), emptyList())

    private val _basicContent = MutableStateFlow<ContentResource?>(null)
    private val _premiumContent = MutableStateFlow<ContentResource?>(null)

    /**
     * [StateFlow] with the basic content.
     * Intended to be collected by ViewModel
     */
    val basicContent = _basicContent.asStateFlow()

    /**
     * [StateFlow] with the premium content.
     * Intended to be collected by ViewModel
     */
    val premiumContent = _premiumContent.asStateFlow()

    init {
        // Update content from the remote server.
        // We are using a MutableStateFlow so that we can clear the data immediately
        // when the subscription changes.
        externalScope.launch {
            remoteDataSource.basicContent.collect {
                _basicContent.emit(it)
            }
        }
        externalScope.launch {
            remoteDataSource.premiumContent.collect {
                _premiumContent.emit(it)
            }
        }

        // When the list of purchases changes, we need to update the subscription status
        // to indicate whether the subscription is local or not. It is local if the
        // the Google Play Billing APIs return a Purchase record for the product. It is not
        // local if there is no record of the subscription on the device.
        externalScope.launch {
            billingClientLifecycle.purchases.collect { purchases ->
                Log.i(TAG, "Collected purchases...")
                val currentSubscriptions = subscriptions.value
                val hasChanged = updateLocalPurchaseTokens(currentSubscriptions, purchases)
                // We only need to update the database if [isLocalPurchase] field needs
                // to be changed.
                if (hasChanged) {
                    localDataSource.updateSubscriptions(currentSubscriptions)
                }
                purchases.forEach {
                    registerSubscription(it.products.first(), it.purchaseToken)
                }
            }
        }
    }

    suspend fun updateSubscriptionsFromNetwork(remoteSubscriptions: List<SubscriptionStatus>?) {
        Log.i(TAG, "Updating subscriptions from remote: ${remoteSubscriptions?.size}")

        val currentSubscriptions = subscriptions.value
        val purchases = billingClientLifecycle.purchases.value
        val mergedSubscriptions =
            mergeSubscriptionsAndPurchases(currentSubscriptions, remoteSubscriptions, purchases)

        // Acknowledge the subscription if it is not.
        remoteSubscriptions?.let {
            acknowledgeRegisteredPurchaseTokens(it)
        }

        // Store the subscription information when it changes.
        localDataSource.updateSubscriptions(mergedSubscriptions)

        // Update the content when the subscription changes.
        remoteSubscriptions?.let {
            // Figure out which content we need to fetch.
            var updateBasic = false
            var updatePremium = false
            for (subscription in it) {
                when (subscription.product) {
                    Constants.BASIC_PRODUCT -> {
                        updateBasic = true
                    }
                    Constants.PREMIUM_PRODUCT -> {
                        updatePremium = true
                        // Premium subscribers get access to basic content as well.
                        updateBasic = true
                    }
                }
            }

            if (updateBasic) {
                remoteDataSource.updateBasicContent()
            } else {
                // If we no longer own this content, clear it from the UI.
                _basicContent.emit(null)
            }
            if (updatePremium) {
                remoteDataSource.updatePremiumContent()
            } else {
                // If we no longer own this content, clear it from the UI.
                _premiumContent.emit(null)
            }
        }
    }

    /**
     * Acknowledge subscriptions that have been registered by the server.
     * Returns true if the param list was empty or all acknowledgement were succeeded
     */
    /**
     * Acknowledge subscriptions that have been registered by the server
     * and update local data source.
     */
    private suspend fun acknowledgeRegisteredPurchaseTokens(
        remoteSubscriptions: List<SubscriptionStatus>
    ) {
        remoteSubscriptions.forEach { sub ->
            if (!sub.isAcknowledged) {
                return withContext(externalScope.coroutineContext) {
                    try {
                        val acknowledgedSubs =
                            sub.purchaseToken?.let {
                                sub.product?.let { it1 ->
                                    acknowledgeSubscription(
                                        it1, it
                                    )
                                }
                            }
                        if (acknowledgedSubs != null) {
                            localDataSource.updateSubscriptions(acknowledgedSubs)
                        } else {
                            localDataSource.updateSubscriptions(listOf())
                        }
                    } catch (e: Exception) {
                        throw e
                    }
                }
            }
        }
    }

    /**
     * Merge the previous subscriptions and new subscriptions by looking at on-device purchases.
     *
     * We want to return the list of new subscriptions, possibly with some modifications
     * based on old subscriptions and the on-devices purchases from Google Play Billing.
     * Old subscriptions should be retained if they are owned by someone else (subAlreadyOwned)
     * and the purchase token for the subscription is still on this device.
     */
    private fun mergeSubscriptionsAndPurchases(
        oldSubscriptions: List<SubscriptionStatus>?,
        newSubscriptions: List<SubscriptionStatus>?,
        purchases: List<Purchase>?
    ): List<SubscriptionStatus> {
        return ArrayList<SubscriptionStatus>().apply {
            if (purchases != null) {
                // Record which purchases are local and can be managed on this device.
                updateLocalPurchaseTokens(newSubscriptions, purchases)
            }
            if (newSubscriptions != null) {
                addAll(newSubscriptions)
            }
            // Find old subscriptions that are in purchases but not in new subscriptions.
            if (purchases != null && oldSubscriptions != null) {
                for (oldSubscription in oldSubscriptions) {
                    if (oldSubscription.subAlreadyOwned && oldSubscription.isLocalPurchase) {
                        // This old subscription was previously marked as "already owned" by
                        // another user. It should be included in the output if the product
                        // and purchase token match their previous value.
                        for (purchase in purchases) {
                            if (purchase.products[0] == oldSubscription.product &&
                                purchase.purchaseToken == oldSubscription.purchaseToken
                            ) {
                                // The old subscription that was already owned subscription should
                                // be added to the new subscriptions.
                                // Look through the new subscriptions to see if it is there.
                                var foundNewSubscription = false
                                newSubscriptions?.let {
                                    for (newSubscription in it) {
                                        if (newSubscription.product == oldSubscription.product) {
                                            foundNewSubscription = true
                                        }
                                    }
                                }
                                if (!foundNewSubscription) {
                                    // The old subscription should be added to the output.
                                    // It matches a local purchase.
                                    add(oldSubscription)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * Modify the subscriptions isLocalPurchase field based on the list of local purchases.
     * Return true if any of the values changed.
     */
    private fun updateLocalPurchaseTokens(
        subscriptions: List<SubscriptionStatus>?,
        purchases: List<Purchase>?
    ): Boolean {
        var hasChanged = false
        subscriptions?.forEach { subscription ->
            var isLocalPurchase = false
            var purchaseToken = subscription.purchaseToken
            purchases?.forEach { purchase ->
                if (subscription.product == purchase.products[0]) {
                    isLocalPurchase = true
                    purchaseToken = purchase.purchaseToken
                }
            }
            if (subscription.isLocalPurchase != isLocalPurchase) {
                subscription.isLocalPurchase = isLocalPurchase
                subscription.purchaseToken = purchaseToken
                hasChanged = true
            }
        }
        return hasChanged
    }

    /**
     * Fetch subscriptions from the server and update local data source.
     */
    suspend fun fetchSubscriptions(): Result<Unit> =
        externalScope.async {
            try {
                val subscriptions = remoteDataSource.fetchSubscriptionStatus()
                localDataSource.updateSubscriptions(subscriptions)
                Result.success(Unit)
            } catch (e: Exception) {
                Result.failure(e)
            }
        }.await()

    /**
     * Register subscription to this account and update local data source.
     */
    suspend fun registerSubscription(product: String, purchaseToken: String): Result<Unit> {
        return externalScope.async {
            try {
                val subs =
                    remoteDataSource.registerSubscription(
                        product = product,
                        purchaseToken = purchaseToken
                    )
                updateSubscriptionsFromNetwork(subs)
                Result.success(Unit)
            } catch (e: Exception) {
                Result.failure(e)
            }
        }.await()
    }

    /**
     * Transfer subscription to this account and update local data source.
     */
    suspend fun transferSubscription(product: String, purchaseToken: String) {
        externalScope.launch {
            remoteDataSource.postTransferSubscriptionSync(
                product = product,
                purchaseToken = purchaseToken
            )
        }.join()
    }

    /**
     * Register Instance ID.
     */
    fun registerInstanceId(instanceId: String) {
        externalScope.launch {
            try {
                remoteDataSource.postRegisterInstanceId(instanceId)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to register the instance ID - ${e.localizedMessage}")
            }
        }
    }

    /**
     * Unregister Instance ID.
     */
    fun unregisterInstanceId(instanceId: String) {
        externalScope.launch {
            try {
                remoteDataSource.postUnregisterInstanceId(instanceId)
            } catch (e: Exception) {
                // In general, this should trigger error event (eg. by using Result object),
                // then ViewModel should collect it and show appropriate error to user.
                Log.e(TAG, "Failed to register the instance ID - ${e.localizedMessage}")
            }
        }
    }

    /**
     * Acknowledge subscription to this account.
     */
    private suspend fun acknowledgeSubscription(product: String, purchaseToken: String)
            : List<SubscriptionStatus>? {
        val result = remoteDataSource.postAcknowledgeSubscription(
            product = product,
            purchaseToken = purchaseToken
        )
        return suspendCoroutine { continuation ->
            continuation.resume(result)
        }
    }

    /**
     * Delete local user data when the user signs out.
     */
    suspend fun deleteLocalUserData() {
        withContext(externalScope.coroutineContext) {
            localDataSource.deleteLocalUserData()
            _basicContent.emit(null)
            _premiumContent.emit(null)
        }
    }

    companion object {
        private const val TAG = "SubRepository"

        @Volatile
        private var INSTANCE: SubRepository? = null

        fun getInstance(
            localDataSource: SubLocalDataSource,
            subRemoteDataSource: SubRemoteDataSource,
            billingClientLifecycle: BillingClientLifecycle,
            externalScope: CoroutineScope =
                CoroutineScope(SupervisorJob() + Dispatchers.Default)
        ): SubRepository =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: SubRepository(
                    localDataSource, subRemoteDataSource, billingClientLifecycle, externalScope
                )
                    .also { INSTANCE = it }
            }
    }
}
