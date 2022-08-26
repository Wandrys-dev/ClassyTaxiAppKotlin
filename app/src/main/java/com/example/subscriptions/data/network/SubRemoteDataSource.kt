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

package com.example.subscriptions.data.network

import com.example.subscriptions.data.SubscriptionStatus
import com.example.subscriptions.data.network.firebase.ServerFunctions
import kotlinx.coroutines.flow.StateFlow

/**
 * Execute network requests on the network thread.
 * Fetch data from a remote server object.
 */
class SubRemoteDataSource private constructor(
    private val serverFunctions: ServerFunctions
) {
    /**
     * True when there are pending network requests.
     */
    val loading: StateFlow<Boolean>
        get() = serverFunctions.loading

    /**
     * Live Data with the basic content.
     */
    val basicContent = serverFunctions.basicContent

    /**
     * Live Data with the premium content.
     */
    val premiumContent = serverFunctions.premiumContent

    /**
     * GET basic content.
     */
    suspend fun updateBasicContent() = serverFunctions.updateBasicContent()

    /**
     * GET premium content.
     */
    suspend fun updatePremiumContent() = serverFunctions.updatePremiumContent()

    /**
     * GET request for subscription status.
     */
    suspend fun fetchSubscriptionStatus() = serverFunctions.fetchSubscriptionStatus()

    /**
     * POST request to register subscription.
     */
    suspend fun registerSubscription(
        product: String,
        purchaseToken: String
    ): List<SubscriptionStatus> {
        return serverFunctions.registerSubscription(
            product = product,
            purchaseToken = purchaseToken
        )
    }

    /**
     * POST request to transfer a subscription that is owned by someone else.
     */
    suspend fun postTransferSubscriptionSync(product: String, purchaseToken: String) {
        serverFunctions.transferSubscription(product = product, purchaseToken = purchaseToken)
    }

    /**
     * POST request to register an Instance ID.
     */
    suspend fun postRegisterInstanceId(instanceId: String) {
        serverFunctions.registerInstanceId(instanceId)
    }

    /**
     * POST request to unregister an Instance ID.
     */
    suspend fun postUnregisterInstanceId(instanceId: String) {
        serverFunctions.unregisterInstanceId(instanceId)
    }

    /**
     * POST request to acknowledge a subscription.
     */
    suspend fun postAcknowledgeSubscription(
        product: String,
        purchaseToken: String
    ): List<SubscriptionStatus> {
        return serverFunctions.acknowledgeSubscription(
            product = product,
            purchaseToken = purchaseToken
        )
    }


    companion object {
        @Volatile
        private var INSTANCE: SubRemoteDataSource? = null

        fun getInstance(
            callableFunctions: ServerFunctions
        ): SubRemoteDataSource =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: SubRemoteDataSource(callableFunctions).also { INSTANCE = it }
            }
    }
}
