package com.example.subscriptions.data

import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.android.billingclient.api.Purchase
import com.example.subscriptions.billing.BillingClientLifecycle
import com.example.subscriptions.data.disk.SubLocalDataSource
import com.example.subscriptions.data.disk.db.AppDatabase
import com.example.subscriptions.data.network.SubRemoteDataSource
import com.example.subscriptions.data.network.firebase.FakeServerFunctions
import com.example.subscriptions.data.network.firebase.ServerFunctions
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.hamcrest.CoreMatchers.`is`
import org.hamcrest.MatcherAssert.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.IOException

fun samplePurchase(product: String, purchaseToken: String): Purchase =
    Purchase(
        """
        {"purchaseToken": "$purchaseToken", "productIds": ["$product"]}
        """, "signature"
    )

class SubRepositoryTest {
    private lateinit var database: AppDatabase
    private lateinit var serverFunctions: ServerFunctions
    private lateinit var subLocalDataSource: SubLocalDataSource
    private lateinit var subRemoteDataSource: SubRemoteDataSource
    private lateinit var billingClientLifecycle: BillingClientLifecycle
    private lateinit var repository: SubRepository
    private lateinit var testScope: TestScope

    @Before
    fun setUp() {
        val appContext = InstrumentationRegistry.getInstrumentation().targetContext

        // TODO delegate the following instantiations to Hilt
        database = Room.inMemoryDatabaseBuilder(appContext, AppDatabase::class.java).build()
        serverFunctions = FakeServerFunctions.getInstance()
        subLocalDataSource = SubLocalDataSource.getInstance(database.subscriptionStatusDao())
        subRemoteDataSource = SubRemoteDataSource.getInstance(serverFunctions)

        billingClientLifecycle = mockk()
        every { billingClientLifecycle.purchases } answers { MutableStateFlow(emptyList()) }

        testScope = TestScope(UnconfinedTestDispatcher())
        repository = SubRepository.getInstance(
            subLocalDataSource, subRemoteDataSource, billingClientLifecycle, testScope
        )
    }

    @After
    @Throws(IOException::class)
    fun tearDown() {
        database.close()
    }

    @Test
    fun registerSubscription_callAcknowledge() {
        try {
            testScope.runTest(5_000) {
                // given
                val purchase = samplePurchase(
                    "basic_subscription",
                    "TEST_TOKEN"
                )
                coEvery {
                    billingClientLifecycle
                        .acknowledgePurchase("TEST_TOKEN")
                } returns true
                every {
                    billingClientLifecycle
                        .purchases
                } returns MutableStateFlow(listOf(purchase))

                // when
                val result = repository.registerSubscription(
                    "basic_subscription",
                    "TEST_TOKEN"
                )

                // then
                coVerify { billingClientLifecycle.acknowledgePurchase("TEST_TOKEN") }
                assertThat(result.isSuccess, `is`(true))

                // Walk around for timeout issue - likely error of Coroutines test
                cancel()
            }
        } catch (e: CancellationException) { /* ignore */
        }
    }

    @Test
    fun registerSubscription_failWhenAllAcknowledgeAttemptFailed() {
        try {
            testScope.runTest(5_000) {
                // given
                val purchase = samplePurchase(
                    "basic_subscription",
                    "TEST_TOKEN"
                )
                coEvery {
                    billingClientLifecycle
                        .acknowledgePurchase(any())
                } throws Exception("FAILURE!")
                every {
                    billingClientLifecycle
                        .purchases
                } returns MutableStateFlow(listOf(purchase))

                // when
                val result = repository.registerSubscription(
                    "basic_subscription",
                    "TEST_TOKEN"
                )

                // then
                assertThat(result.isFailure, `is`(true))
                assertThat(result.exceptionOrNull()?.message, `is`("FAILURE!"))

                // Walk around for timeout issue - likely error of Coroutines test
                cancel()
            }
        } catch (e: CancellationException) { /* ignore */
        }
    }

    @Test
    fun registerSubscription_emitRegisteredSubscriptionToStateFlow() {
        try {
            testScope.runTest(5_000) {
                // given
                val purchase = samplePurchase(
                    "basic_subscription",
                    "TEST_TOKEN"
                )
                coEvery {
                    billingClientLifecycle
                        .acknowledgePurchase("TEST_TOKEN")
                } coAnswers { true }
                val purchaseState = MutableStateFlow(listOf(purchase))
                every { billingClientLifecycle.purchases } returns purchaseState

                // when
                repository.registerSubscription(
                    "basic_subscription",
                    "TEST_TOKEN"
                )

                // then
                val storedSubscriptionList = subLocalDataSource.getSubscriptions().first()
                val storedSubscription = storedSubscriptionList.first()
                assertThat(storedSubscription.product, `is`("basic_subscription"))
                assertThat(storedSubscription.purchaseToken, `is`("TEST_TOKEN"))

                // Walk around for timeout issue - likely error of Coroutines test
                cancel()
            }
        } catch (e: CancellationException) { /* ignore */
        }
    }
}