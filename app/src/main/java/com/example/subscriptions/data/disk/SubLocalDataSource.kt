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

package com.example.subscriptions.data.disk

import com.example.subscriptions.data.SubscriptionStatus
import com.example.subscriptions.data.disk.db.SubscriptionStatusDao
import kotlinx.coroutines.flow.Flow

class SubLocalDataSource private constructor(
    private val subscriptionStatusDao: SubscriptionStatusDao
) {
    /**
     * Get the list of subscriptions from the DAO
     */
    fun getSubscriptions(): Flow<List<SubscriptionStatus>> = subscriptionStatusDao.getAll()

    suspend fun updateSubscriptions(subscriptions: List<SubscriptionStatus>) {
        // Delete existing subscriptions.
        subscriptionStatusDao.deleteAll()
        // Put new subscriptions data into localDataSource.
        subscriptionStatusDao.insertAll(subscriptions)
    }

    /**
     * Delete local user data when the user signs out.
     */
    suspend fun deleteLocalUserData() = updateSubscriptions(listOf())

    companion object {

        @Volatile
        private var INSTANCE: SubLocalDataSource? = null

        fun getInstance(subscriptionStatusDao: SubscriptionStatusDao): SubLocalDataSource =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: SubLocalDataSource(subscriptionStatusDao).also { INSTANCE = it }
            }
    }

}