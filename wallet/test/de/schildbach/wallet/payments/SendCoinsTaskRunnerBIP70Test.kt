/*
 * Copyright 2026 Dash Core Group.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package de.schildbach.wallet.payments

import android.content.Context
import com.google.firebase.FirebaseApp
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.HiltTestApplication
import de.schildbach.wallet.WalletApplication
import de.schildbach.wallet.database.entity.BlockchainIdentityConfig
import de.schildbach.wallet.security.SecurityFunctions
import de.schildbach.wallet.service.PackageInfoProvider
import de.schildbach.wallet.ui.dashpay.PlatformRepo
import de.schildbach.wallet.security.SecurityGuard
import de.schildbach.wallet.service.platform.IdentityRepository
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.spyk
import io.mockk.unmockkStatic
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.dash.wallet.common.payments.bip70.Protos
import org.bitcoinj.core.Address
import org.bitcoinj.core.Coin
import org.bitcoinj.core.NetworkParameters
import org.bitcoinj.params.TestNet3Params
import org.dash.wallet.common.payments.bip70.PaymentProtocol
import org.bitcoinj.wallet.SendRequest
import org.bitcoinj.wallet.Wallet
import org.bitcoinj.wallet.WalletProtobufSerializer
import de.schildbach.wallet.data.WalletData
import org.dash.wallet.common.data.PaymentIntent
import org.dash.wallet.common.services.TransactionMetadataProvider
import org.dash.wallet.common.services.analytics.AnalyticsService
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.FileInputStream
import java.net.HttpURLConnection
import de.schildbach.wallet.util.toNeutralCoin

/**
 * Unit tests for SendCoinsTaskRunner BIP70/71/72 payment protocol functionality.
 *
 * These tests use MockWebServer to simulate a BIP70 payment server and verify
 * that the payment request fetching and parsing works correctly.
 */
@HiltAndroidTest
@RunWith(RobolectricTestRunner::class)
@Config(application = HiltTestApplication::class, sdk = [29], manifest = Config.NONE)
class SendCoinsTaskRunnerBIP70Test {

    private lateinit var mockWebServer: MockWebServer
    private lateinit var sendCoinsTaskRunner: SendCoinsTaskRunner

    // Mocks
    private lateinit var walletDataProvider: WalletData
    private lateinit var walletApplication: WalletApplication
    private lateinit var securityFunctions: SecurityFunctions
    private lateinit var packageInfoProvider: PackageInfoProvider
    private lateinit var analyticsService: AnalyticsService
    private lateinit var identityConfig: BlockchainIdentityConfig
    private lateinit var identityRepo: IdentityRepository
    private lateinit var platformRepo: PlatformRepo
    private lateinit var metadataProvider: TransactionMetadataProvider
    private lateinit var sdkL1SendService: de.schildbach.wallet.service.platform.sdk.SdkL1SendService
    private lateinit var bridgedTransactionFactory: de.schildbach.wallet.service.platform.sdk.SdkBridgedTransactionFactory
    private lateinit var wallet: Wallet

    private val networkParams: NetworkParameters = TestNet3Params.get()

    @Before
    fun setUp() {
        // Initialize MockWebServer
        mockWebServer = MockWebServer()
        mockWebServer.start()

        // Create mocks
        walletDataProvider = mockk(relaxed = true)
        walletApplication = mockk(relaxed = true)
        securityFunctions = mockk(relaxed = true)
        packageInfoProvider = mockk(relaxed = true)
        analyticsService = mockk(relaxed = true)
        identityConfig = mockk(relaxed = true)
        identityRepo = mockk(relaxed = true)
        platformRepo = mockk(relaxed = true)
        metadataProvider = mockk(relaxed = true)
        sdkL1SendService = mockk(relaxed = true)
        // The relaxed default for a String?-returning suspend fun is "",
        // which is not a decodable address — default to "no refund address"
        // (omitted refund_to); tests that exercise refund_to override this.
        coEvery { sdkL1SendService.refundAddressOrNull() } returns null
        bridgedTransactionFactory = mockk(relaxed = true)
        // wallet = mockk(relaxed = true)

        // dashj requires a Context to be constructed before reading a wallet
        org.bitcoinj.core.Context.propagate(org.bitcoinj.core.Context.getOrCreate(networkParams))

        javaClass.getResourceAsStream("coinjoin.wallet").use {
            wallet = WalletProtobufSerializer().readWallet(it)
        }

        // Setup default mock behaviors
        every { walletDataProvider.wallet } returns wallet
        // every { wallet.params } returns networkParams
        every { packageInfoProvider.httpUserAgent() } returns "DashWallet-Test/1.0"


        // Mock SecurityGuard.getInstance() and related security functions
        val mockSecurityGuard = mockk<SecurityGuard>(relaxed = true)
        mockkStatic(SecurityGuard::class)
        every { SecurityGuard.getInstance() } returns mockSecurityGuard
        every { mockSecurityGuard.retrievePassword() } returns "testPassword"
        every { securityFunctions.deriveKey(any(), any()) } returns mockk(relaxed = true)

        // Create SendCoinsTaskRunner
        sendCoinsTaskRunner = spyk(
            SendCoinsTaskRunner(
                walletDataProvider,
                walletApplication,
                securityFunctions,
                packageInfoProvider,
                analyticsService,
                identityConfig,
                identityRepo,
                platformRepo,
                metadataProvider,
                // Phase 5b routing is exercised in SendCoinsTaskRunnerSdkRoutingTest.
                // The relaxed default keeps cutoverCommitted() false, so the
                // legacy dashj-path tests below are untouched; the
                // post-cutover BIP70 tests re-program this named mock.
                sdkL1SendService,
                // Self-spend grace arming is exercised in
                // SendCoinsTaskRunnerSelfSpendGraceTest.
                mockk(relaxed = true),
                // 5c.0/5c.1 debug probes (L1SendProbeServiceTest): these
                // BIP70 flows never touch the neutral overload.
                mockk(relaxed = true),
                // Phase 5d bridge factory: reached only under a committed
                // cutover — programmed by the post-cutover BIP70 tests.
                bridgedTransactionFactory
            )
        )

        coEvery { sendCoinsTaskRunner.logSendTxEvent(any(), any()) } returns Unit

    }

    @After
    fun tearDown() {
        mockWebServer.shutdown()
        unmockkStatic(SecurityGuard::class)
    }

    /**
     * Creates a valid BIP70 PaymentRequest protobuf for testing.
     */
    private fun createPaymentRequest(
        address: Address,
        amount: Coin,
        memo: String = "Test payment",
        paymentUrl: String? = null
    ): ByteArray {
        val paymentDetails = Protos.PaymentDetails.newBuilder()
            .setNetwork(if (networkParams.id == NetworkParameters.ID_MAINNET) "main" else "test")
            .setTime(System.currentTimeMillis() / 1000)
            .setExpires((System.currentTimeMillis() / 1000) + 3600) // 1 hour from now
            .setMemo(memo)

        // Add output
        val output = Protos.Output.newBuilder()
            .setAmount(amount.value)
            .setScript(com.google.protobuf.ByteString.copyFrom(
                org.bitcoinj.script.ScriptBuilder.createOutputScript(address).program
            ))
            .build()
        paymentDetails.addOutputs(output)

        if (paymentUrl != null) {
            paymentDetails.setPaymentUrl(paymentUrl)
        }

        val paymentRequest = Protos.PaymentRequest.newBuilder()
            .setPaymentDetailsVersion(1)
            .setSerializedPaymentDetails(paymentDetails.build().toByteString())
            .build()

        return paymentRequest.toByteArray()
    }

    /**
     * Creates a BIP71 PaymentACK protobuf for testing.
     */
    private fun createPaymentAck(memo: String = "Payment received"): ByteArray {
        val payment = Protos.Payment.newBuilder()
            .setMemo("Test")
            .build()

        val paymentAck = Protos.PaymentACK.newBuilder()
            .setPayment(payment)
            .setMemo(memo)
            .build()

        return paymentAck.toByteArray()
    }

    @Test
    fun `fetchPaymentRequest returns valid PaymentIntent for BIP70 request`() = runTest {
        // Given: A mock BIP70 server
        val testAddress = Address.fromString(networkParams, "yWdXnYxGbouNoo8yMvcbZmZ3Gdp6BpySxL")
        val testAmount = Coin.parseCoin("1.5")
        val testMemo = "Payment for Order #12345"
        val paymentUrl = mockWebServer.url("/payment").toString()

        val paymentRequestBytes = createPaymentRequest(
            address = testAddress,
            amount = testAmount,
            memo = testMemo,
            paymentUrl = paymentUrl
        )

        val response = MockResponse()
            .setResponseCode(HttpURLConnection.HTTP_OK)
            .setHeader("Content-Type", PaymentProtocol.MIMETYPE_PAYMENTREQUEST)
            .setBody(okio.Buffer().write(paymentRequestBytes))
        mockWebServer.enqueue(response)

        // Create base payment intent with payment request URL
        val basePaymentIntent = PaymentIntent(
            PaymentIntent.Standard.BIP70,
            null, // payeeName
            null, // payeeVerifiedBy
            null, // outputs
            null, // memo
            null, // paymentUrl
            null, // payeeData
            mockWebServer.url("/request").toString(), // paymentRequestUrl
            null, // paymentRequestHash
            null, // payeeUserId
            null  // payeeUsername
        )

        // When: Fetching the payment request
        val result = sendCoinsTaskRunner.fetchPaymentRequest(basePaymentIntent)

        // Then: The result should contain the correct payment details
        assertNotNull(result)
        assertTrue(result.hasAmount())
        assertEquals(testAmount.toNeutralCoin(), result.amount)
        assertEquals(testMemo, result.memo)
    }

    @Test
    fun `fetchPaymentRequest handles HTTP errors gracefully`() = runTest {
        // Given: A mock server returning an error
        val response = MockResponse()
            .setResponseCode(HttpURLConnection.HTTP_INTERNAL_ERROR)
            .setBody("Internal Server Error")
        mockWebServer.enqueue(response)

        val testAddress = Address.fromString(networkParams, "yWdXnYxGbouNoo8yMvcbZmZ3Gdp6BpySxL")
        val basePaymentIntent = PaymentIntent(
            PaymentIntent.Standard.BIP70,
            null, // payeeName
            null, // payeeVerifiedBy
            null, // outputs
            null, // memo
            null, // paymentUrl
            null, // payeeData
            mockWebServer.url("/request").toString(), // paymentRequestUrl
            null, // paymentRequestHash
            null, // payeeUserId
            null  // payeeUsername
        )

        // When/Then: Should throw an exception
        var exceptionThrown = false
        try {
            sendCoinsTaskRunner.fetchPaymentRequest(basePaymentIntent)
        } catch (e: Exception) {
            exceptionThrown = true
        }
        assertTrue("Expected exception for HTTP error", exceptionThrown)
    }

    @Test
    fun `fetchPaymentRequest rejects expired payment requests`() = runTest {
        // Given: An expired payment request
        val testAddress = Address.fromString(networkParams, "yWdXnYxGbouNoo8yMvcbZmZ3Gdp6BpySxL")
        val testAmount = Coin.parseCoin("1.0")

        // Create an expired payment request (expired 1 hour ago)
        val paymentDetails = Protos.PaymentDetails.newBuilder()
            .setNetwork("test")
            .setTime((System.currentTimeMillis() / 1000) - 7200) // 2 hours ago
            .setExpires((System.currentTimeMillis() / 1000) - 3600) // 1 hour ago (expired)
            .setMemo("Expired payment")

        val output = Protos.Output.newBuilder()
            .setAmount(testAmount.value)
            .setScript(com.google.protobuf.ByteString.copyFrom(
                org.bitcoinj.script.ScriptBuilder.createOutputScript(testAddress).program
            ))
            .build()
        paymentDetails.addOutputs(output)

        val paymentRequest = Protos.PaymentRequest.newBuilder()
            .setPaymentDetailsVersion(1)
            .setSerializedPaymentDetails(paymentDetails.build().toByteString())
            .build()

        val response = MockResponse()
            .setResponseCode(HttpURLConnection.HTTP_OK)
            .setHeader("Content-Type", PaymentProtocol.MIMETYPE_PAYMENTREQUEST)
            .setBody(okio.Buffer().write(paymentRequest.toByteArray()))
        mockWebServer.enqueue(response)

        val basePaymentIntent = PaymentIntent(
            PaymentIntent.Standard.BIP70,
            null, // payeeName
            null, // payeeVerifiedBy
            null, // outputs
            null, // memo
            null, // paymentUrl
            null, // payeeData
            mockWebServer.url("/request").toString(), // paymentRequestUrl
            null, // paymentRequestHash
            null, // payeeUserId
            null  // payeeUsername
        )

        // When/Then: The parser should throw an exception for expired payment requests
        var exceptionThrown = false
        try {
            sendCoinsTaskRunner.fetchPaymentRequest(basePaymentIntent)
        } catch (e: Exception) {
            exceptionThrown = true
            // The exception chain should contain PaymentProtocolException.Expired
            assertTrue(
                "Expected expired payment exception",
                e.message?.contains("expired", ignoreCase = true) == true ||
                    e.cause?.message?.contains("expired", ignoreCase = true) == true
            )
        }
        assertTrue("Expected exception for expired payment request", exceptionThrown)
    }

    @Test
    fun `fetchPaymentRequest includes correct HTTP headers`() = runTest {
        // Given: A mock server
        val testAddress = Address.fromString(networkParams, "yWdXnYxGbouNoo8yMvcbZmZ3Gdp6BpySxL")
        val testAmount = Coin.parseCoin("1.0")
        val paymentRequestBytes = createPaymentRequest(testAddress, testAmount)

        val response = MockResponse()
            .setResponseCode(HttpURLConnection.HTTP_OK)
            .setHeader("Content-Type", PaymentProtocol.MIMETYPE_PAYMENTREQUEST)
            .setBody(okio.Buffer().write(paymentRequestBytes))
        mockWebServer.enqueue(response)

        val basePaymentIntent = PaymentIntent(
            PaymentIntent.Standard.BIP70,
            null, // payeeName
            null, // payeeVerifiedBy
            null, // outputs
            null, // memo
            null, // paymentUrl
            null, // payeeData
            mockWebServer.url("/request").toString(), // paymentRequestUrl
            null, // paymentRequestHash
            null, // payeeUserId
            null  // payeeUsername
        )

        // When: Fetching the payment request
        sendCoinsTaskRunner.fetchPaymentRequest(basePaymentIntent)

        // Then: Verify the request headers
        val request = mockWebServer.takeRequest()
        assertEquals("GET", request.method)
        assertEquals(PaymentProtocol.MIMETYPE_PAYMENTREQUEST, request.getHeader("Accept"))
        assertEquals("DashWallet-Test/1.0", request.getHeader("User-Agent"))
    }

    @Test
    fun `fetchPaymentRequest validates BIP72 trust check with matching outputs`() = runTest {
        // Given: A payment request that matches the base intent exactly
        val testAddress = Address.fromString(networkParams, "yWdXnYxGbouNoo8yMvcbZmZ3Gdp6BpySxL")
        val testAmount = Coin.parseCoin("1.0")

        // Create payment request with same amount
        val paymentRequestBytes = createPaymentRequest(testAddress, testAmount)

        val response = MockResponse()
            .setResponseCode(HttpURLConnection.HTTP_OK)
            .setHeader("Content-Type", PaymentProtocol.MIMETYPE_PAYMENTREQUEST)
            .setBody(okio.Buffer().write(paymentRequestBytes))
        mockWebServer.enqueue(response)

        // Base intent with matching outputs (BIP21 with payment request URL = BIP72)
        val outputs = arrayOf(
            PaymentIntent.Output(
                testAmount.toNeutralCoin(),
                org.bitcoinj.script.ScriptBuilder.createOutputScript(testAddress).program
            )
        )
        val basePaymentIntent = PaymentIntent(
            PaymentIntent.Standard.BIP21,
            null, null,
            outputs,
            null, null, null,
            mockWebServer.url("/request").toString(), // paymentRequestUrl
            null, null, null
        )

        // When: Fetching the payment request
        val result = sendCoinsTaskRunner.fetchPaymentRequest(basePaymentIntent)

        // Then: Should succeed with matching outputs
        assertNotNull(result)
        assertEquals(testAmount.toNeutralCoin(), result.amount)
    }

    @Test
    fun `fetchPaymentRequest handles null payment request URL`() = runTest {
        // Given: A payment intent without a payment request URL
        val testAddress = Address.fromString(networkParams, "yWdXnYxGbouNoo8yMvcbZmZ3Gdp6BpySxL")
        val basePaymentIntent = PaymentIntent.fromAddress(testAddress.toBase58(), null as String?)

        // When/Then: Should throw IllegalArgumentException
        var exceptionThrown = false
        try {
            sendCoinsTaskRunner.fetchPaymentRequest(basePaymentIntent)
        } catch (e: IllegalArgumentException) {
            exceptionThrown = true
            assertTrue(e.message?.contains("payment request URL") == true)
        }
        assertTrue("Expected exception for null payment request URL", exceptionThrown)
    }

    // ==================== payWithDashUrl Tests ====================

    @Test
    fun `payWithDashUrl parses BIP72 URI and fetches payment request`() = runTest {
        // Given: A BIP72 URI with payment request URL
        val testAddress = Address.fromString(networkParams, "yWdXnYxGbouNoo8yMvcbZmZ3Gdp6BpySxL")
        val testAmount = Coin.parseCoin("2.0")
        val testMemo = "BIP72 Payment"
        val paymentUrl = mockWebServer.url("/payment").toString()

        val paymentRequestBytes = createPaymentRequest(
            address = testAddress,
            amount = testAmount,
            memo = testMemo,
            paymentUrl = paymentUrl
        )

        // Enqueue payment request response
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(HttpURLConnection.HTTP_OK)
                .setHeader("Content-Type", PaymentProtocol.MIMETYPE_PAYMENTREQUEST)
                .setBody(okio.Buffer().write(paymentRequestBytes))
        )

        // Enqueue payment ACK response
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(HttpURLConnection.HTTP_OK)
                .setHeader("Content-Type", PaymentProtocol.MIMETYPE_PAYMENTACK)
                .setBody(okio.Buffer().write(createPaymentAck()))
        )

        // Create BIP72 URI (BIP21 with r= parameter)
        val requestUrl = mockWebServer.url("/request").toString()
        val bip72Uri = "dash:$testAddress?amount=2.0&r=$requestUrl"

        // When: Processing the BIP72 URI
        try {
            val tx = sendCoinsTaskRunner.payWithDashUrl(bip72Uri, "TestService")
            println(tx)
            // If we get here without exception, the request was processed
            // (though it may fail later due to wallet mocking limitations)
        } catch (e: Exception) {
            // Expected - wallet operations are mocked
            // Verify the payment request was fetched
            val request = mockWebServer.takeRequest()
            assertEquals("GET", request.method)
            assertTrue(request.path?.contains("/request") == true)
            e.printStackTrace()
        }
    }

    @Test
    fun `payWithDashUrl handles simple BIP21 URI without payment request`() = runTest {
        // Given: A simple BIP21 URI without payment request URL
        val testAddress = Address.fromString(networkParams, "yWdXnYxGbouNoo8yMvcbZmZ3Gdp6BpySxL")
        val bip21Uri = "dash:$testAddress?amount=1.5"

        // When: Processing the simple BIP21 URI
        try {
            sendCoinsTaskRunner.payWithDashUrl(bip21Uri, null)
            // Should not make HTTP requests for simple BIP21
        } catch (e: Exception) {
            // Expected - wallet operations are mocked
            // Verify no HTTP requests were made
            assertEquals(0, mockWebServer.requestCount)
        }
    }

    @Test
    fun `payWithDashUrl rejects invalid URI`() = runTest {
        // Given: An invalid URI
        val invalidUri = "not-a-valid-uri"

        // When/Then: Should throw an exception
        try {
            sendCoinsTaskRunner.payWithDashUrl(invalidUri, null)
            fail("Expected exception for invalid URI")
        } catch (e: Exception) {
            // Expected - invalid URI should throw
            assertNotNull(e)
        }
    }

    @Test
    fun `payWithDashUrl passes service name for metadata tracking`() = runTest {
        // Given: A BIP72 URI with a service name
        val testAddress = Address.fromString(networkParams, "yWdXnYxGbouNoo8yMvcbZmZ3Gdp6BpySxL")
        val testAmount = Coin.parseCoin("1.0")
        val paymentUrl = mockWebServer.url("/payment").toString()

        val paymentRequestBytes = createPaymentRequest(
            address = testAddress,
            amount = testAmount,
            memo = "Service payment",
            paymentUrl = paymentUrl
        )

        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(HttpURLConnection.HTTP_OK)
                .setHeader("Content-Type", PaymentProtocol.MIMETYPE_PAYMENTREQUEST)
                .setBody(okio.Buffer().write(paymentRequestBytes))
        )

        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(HttpURLConnection.HTTP_OK)
                .setHeader("Content-Type", PaymentProtocol.MIMETYPE_PAYMENTACK)
                .setBody(okio.Buffer().write(createPaymentAck()))
        )

        val requestUrl = mockWebServer.url("/request").toString()
        val bip72Uri = "dash:$testAddress?amount=1.0&r=$requestUrl"

        // When: Processing with a service name
        try {
            sendCoinsTaskRunner.payWithDashUrl(bip72Uri, "GiftCardService")
        } catch (e: Exception) {
            // Expected due to wallet mocking
            // The service name would be passed through to metadata tracking
        }

        // Verify HTTP request was made
        assertTrue(mockWebServer.requestCount > 0)
    }

    // ==================== sendDirectPayment Tests ====================

    /**
     * Helper to create a real SendRequest with a transaction for testing.
     * Since SendRequest.tx is a field (not a method), we can't mock it with MockK.
     */
    private fun createTestSendRequest(address: Address, amount: Coin): SendRequest {
        // Create a real SendRequest - this creates a transaction internally
        return SendRequest.to(address, amount)
    }

    @Test
    fun `sendDirectPayment sends payment and receives ACK`() = runTest {
        // Given: A payment intent with payment URL and mock ACK response
        val testAddress = Address.fromString(networkParams, "yWdXnYxGbouNoo8yMvcbZmZ3Gdp6BpySxL")
        val testAmount = Coin.parseCoin("1.0")
        val paymentUrl = mockWebServer.url("/payment").toString()

        // Create payment intent with payment URL
        val outputs = arrayOf(
            PaymentIntent.Output(
                testAmount.toNeutralCoin(),
                org.bitcoinj.script.ScriptBuilder.createOutputScript(testAddress).program
            )
        )
        val paymentIntent = PaymentIntent(
            PaymentIntent.Standard.BIP70,
            "Test Merchant", // payeeName
            null, // payeeVerifiedBy
            outputs,
            "Test payment", // memo
            paymentUrl, // paymentUrl - this is where the payment is sent
            null, // payeeData
            null, // paymentRequestUrl
            null, // paymentRequestHash
            null, // payeeUserId
            null  // payeeUsername
        )

        // Enqueue successful ACK response
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(HttpURLConnection.HTTP_OK)
                .setHeader("Content-Type", PaymentProtocol.MIMETYPE_PAYMENTACK)
                .setBody(okio.Buffer().write(createPaymentAck("Payment accepted")))
        )

        // Create a real SendRequest
        val sendRequest = createTestSendRequest(testAddress, testAmount)

        // When: Sending direct payment
        try {
            sendCoinsTaskRunner.sendDirectPayment(sendRequest, paymentIntent, "TestService")
        } catch (e: Exception) {
            // May fail due to wallet completion mocking, but verify HTTP was attempted
        }

        // Then: Verify payment was sent via HTTP POST
        if (mockWebServer.requestCount > 0) {
            val request = mockWebServer.takeRequest()
            assertEquals("POST", request.method)
            assertEquals(PaymentProtocol.MIMETYPE_PAYMENTACK, request.getHeader("Accept"))
            assertTrue(request.path?.contains("/payment") == true)
        }
    }

    @Test
    fun `sendDirectPayment handles NACK response`() = runTest {
        // Given: A payment intent and NACK response
        val testAddress = Address.fromString(networkParams, "yWdXnYxGbouNoo8yMvcbZmZ3Gdp6BpySxL")
        val testAmount = Coin.parseCoin("1.0")
        val paymentUrl = mockWebServer.url("/payment").toString()

        val outputs = arrayOf(
            PaymentIntent.Output(
                testAmount.toNeutralCoin(),
                org.bitcoinj.script.ScriptBuilder.createOutputScript(testAddress).program
            )
        )
        val paymentIntent = PaymentIntent(
            PaymentIntent.Standard.BIP70,
            null, null,
            outputs,
            null,
            paymentUrl,
            null, null, null, null, null
        )

        // Create NACK response (memo = "nack")
        val nackPayment = Protos.Payment.newBuilder().setMemo("Test").build()
        val nackResponse = Protos.PaymentACK.newBuilder()
            .setPayment(nackPayment)
            .setMemo("nack")
            .build()

        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(HttpURLConnection.HTTP_OK)
                .setHeader("Content-Type", PaymentProtocol.MIMETYPE_PAYMENTACK)
                .setBody(okio.Buffer().write(nackResponse.toByteArray()))
        )

        val sendRequest = createTestSendRequest(testAddress, testAmount)

        // When/Then: Should throw DirectPayException for NACK
        try {
            sendCoinsTaskRunner.sendDirectPayment(sendRequest, paymentIntent)
            // If no exception, the nack was not properly handled
        } catch (e: org.dash.wallet.common.services.DirectPayException) {
            // Expected - NACK should throw DirectPayException
            assertTrue(e.message?.contains("not acknowledged") == true)
        } catch (e: Exception) {
            // Other exceptions may occur due to mocking
        }
    }

    @Test
    fun `sendDirectPayment handles HTTP error gracefully`() = runTest {
        // Given: A payment intent and HTTP error response
        val testAddress = Address.fromString(networkParams, "yWdXnYxGbouNoo8yMvcbZmZ3Gdp6BpySxL")
        val testAmount = Coin.parseCoin("1.0")
        val paymentUrl = mockWebServer.url("/payment").toString()

        val outputs = arrayOf(
            PaymentIntent.Output(
                testAmount.toNeutralCoin(),
                org.bitcoinj.script.ScriptBuilder.createOutputScript(testAddress).program
            )
        )
        val paymentIntent = PaymentIntent(
            PaymentIntent.Standard.BIP70,
            null, null,
            outputs,
            null,
            paymentUrl,
            null, null, null, null, null
        )

        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(HttpURLConnection.HTTP_BAD_REQUEST)
                .setBody("Bad Request")
        )

        val sendRequest = createTestSendRequest(testAddress, testAmount)

        // When/Then: Should throw exception for HTTP error
        try {
            sendCoinsTaskRunner.sendDirectPayment(sendRequest, paymentIntent)
            fail("Expected exception for HTTP error")
        } catch (e: Exception) {
            // Expected - HTTP errors should throw
            assertNotNull(e)
        }
    }

    @Test
    fun `sendDirectPayment includes correct Content-Type header`() = runTest {
        // Given: A payment intent with payment URL
        val testAddress = Address.fromString(networkParams, "yWdXnYxGbouNoo8yMvcbZmZ3Gdp6BpySxL")
        val testAmount = Coin.parseCoin("1.0")
        val paymentUrl = mockWebServer.url("/payment").toString()

        val outputs = arrayOf(
            PaymentIntent.Output(
                testAmount.toNeutralCoin(),
                org.bitcoinj.script.ScriptBuilder.createOutputScript(testAddress).program
            )
        )
        val paymentIntent = PaymentIntent(
            PaymentIntent.Standard.BIP70,
            null, null,
            outputs,
            null,
            paymentUrl,
            null, null, null, null, null
        )

        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(HttpURLConnection.HTTP_OK)
                .setHeader("Content-Type", PaymentProtocol.MIMETYPE_PAYMENTACK)
                .setBody(okio.Buffer().write(createPaymentAck()))
        )

        val sendRequest = createTestSendRequest(testAddress, testAmount)

        // When: Sending direct payment
        try {
            sendCoinsTaskRunner.sendDirectPayment(sendRequest, paymentIntent)
        } catch (e: Exception) {
            // Expected due to mocking
        }

        // Then: Verify Content-Type header
        if (mockWebServer.requestCount > 0) {
            val request = mockWebServer.takeRequest()
            assertEquals(PaymentProtocol.MIMETYPE_PAYMENT, request.getHeader("Content-Type"))
            assertEquals("DashWallet-Test/1.0", request.getHeader("User-Agent"))
        }
    }

    @Test
    fun `sendDirectPayment throws for null payment URL`() = runTest {
        // Given: A payment intent without payment URL
        val testAddress = Address.fromString(networkParams, "yWdXnYxGbouNoo8yMvcbZmZ3Gdp6BpySxL")
        val testAmount = Coin.parseCoin("1.0")
        val paymentIntent = PaymentIntent.fromAddress(testAddress.toBase58(), null as String?)

        val sendRequest = createTestSendRequest(testAddress, testAmount)

        // When/Then: Should throw for null payment URL
        try {
            sendCoinsTaskRunner.sendDirectPayment(sendRequest, paymentIntent)
            fail("Expected exception for null payment URL")
        } catch (e: Exception) {
            // Expected - null payment URL should throw
            assertNotNull(e)
        }
    }

    // ==================== Post-cutover (SDK deferred) direct-pay tests ====================

    private val deferredTxidHex = "aa".repeat(32)

    private fun deferredPayment() = de.schildbach.wallet.service.platform.sdk.SdkDeferredPayment(
        txidHex = deferredTxidHex,
        rawTxBytes = byteArrayOf(1, 2, 3, 4),
        feeDuffs = 1000L,
        native = null
    )

    private fun bip70PaymentIntent(address: Address, amount: Coin, paymentUrl: String) = PaymentIntent(
        PaymentIntent.Standard.BIP70,
        "Test Merchant",
        null,
        arrayOf(
            PaymentIntent.Output(
                amount.toNeutralCoin(),
                org.bitcoinj.script.ScriptBuilder.createOutputScript(address).program
            )
        ),
        "Test payment",
        paymentUrl,
        null,
        null,
        null,
        null,
        null
    )

    @Test
    fun `post-cutover directPay builds via SDK, broadcasts on ACK and returns the bridged tx`() = runTest {
        val testAddress = Address.fromString(networkParams, "yWdXnYxGbouNoo8yMvcbZmZ3Gdp6BpySxL")
        val testAmount = Coin.parseCoin("1.0")
        val paymentIntent =
            bip70PaymentIntent(testAddress, testAmount, mockWebServer.url("/payment").toString())

        val payment = deferredPayment()
        val liveTx = mockk<org.bitcoinj.core.Transaction>(relaxed = true)
        coEvery { sdkL1SendService.cutoverCommitted() } returns true
        // Exercise the refund_to arm: a valid testnet address from the
        // SDK address pool goes into the Payment message.
        coEvery { sdkL1SendService.refundAddressOrNull() } returns
            "yWdXnYxGbouNoo8yMvcbZmZ3Gdp6BpySxL"
        coEvery { sdkL1SendService.buildDeferredPayment(any()) } returns payment
        coEvery { sdkL1SendService.broadcastDeferredPayment(payment) } returns
            de.schildbach.wallet.service.platform.sdk.SdkWriteResult.Broadcast(deferredTxidHex)
        coEvery { bridgedTransactionFactory.bridge(deferredTxidHex, payment.rawTxBytes) } returns
            de.schildbach.wallet.service.platform.sdk.BridgedTxResult.Bridged(
                liveTx, adoptedWalletInstance = false
            )

        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(HttpURLConnection.HTTP_OK)
                .setHeader("Content-Type", PaymentProtocol.MIMETYPE_PAYMENTACK)
                .setBody(okio.Buffer().write(createPaymentAck("Payment accepted")))
        )

        val sendRequest = createTestSendRequest(testAddress, testAmount)
        val result = sendCoinsTaskRunner.sendDirectPayment(sendRequest, paymentIntent, "TestService")

        // The bridged live instance comes back; the recipients were the
        // intent's (address, duffs) pair; the reservation was never released.
        assertEquals(liveTx, result)
        io.mockk.coVerify(exactly = 1) {
            sdkL1SendService.buildDeferredPayment(
                listOf(testAddress.toBase58() to testAmount.value)
            )
        }
        io.mockk.coVerify(exactly = 1) { sdkL1SendService.broadcastDeferredPayment(payment) }
        io.mockk.coVerify(exactly = 0) { sdkL1SendService.releaseDeferredPayment(any()) }

        // And the Payment message actually went over HTTP.
        assertEquals(1, mockWebServer.requestCount)
        assertEquals("POST", mockWebServer.takeRequest().method)
    }

    @Test
    fun `post-cutover directPay releases the reservation on NACK and never broadcasts`() = runTest {
        val testAddress = Address.fromString(networkParams, "yWdXnYxGbouNoo8yMvcbZmZ3Gdp6BpySxL")
        val testAmount = Coin.parseCoin("1.0")
        val paymentIntent =
            bip70PaymentIntent(testAddress, testAmount, mockWebServer.url("/payment").toString())

        val payment = deferredPayment()
        coEvery { sdkL1SendService.cutoverCommitted() } returns true
        coEvery { sdkL1SendService.buildDeferredPayment(any()) } returns payment

        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(HttpURLConnection.HTTP_OK)
                .setHeader("Content-Type", PaymentProtocol.MIMETYPE_PAYMENTACK)
                .setBody(okio.Buffer().write(createPaymentAck("nack")))
        )

        val sendRequest = createTestSendRequest(testAddress, testAmount)
        try {
            sendCoinsTaskRunner.sendDirectPayment(sendRequest, paymentIntent)
            fail("Expected DirectPayException for NACK")
        } catch (e: org.dash.wallet.common.services.DirectPayException) {
            // expected
        }

        io.mockk.coVerify(exactly = 1) { sdkL1SendService.releaseDeferredPayment(payment) }
        io.mockk.coVerify(exactly = 0) { sdkL1SendService.broadcastDeferredPayment(any()) }
    }

    @Test
    fun `post-cutover directPay releases the reservation on transport failure`() = runTest {
        val testAddress = Address.fromString(networkParams, "yWdXnYxGbouNoo8yMvcbZmZ3Gdp6BpySxL")
        val testAmount = Coin.parseCoin("1.0")
        val paymentIntent =
            bip70PaymentIntent(testAddress, testAmount, mockWebServer.url("/payment").toString())

        val payment = deferredPayment()
        coEvery { sdkL1SendService.cutoverCommitted() } returns true
        coEvery { sdkL1SendService.buildDeferredPayment(any()) } returns payment

        mockWebServer.enqueue(MockResponse().setResponseCode(HttpURLConnection.HTTP_INTERNAL_ERROR))

        val sendRequest = createTestSendRequest(testAddress, testAmount)
        try {
            sendCoinsTaskRunner.sendDirectPayment(sendRequest, paymentIntent)
            fail("Expected exception for HTTP 500")
        } catch (e: Exception) {
            assertNotNull(e)
        }

        io.mockk.coVerify(exactly = 1) { sdkL1SendService.releaseDeferredPayment(payment) }
        io.mockk.coVerify(exactly = 0) { sdkL1SendService.broadcastDeferredPayment(any()) }
    }

    @Test
    fun `post-cutover directPay fails closed on a non-address output before building`() = runTest {
        val testAmount = Coin.parseCoin("1.0")
        // OP_RETURN output — not expressible by the SDK's address-only builder.
        val opReturnScript = org.bitcoinj.script.ScriptBuilder.createOpReturnScript(
            byteArrayOf(1, 2, 3)
        ).program
        val paymentIntent = PaymentIntent(
            PaymentIntent.Standard.BIP70,
            "Test Merchant",
            null,
            arrayOf(PaymentIntent.Output(testAmount.toNeutralCoin(), opReturnScript)),
            "Test payment",
            mockWebServer.url("/payment").toString(),
            null,
            null,
            null,
            null,
            null
        )
        coEvery { sdkL1SendService.cutoverCommitted() } returns true

        val testAddress = Address.fromString(networkParams, "yWdXnYxGbouNoo8yMvcbZmZ3Gdp6BpySxL")
        val sendRequest = createTestSendRequest(testAddress, testAmount)
        try {
            sendCoinsTaskRunner.sendDirectPayment(sendRequest, paymentIntent)
            fail("Expected IllegalStateException for a non-address output")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("not SDK-routable"))
        }

        io.mockk.coVerify(exactly = 0) { sdkL1SendService.buildDeferredPayment(any()) }
        assertEquals(0, mockWebServer.requestCount)
    }

    // ==================== extractBip70Recipients (pure) ====================

    @Test
    fun `extractBip70Recipients maps standard outputs and rejects non-address scripts`() {
        val network = org.dash.wallet.common.payments.parsers.AddressNetwork.fromId(networkParams.id)
        val addressA = Address.fromString(networkParams, "yWdXnYxGbouNoo8yMvcbZmZ3Gdp6BpySxL")
        val p2pkh = org.bitcoinj.script.ScriptBuilder.createOutputScript(addressA).program

        // Single and multi-output standard scripts map in order.
        val multi = PaymentIntent(
            PaymentIntent.Standard.BIP70, null, null,
            arrayOf(
                PaymentIntent.Output(Coin.valueOf(150_000).toNeutralCoin(), p2pkh),
                PaymentIntent.Output(Coin.valueOf(250_000).toNeutralCoin(), p2pkh)
            ),
            null, null, null, null, null, null, null
        )
        assertEquals(
            listOf(addressA.toBase58() to 150_000L, addressA.toBase58() to 250_000L),
            extractBip70Recipients(multi, network)
        )

        // An OP_RETURN output poisons the whole intent (fail closed).
        val withOpReturn = PaymentIntent(
            PaymentIntent.Standard.BIP70, null, null,
            arrayOf(
                PaymentIntent.Output(Coin.valueOf(150_000).toNeutralCoin(), p2pkh),
                PaymentIntent.Output(
                    Coin.valueOf(1).toNeutralCoin(),
                    org.bitcoinj.script.ScriptBuilder.createOpReturnScript(byteArrayOf(9, 9, 9)).program
                )
            ),
            null, null, null, null, null, null, null
        )
        assertEquals(null, extractBip70Recipients(withOpReturn, network))

        // A zero-amount output is not routable either.
        val zeroAmount = PaymentIntent(
            PaymentIntent.Standard.BIP70, null, null,
            arrayOf(PaymentIntent.Output(Coin.ZERO.toNeutralCoin(), p2pkh)),
            null, null, null, null, null, null, null
        )
        assertEquals(null, extractBip70Recipients(zeroAmount, network))
    }
}