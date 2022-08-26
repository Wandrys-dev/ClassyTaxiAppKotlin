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

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.ViewModelProvider
import androidx.viewpager2.adapter.FragmentStateAdapter
import com.android.billingclient.api.Purchase
import com.example.subscriptions.Constants
import com.example.subscriptions.R
import com.example.subscriptions.SubApp
import com.example.subscriptions.billing.BillingClientLifecycle
import com.example.subscriptions.databinding.ActivityMainBinding
import com.firebase.ui.auth.AuthUI
import com.google.android.material.tabs.TabLayout
import com.google.android.material.tabs.TabLayoutMediator
import com.google.firebase.auth.FirebaseUser

/**
 * [MainActivity] contains 3 [TabFragment] objects.
 * Each fragment uses this activity as the lifecycle owner for [SubscriptionStatusViewModel].
 * When the ViewModel needs to open an Intent from this Activity, it calls a [SingleLiveEvent]
 * observed in this Activity.
 *
 * Uses [FirebaseUserViewModel] to maintain authentication state.
 * The menu is updated when the [FirebaseUser] changes.
 * When sign-in or sign-out is completed, call the [FirebaseUserViewModel] to update the state.
 */
class MainActivity : AppCompatActivity() {
    private lateinit var billingClientLifecycle: BillingClientLifecycle

    private lateinit var authenticationViewModel: FirebaseUserViewModel
    private lateinit var billingViewModel: BillingViewModel
    private lateinit var subscriptionViewModel: SubscriptionStatusViewModel

    private val registerResult = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        if (it.resultCode == RESULT_OK) {
            Log.d(TAG, "Sign-in SUCCESS!")
            authenticationViewModel.updateFirebaseUser()
        } else {
            Log.d(TAG, "Sign-in FAILED!")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        ActivityMainBinding.inflate(layoutInflater).apply {
            setContentView(root)
            setSupportActionBar(toolbar)

            // Create the adapter that will return a fragment for each of the three
            // primary sections of the activity.
            container.adapter = SectionsStateAdapter(this@MainActivity)
            TabLayoutMediator(tabs, container) { tab, position ->
                when (position) {
                    0 -> tab.setText(R.string.tab_text_home)
                    1 -> tab.setText(R.string.tab_text_premium)
                    2 -> tab.setText(R.string.tab_text_settings)
                }
            }.attach()
        }

        authenticationViewModel = ViewModelProvider(this).get(FirebaseUserViewModel::class.java)
        billingViewModel = ViewModelProvider(this).get(BillingViewModel::class.java)
        subscriptionViewModel =
            ViewModelProvider(this).get(SubscriptionStatusViewModel::class.java)

        // Billing APIs are all handled in the this lifecycle observer.
        billingClientLifecycle = (application as SubApp).billingClientLifecycle
        lifecycle.addObserver(billingClientLifecycle)

        // Launch the billing flow when the user clicks a button to buy something.
        billingViewModel.buyEvent.observe(this) {
            if (it != null) {
                billingClientLifecycle.launchBillingFlow(this, it)
            }
        }

        // Open the Play Store when this event is triggered.
        billingViewModel.openPlayStoreSubscriptionsEvent.observe(this) { product ->
            Log.i(TAG, "Viewing subscriptions on the Google Play Store")
            val url = if (product == null) {
                // If the Product is not specified, just open the Google Play subscriptions URL.
                Constants.PLAY_STORE_SUBSCRIPTION_URL
            } else {
                // If the Product is specified, open the deeplink for this Product on Google Play.
                String.format(Constants.PLAY_STORE_SUBSCRIPTION_DEEPLINK_URL, product, packageName)
            }
            val intent = Intent(Intent.ACTION_VIEW)
            intent.data = Uri.parse(url)
            startActivity(intent)
        }

        // Update authentication UI.
        authenticationViewModel.firebaseUser.observe(this) {
            invalidateOptionsMenu()
            if (it == null) {
                triggerSignIn()
            } else {
                Log.d(TAG, "CURRENT user: ${it.email} ${it.displayName}")
            }
        }

        // Update subscription information when user changes.
        authenticationViewModel.userChangeEvent.observe(this) {
            subscriptionViewModel.userChanged()
            registerPurchases(billingClientLifecycle.purchases.value)
        }
    }

    /**
     * Register Products and purchase tokens with the server.
     */
    private fun registerPurchases(purchaseList: List<Purchase>) {
        for (purchase in purchaseList) {
            val product = purchase.products[0]
            val purchaseToken = purchase.purchaseToken
            Log.d(TAG, "Register purchase with product: $product, token: $purchaseToken")
            subscriptionViewModel.registerSubscription(
                product = product,
                purchaseToken = purchaseToken
            )
        }
    }

    /**
     * Create menu items.
     */
    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu, menu)
        return true
    }

    /**
     * Update menu based on sign-in state. Called in response to [invalidateOptionsMenu].
     */
    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        val isSignedIn = authenticationViewModel.isSignedIn()
        menu.findItem(R.id.sign_in).isVisible = !isSignedIn
        menu.findItem(R.id.sign_out).isVisible = isSignedIn
        return true
    }

    /**
     * Called when menu item is selected.
     */
    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.sign_out -> {
                triggerSignOut()
                true
            }
            R.id.sign_in -> {
                triggerSignIn()
                true
            }
            R.id.refresh -> {
                refreshData()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun refreshData() {
        billingClientLifecycle.queryPurchases()
        subscriptionViewModel.manualRefresh()
    }

    /**
     * Sign in with FirebaseUI Auth.
     */
    private fun triggerSignIn() {
        Log.d(TAG, "Attempting SIGN-IN!")
        val providers = listOf(
            AuthUI.IdpConfig.EmailBuilder().build(),
            AuthUI.IdpConfig.GoogleBuilder().build()
        )
        registerResult.launch(
            AuthUI.getInstance()
                .createSignInIntentBuilder()
                .setAvailableProviders(providers)
                .build()
        )
    }

    /**
     * Sign out with FirebaseUI Auth.
     */
    private fun triggerSignOut() {
        subscriptionViewModel.unregisterInstanceId()
        AuthUI.getInstance().signOut(this).addOnCompleteListener {
            Log.d(TAG, "User SIGNED OUT!")
            authenticationViewModel.updateFirebaseUser()
        }
    }

    /**
     * A [FragmentStateAdapter] that returns a fragment corresponding to
     * one of the sections/tabs/pages.
     */
    private class SectionsStateAdapter(activity : FragmentActivity) :
        FragmentStateAdapter(activity) {
        override fun createFragment(position: Int): Fragment = TabFragment.newInstance(position)
        override fun getItemCount(): Int = TAB_COUNT
    }

    companion object {
        const val HOME_PAGER_INDEX = 0
        const val PREMIUM_PAGER_INDEX = 1
        const val SETTINGS_PAGER_INDEX = 2

        private const val TAG = "MainActivity"
        private const val TAB_COUNT = 3
    }
}
