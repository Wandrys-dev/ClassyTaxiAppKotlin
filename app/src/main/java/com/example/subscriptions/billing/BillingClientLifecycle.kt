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

package com.example.subscriptions.billing

import android.app.Activity
import android.content.Context
import android.util.Log
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.MutableLiveData
import com.android.billingclient.api.AcknowledgePurchaseParams
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.ProductDetailsResponseListener
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.PurchasesResponseListener
import com.android.billingclient.api.PurchasesUpdatedListener
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryPurchasesParams
import com.example.subscriptions.Constants
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.math.pow

class BillingClientLifecycle private constructor(
    private val applicationContext: Context,
    private val externalScope: CoroutineScope =
        CoroutineScope(SupervisorJob() + Dispatchers.Default)
) : DefaultLifecycleObserver, PurchasesUpdatedListener, BillingClientStateListener,
    ProductDetailsResponseListener, PurchasesResponseListener {

    private val _purchases = MutableStateFlow<List<Purchase>>(emptyList())

    /**
     * Purchases are collectable. This list will be updated when the Billing Library
     * detects new or existing purchases.
     */
    val purchases = _purchases.asStateFlow()

    /**
     * ProductDetails for all known products.
     */
    val productsWithProductDetails = MutableLiveData<Map<String, ProductDetails>>()

    /**
     * Instantiate a new BillingClient instance.
     */
    private lateinit var billingClient: BillingClient

    override fun onCreate(owner: LifecycleOwner) {
        Log.d(TAG, "ON_CREATE")
        // Create a new BillingClient in onCreate().
        // Since the BillingClient can only be used once, we need to create a new instance
        // after ending the previous connection to the Google Play Store in onDestroy().
        billingClient = BillingClient.newBuilder(applicationContext)
            .setListener(this)
            .enablePendingPurchases() // Not used for subscriptions.
            .build()
        if (!billingClient.isReady) {
            Log.d(TAG, "BillingClient: Start connection...")
            billingClient.startConnection(this)
        }
    }

    override fun onDestroy(owner: LifecycleOwner) {
        Log.d(TAG, "ON_DESTROY")
        if (billingClient.isReady) {
            Log.d(TAG, "BillingClient can only be used once -- closing connection")
            // BillingClient can only be used once.
            // After calling endConnection(), we must create a new BillingClient.
            billingClient.endConnection()
        }
    }

    override fun onBillingSetupFinished(billingResult: BillingResult) {
        val responseCode = billingResult.responseCode
        val debugMessage = billingResult.debugMessage
        Log.d(TAG, "onBillingSetupFinished: $responseCode $debugMessage")
        if (responseCode == BillingClient.BillingResponseCode.OK) {
            // The billing client is ready.
            // You can query product details and purchases here.
            queryProductDetails()
            queryPurchases()
        }
    }

    override fun onBillingServiceDisconnected() {
        Log.d(TAG, "onBillingServiceDisconnected")
        // TODO: Try connecting again with exponential backoff.
        // billingClient.startConnection(this)
    }

    /**
     * In order to make purchases, you need the [ProductDetails] for the item or subscription.
     * This is an asynchronous call that will receive a result in [onProductDetailsResponse].
     *
     * queryProductDetails uses method calls from GPBL 5.0.0. PBL5, released in May 2022,
     * is backwards compatible with previous versions.
     * To learn more about this you can read https://developer.android.com/google/play/billing/compatibility
     */
    private fun queryProductDetails() {
        Log.d(TAG, "queryProductDetails")
        val params = QueryProductDetailsParams.newBuilder()

        val productList: MutableList<QueryProductDetailsParams.Product> = arrayListOf()
        for (product in LIST_OF_PRODUCTS) {
            productList.add(
                QueryProductDetailsParams.Product.newBuilder()
                    .setProductId(product)
                    .setProductType(BillingClient.ProductType.SUBS)
                    .build()
            )
        }

        params.setProductList(productList).let { productDetailsParams ->
            Log.i(TAG, "queryProductDetailsAsync")
            billingClient.queryProductDetailsAsync(productDetailsParams.build(), this)
        }

    }

    /**
     * Receives the result from [queryProductDetails].
     *
     * Store the ProductDetails and post them in the [productsWithProductDetails]. This allows other parts
     * of the app to use the [ProductDetails] to show product information and make purchases.
     *
     * onProductDetailsResponse() uses method calls from GPBL 5.0.0. PBL5, released in May 2022,
     * is backwards compatible with previous versions.
     * To learn more about this you can read https://developer.android.com/google/play/billing/compatibility
     */
    override fun onProductDetailsResponse(
        billingResult: BillingResult,
        productDetailsList: MutableList<ProductDetails>
    ) {
        val response = BillingResponse(billingResult.responseCode)
        val debugMessage = billingResult.debugMessage
        when {
            response.isOk -> {
                val expectedProductDetailsCount = LIST_OF_PRODUCTS.size
                if (productDetailsList.isNullOrEmpty()) {
                    productsWithProductDetails.postValue(emptyMap())
                    Log.e(
                        TAG, "onProductDetailsResponse: " +
                                "Expected ${expectedProductDetailsCount}, " +
                                "Found null ProductDetails. " +
                                "Check to see if the products you requested are correctly published " +
                                "in the Google Play Console."
                    )
                } else {
                    productsWithProductDetails.postValue(HashMap<String, ProductDetails>().apply {
                        for (productDetails in productDetailsList) {
                            put(productDetails.productId, productDetails)
                        }
                    }.also { postedValue ->
                        val productDetailsCount = postedValue.size
                        if (productDetailsCount == expectedProductDetailsCount) {
                            Log.i(
                                TAG,
                                "onProductDetailsResponse: Found $productDetailsCount ProductDetails"
                            )
                        } else {
                            Log.e(
                                TAG, "onProductDetailsResponse: " +
                                        "Expected ${expectedProductDetailsCount}, " +
                                        "Found $productDetailsCount ProductDetails. " +
                                        "Check to see if the products you requested are correctly published " +
                                        "in the Google Play Console."
                            )
                        }
                        Log.wtf(TAG, "productsWithProductDetails: $productsWithProductDetails")
                    }
                    )
                }
            }
            response.isTerribleFailure -> {
                // These response codes are not expected.
                Log.wtf(TAG, "onProductDetailsResponse: ${response.code} $debugMessage")
            }
            else -> {
                Log.e(TAG, "onProductDetailsResponse: ${response.code} $debugMessage")
            }

        }
    }


    /**
     * Query Google Play Billing for existing purchases.
     *
     * New purchases will be provided to the PurchasesUpdatedListener.
     * You still need to check the Google Play Billing API to know when purchase tokens are removed.
     */
    fun queryPurchases() {
        if (!billingClient.isReady) {
            Log.e(TAG, "queryPurchases: BillingClient is not ready")
            billingClient.startConnection(this)
        }
        billingClient.queryPurchasesAsync(
            QueryPurchasesParams.newBuilder()
                .setProductType(BillingClient.ProductType.SUBS)
                .build(), this
        )
    }

    /**
     * Callback from the billing library when queryPurchasesAsync is called.
     */
    override fun onQueryPurchasesResponse(
        billingResult: BillingResult,
        purchasesList: MutableList<Purchase>
    ) {
        processPurchases(purchasesList)
    }

    /**
     * Called by the Billing Library when new purchases are detected.
     */
    override fun onPurchasesUpdated(
        billingResult: BillingResult,
        purchases: MutableList<Purchase>?
    ) {
        val responseCode = billingResult.responseCode
        val debugMessage = billingResult.debugMessage
        Log.d(TAG, "onPurchasesUpdated: $responseCode $debugMessage")
        when (responseCode) {
            BillingClient.BillingResponseCode.OK -> {
                if (purchases == null) {
                    Log.d(TAG, "onPurchasesUpdated: null purchase list")
                    processPurchases(null)
                } else {
                    processPurchases(purchases)
                }
            }
            BillingClient.BillingResponseCode.USER_CANCELED -> {
                Log.i(TAG, "onPurchasesUpdated: User canceled the purchase")
            }
            BillingClient.BillingResponseCode.ITEM_ALREADY_OWNED -> {
                Log.i(TAG, "onPurchasesUpdated: The user already owns this item")
            }
            BillingClient.BillingResponseCode.DEVELOPER_ERROR -> {
                Log.e(
                    TAG, "onPurchasesUpdated: Developer error means that Google Play " +
                            "does not recognize the configuration. If you are just getting started, " +
                            "make sure you have configured the application correctly in the " +
                            "Google Play Console. The product ID must match and the APK you " +
                            "are using must be signed with release keys."
                )
            }
        }
    }

    /**
     * Send purchase to StateFlow, which will trigger network call to verify the subscriptions
     * on the sever.
     */
    private fun processPurchases(purchasesList: List<Purchase>?) {
        Log.d(TAG, "processPurchases: ${purchasesList?.size} purchase(s)")
        if (purchasesList == null || isUnchangedPurchaseList(purchasesList)) {
            Log.d(TAG, "processPurchases: Purchase list has not changed")
            return
        }
        externalScope.launch {
            _purchases.emit(purchasesList)
        }
        logAcknowledgementStatus(purchasesList)
    }

    /**
     * Check whether the purchases have changed before posting changes.
     */
    @Suppress("UNUSED_PARAMETER")
    private fun isUnchangedPurchaseList(purchasesList: List<Purchase>): Boolean {
        // TODO: Optimize to avoid updates with identical data.
        return false
    }

    /**
     * Log the number of purchases that are acknowledge and not acknowledged.
     *
     * https://developer.android.com/google/play/billing/billing_library_releases_notes#2_0_acknowledge
     *
     * When the purchase is first received, it will not be acknowledge.
     * This application sends the purchase token to the server for registration. After the
     * purchase token is registered to an account, the Android app acknowledges the purchase token.
     * The next time the purchase list is updated, it will contain acknowledged purchases.
     */
    private fun logAcknowledgementStatus(purchasesList: List<Purchase>) {
        var acknowledgedCounter = 0
        var unacknowledgedCounter = 0
        for (purchase in purchasesList) {
            if (purchase.isAcknowledged) {
                acknowledgedCounter++
            } else {
                unacknowledgedCounter++
            }
        }
        Log.d(
            TAG,
            "logAcknowledgementStatus: acknowledged=$acknowledgedCounter unacknowledged=$unacknowledgedCounter"
        )
    }

    /**
     * Launching the billing flow.
     *
     * Launching the UI to make a purchase requires a reference to the Activity.
     */
    fun launchBillingFlow(activity: Activity, params: BillingFlowParams): Int {
        if (!billingClient.isReady) {
            Log.e(TAG, "launchBillingFlow: BillingClient is not ready")
        }
        val billingResult = billingClient.launchBillingFlow(activity, params)
        val responseCode = billingResult.responseCode
        val debugMessage = billingResult.debugMessage
        Log.d(TAG, "launchBillingFlow: BillingResponse $responseCode $debugMessage")
        return responseCode
    }

    /**
     * Acknowledge a purchase.
     *
     * https://developer.android.com/google/play/billing/billing_library_releases_notes#2_0_acknowledge
     *
     * Apps should acknowledge the purchase after confirming that the purchase token
     * has been associated with a user. This app only acknowledges purchases after
     * successfully receiving the subscription data back from the server.
     *
     * Developers can choose to acknowledge purchases from a server using the
     * Google Play Developer API. The server has direct access to the user database,
     * so using the Google Play Developer API for acknowledgement might be more reliable.
     * TODO(134506821): Acknowledge purchases on the server.
     * TODO: Remove client side purchase acknowledgement after removing the associated tests.
     * If the purchase token is not acknowledged within 3 days,
     * then Google Play will automatically refund and revoke the purchase.
     * This behavior helps ensure that users are not charged for subscriptions unless the
     * user has successfully received access to the content.
     * This eliminates a category of issues where users complain to developers
     * that they paid for something that the app is not giving to them.
     */
    suspend fun acknowledgePurchase(purchaseToken: String): Boolean {
        val params = AcknowledgePurchaseParams.newBuilder()
            .setPurchaseToken(purchaseToken)
            .build()

        for (trial in 1..MAX_RETRY_ATTEMPT) {
            var response = BillingResponse(500)
            var bResult: BillingResult? = null
            billingClient.acknowledgePurchase(params) { billingResult ->
                response = BillingResponse(billingResult.responseCode)
                bResult = billingResult
            }

            when {
                response.isOk -> {
                    Log.i(TAG, "Acknowledge success - token: $purchaseToken")
                    return true
                }
                response.canFailGracefully -> {
                    // Ignore the error
                    Log.i(TAG, "Token $purchaseToken is already owned.")
                    return true
                }
                response.isRecoverableError -> {
                    // Retry to ack because these errors may be recoverable.
                    val duration = 500L * 2.0.pow(trial).toLong()
                    delay(duration)
                    if (trial < MAX_RETRY_ATTEMPT) {
                        Log.w(
                            TAG,
                            "Retrying($trial) to acknowledge for token $purchaseToken - code: ${bResult!!.responseCode}, message: ${bResult!!.debugMessage}"
                        )
                    }
                }
                response.isNonrecoverableError || response.isTerribleFailure -> {
                    Log.e(
                        TAG,
                        "Failed to acknowledge for token $purchaseToken - code: ${bResult!!.responseCode}, message: ${bResult!!.debugMessage}"
                    )
                    break
                }
            }
        }
        throw Exception("Failed to acknowledge the purchase!")
    }

    companion object {
        private const val TAG = "BillingLifecycle"
        private const val MAX_RETRY_ATTEMPT = 3

        private val LIST_OF_PRODUCTS = listOf(
            Constants.BASIC_PRODUCT,
            Constants.PREMIUM_PRODUCT,
        )

        @Volatile
        private var INSTANCE: BillingClientLifecycle? = null

        fun getInstance(applicationContext: Context): BillingClientLifecycle =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: BillingClientLifecycle(applicationContext).also { INSTANCE = it }
            }
    }
}

@JvmInline
private value class BillingResponse(val code: Int) {
    val isOk: Boolean
        get() = code == BillingClient.BillingResponseCode.OK
    val canFailGracefully: Boolean
        get() = code == BillingClient.BillingResponseCode.ITEM_ALREADY_OWNED
    val isRecoverableError: Boolean
        get() = code in setOf(
            BillingClient.BillingResponseCode.ERROR,
            BillingClient.BillingResponseCode.SERVICE_DISCONNECTED,
        )
    val isNonrecoverableError: Boolean
        get() = code in setOf(
            BillingClient.BillingResponseCode.SERVICE_UNAVAILABLE,
            BillingClient.BillingResponseCode.BILLING_UNAVAILABLE,
            BillingClient.BillingResponseCode.DEVELOPER_ERROR,
        )
    val isTerribleFailure: Boolean
        get() = code in setOf(
            BillingClient.BillingResponseCode.ITEM_UNAVAILABLE,
            BillingClient.BillingResponseCode.FEATURE_NOT_SUPPORTED,
            BillingClient.BillingResponseCode.ITEM_NOT_OWNED,
            BillingClient.BillingResponseCode.USER_CANCELED,
        )
}