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

package com.example.subscriptions.ui

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.subscriptions.SubApp
import com.example.subscriptions.data.SubRepository
import com.example.subscriptions.data.SubscriptionStatus
import com.google.android.gms.tasks.OnCompleteListener
import com.google.firebase.messaging.FirebaseMessaging
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class SubscriptionStatusViewModel(
    application: Application,
) : AndroidViewModel(application) {

    // TODO this should be moved to constructor param and injected by Hilt
    private val repository: SubRepository = (application as SubApp).repository

    /**
     * True when there are pending network requests.
     */
    val loading: StateFlow<Boolean> = repository.loading

    /**
     * Subscriptions LiveData.
     */
    val subscriptions: StateFlow<List<SubscriptionStatus>> = repository.subscriptions

    /**
     * StateFlow with the basic content.
     */
    val basicContent = repository.basicContent

    /**
     * StateFlow with the premium content.
     */
    val premiumContent = repository.premiumContent

    // TODO show UI status in View
    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage = _errorMessage.asStateFlow()

    fun unregisterInstanceId() {
        // Unregister current Instance ID before the user signs out.
        // This is an authenticated call, so you cannot do this after the sign-out has completed.
        instanceIdToken?.let {
            repository.unregisterInstanceId(it)
        }
    }

    fun userChanged() {
        viewModelScope.launch {
            repository.deleteLocalUserData()
            FirebaseMessaging.getInstance().token.addOnCompleteListener(OnCompleteListener { task ->
                if (!task.isSuccessful) {
                    Log.w(TAG, "Fetching FCM registration token failed", task.exception)
                    return@OnCompleteListener
                }
                val token = task.result
                if (token != null) {
                    registerInstanceId(token)
                }
            })
            repository.fetchSubscriptions()
        }
    }

    fun manualRefresh() {
        viewModelScope.launch {
            val result = repository.fetchSubscriptions()
            if (result.isFailure) {
                _errorMessage.emit(result.exceptionOrNull()?.localizedMessage)
            }
        }
    }

    /**
     * Keep track of the last Instance ID to be registered, so that it
     * can be unregistered when the user signs out.
     */
    private var instanceIdToken: String? = null

    /**
     * Register Instance ID.
     */
    private fun registerInstanceId(token: String) {
        repository.registerInstanceId(token)
        // Keep track of the Instance ID so that it can be unregistered.
        instanceIdToken = token
    }

    /**
     * Register a new subscription.
     */
    fun registerSubscription(product: String, purchaseToken: String) {
        viewModelScope.launch {
            val result = repository.registerSubscription(product, purchaseToken)
            if (result.isFailure) {
                _errorMessage.emit(result.exceptionOrNull()?.localizedMessage)
            }
        }
    }

    /**
     * Transfer the subscription to this account.
     */
    fun transferSubscriptions() {
        Log.d(TAG, "transferSubscriptions")
        viewModelScope.launch {
            subscriptions.value.forEach { subscription ->
                val product = subscription.product
                val purchaseToken = subscription.purchaseToken
                if (product != null && purchaseToken != null) {
                    repository.transferSubscription(
                        product = product, purchaseToken = purchaseToken)
                }
            }
        }
    }

    companion object {
        private const val TAG = "SubViewModel"
    }
}
