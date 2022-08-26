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

package com.example.subscriptions.data.network.firebase

import com.example.subscriptions.data.ContentResource
import com.example.subscriptions.data.SubscriptionStatus
import com.example.subscriptions.data.network.retrofit.ServerFunctionsImpl
import kotlinx.coroutines.flow.StateFlow

/**
 * Interface to perform the remote API calls.
 *
 * Server updates for loading, basicContent, premiumContent will be communicated
 * through [StateFlow] variables.
 */
interface ServerFunctions {

    /**
     * True when there are pending network requests.
     */
    val loading: StateFlow<Boolean>

    /**
     * The basic content URL.
     */
    val basicContent: StateFlow<ContentResource?>

    /**
     * The premium content URL.
     */
    val premiumContent: StateFlow<ContentResource?>

    /**
     * Fetch basic content and post results to [basicContent].
     * This will fail if the user does not have a basic subscription.
     */
    suspend fun updateBasicContent()

    /**
     * Fetch premium content and post results to [premiumContent].
     * This will fail if the user does not have a premium subscription.
     */
    suspend fun updatePremiumContent()

    /**
     * Fetches subscription data from the server.
     */
    suspend fun fetchSubscriptionStatus(): List<SubscriptionStatus>

    /**
     * Register a subscription with the server and return results.
     */
    suspend fun registerSubscription(
        product: String,
        purchaseToken: String
    ): List<SubscriptionStatus>

    /**
     * Transfer subscription to this account posts
     */
    suspend fun transferSubscription(
        product: String,
        purchaseToken: String
    ): List<SubscriptionStatus>

    /**
     * Register Instance ID when the user signs in or the token is refreshed.
     */
    suspend fun registerInstanceId(instanceId: String)

    /**
     * Unregister when the user signs out.
     */
    suspend fun unregisterInstanceId(instanceId: String)

    /**
     * Send a purchase object to server for acknowledgement.
     */
    suspend fun acknowledgeSubscription(
        product: String,
        purchaseToken: String
    ): List<SubscriptionStatus>

    companion object {
        @Volatile
        private var INSTANCE: ServerFunctions? = null

        fun getInstance(): ServerFunctions =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: ServerFunctionsImpl().also { INSTANCE = it }
            }
    }
}
