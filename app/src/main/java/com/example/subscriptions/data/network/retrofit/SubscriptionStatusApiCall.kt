package com.example.subscriptions.data.network.retrofit

import com.example.subscriptions.data.ContentResource
import com.example.subscriptions.data.SubscriptionStatus
import com.example.subscriptions.data.SubscriptionStatusList
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.PUT

/**
 * [SubscriptionStatusApiCall] defines the API endpoints that are called in [ServerFunctionsImpl].
 */
interface SubscriptionStatusApiCall {

    // Fetch Basic content.
    @GET("content_basic_v2")
    suspend fun fetchBasicContent(): ContentResource

    // Fetch Premium content.
    @GET("content_premium_v2")
    suspend fun fetchPremiumContent(): ContentResource

    // Fetch Subscription Status.
    @GET("subscription_status_v2")
    suspend fun fetchSubscriptionStatus(): Response<SubscriptionStatusList>

    // Registers Instance ID for Firebase Cloud Messaging.
    @PUT("instanceId_register_v2")
    suspend fun registerInstanceID(@Body instanceId: Map<String, String>): String

    // Unregisters Instance ID for Firebase Cloud Messaging.
    @PUT("instanceId_unregister_v2")
    suspend fun unregisterInstanceID(@Body instanceId: Map<String, String>): String

    // Registers subscription status to the server and get updated list of subscriptions
    @PUT("subscription_register_v2")
    suspend fun registerSubscription(@Body registerStatus: SubscriptionStatus):
            Response<SubscriptionStatusList>

    // Transfers subscription status to another account.
    @PUT("subscription_transfer_v2")
    suspend fun transferSubscription(@Body transferStatus: SubscriptionStatus)
            : SubscriptionStatusList

    @PUT("acknowledge_purchase")
    suspend fun acknowledgeSubscription(@Body acknowledge: SubscriptionStatus)
            : Response<SubscriptionStatusList>
}
