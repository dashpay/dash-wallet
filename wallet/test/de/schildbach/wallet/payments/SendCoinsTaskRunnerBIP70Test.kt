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
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.spyk
import io.mockk.unmockkStatic
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.dash.wallet.common.payments.bip70.Protos
import org.bitcoinj.core.Address
import org.bitcoinj.core.Coin
import org.bitcoinj.core.NetworkParameters
import org.bitcoinj.core.Sha256Hash
import org.bitcoinj.core.Transaction
import org.bitcoinj.params.TestNet3Params
import org.dash.wallet.common.payments.bip70.PaymentProtocol
import org.bitcoinj.wallet.SendRequest
import org.bitcoinj.wallet.Wallet
import org.bitcoinj.wallet.WalletProtobufSerializer
import de.schildbach.wallet.data.PendingDirectPaymentConfig
import de.schildbach.wallet.data.WalletData
import org.dash.wallet.common.data.NetworkStatus
import org.dash.wallet.common.data.PaymentIntent
import org.dash.wallet.common.services.BlockchainStateProvider
import org.dash.wallet.common.services.PaymentRecoveryMetadata
import org.dash.wallet.common.services.PaymentSubmissionPendingException
import org.dash.wallet.common.services.TransactionMetadataProvider
import org.dash.wallet.common.services.analytics.AnalyticsService
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.FileInputStream
import java.net.HttpURLConnection
import java.util.concurrent.atomic.AtomicInteger
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
    private lateinit var l1SendProbeService: de.schildbach.wallet.service.platform.sdk.L1SendProbeService
    private lateinit var bridgedTransactionFactory: de.schildbach.wallet.service.platform.sdk.SdkBridgedTransactionFactory
    private lateinit var blockchainStateProvider: BlockchainStateProvider
    private lateinit var pendingPaymentConfig: PendingDirectPaymentConfig
    private lateinit var pendingPaymentVerifier: PendingDirectPaymentVerifier
    private lateinit var wallet: Wallet

    /** The verifier's own wipe listener, captured so tearDown can stop its watches. */
    private val wipeListeners = mutableListOf<suspend () -> Unit>()

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
        l1SendProbeService = mockk(relaxed = true)
        // No SDK row observed by default: the IS-lock rescue finds
        // nothing unless a test says otherwise.
        coEvery { l1SendProbeService.observedTxContext(any()) } returns null
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

        // No peers, no sync, nothing observed: the verifier never resolves a quarantined payment
        // on its own unless a test says otherwise.
        blockchainStateProvider = mockk(relaxed = true)
        every { blockchainStateProvider.getNetworkStatus() } returns NetworkStatus.DISCONNECTED
        coEvery { blockchainStateProvider.getState() } returns null
        pendingPaymentConfig = mockk(relaxed = true)
        coEvery { pendingPaymentConfig.getAllOrThrow() } returns emptyList()
        every { walletDataProvider.attachOnWalletWipedListener(any()) } answers { wipeListeners.add(firstArg()) }
        coEvery { sdkL1SendService.cutoverCommitted() } returns false
        // The real engine for broadcast/release/bridge, so those reach the mocked SDK services the
        // tests verify; observation answers from the mocked probe, with the engine treated as live.
        val sdkEngine = SdkPendingPaymentEngine(
            sdkL1SendService,
            mockk(relaxed = true),
            mockk(relaxed = true),
            bridgedTransactionFactory
        )
        val pendingPaymentEngine = object : PendingPaymentEngine by sdkEngine {
            override fun isObservationLive(): Boolean = true
            override suspend fun observation(txIdHex: String): EngineObservation =
                if (l1SendProbeService.observedTxContext(txIdHex) != null) {
                    EngineObservation.OBSERVED
                } else {
                    EngineObservation.ABSENT
                }
        }
        pendingPaymentVerifier = PendingDirectPaymentVerifier(
            walletDataProvider,
            walletApplication,
            blockchainStateProvider,
            metadataProvider,
            pendingPaymentConfig,
            pendingPaymentEngine
        ).apply { pollIntervalMs = 20L }

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
                // The BIP70 route is SDK-only (replace-then-delete policy);
                // the direct-pay tests program this named mock.
                sdkL1SendService,
                // Self-spend grace arming is exercised in
                // SendCoinsTaskRunnerSelfSpendGraceTest.
                mockk(relaxed = true),
                // Row-context reads drive the BIP70 IS-lock timeout
                // rescue; the rescue tests program this named mock.
                l1SendProbeService,
                // Phase 5d bridge factory: reached only under a committed
                // cutover — programmed by the post-cutover BIP70 tests.
                bridgedTransactionFactory,
                pendingPaymentVerifier
            )
        )
        // don't wait the production 30 seconds for network evidence in tests
        sendCoinsTaskRunner.ambiguousSubmissionWaitMs = 500L

        coEvery { sendCoinsTaskRunner.logSendTxEvent(any(), any()) } returns Unit

    }

    @After
    fun tearDown() {
        // Stop this test's watches: the verifier's scope outlives the test.
        runBlocking { wipeListeners.forEach { it() } }
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

    // ============ Direct-pay (SDK deferred — the only route) ============

    /**
     * The signed transaction an SDK deferred build hands back: decodable, because the runner
     * quarantines it, and absent from the fixture wallet, so nothing reads it as already sent.
     */
    private val deferredTx: Transaction by lazy {
        Transaction(networkParams).apply {
            addInput(
                Sha256Hash.of("bip70-deferred-${txCounter.incrementAndGet()}".toByteArray()),
                0,
                org.bitcoinj.script.ScriptBuilder.createEmpty()
            )
            addOutput(Coin.parseCoin("1.0"), Address.fromString(networkParams, "yWdXnYxGbouNoo8yMvcbZmZ3Gdp6BpySxL"))
        }
    }
    private val deferredTxidHex: String by lazy { deferredTx.txId.toString() }

    private fun deferredPayment() = de.schildbach.wallet.service.platform.sdk.SdkDeferredPayment(
        txidHex = deferredTxidHex,
        rawTxBytes = deferredTx.unsafeBitcoinSerialize(),
        feeDuffs = 1000L,
        native = null
    )

    private companion object {
        // static: a per-instance counter would hand every test the same txid
        val txCounter = AtomicInteger(0)
    }

    private fun bip70PaymentIntent(
        address: Address,
        amount: Coin,
        paymentUrl: String,
        paymentRequestHash: ByteArray? = null
    ) = PaymentIntent(
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
        paymentRequestHash,
        null,
        null
    )

    @Test
    fun `directPay builds via SDK, broadcasts on ACK and returns the bridged tx`() = runTest {
        val testAddress = Address.fromString(networkParams, "yWdXnYxGbouNoo8yMvcbZmZ3Gdp6BpySxL")
        val testAmount = Coin.parseCoin("1.0")
        val paymentIntent =
            bip70PaymentIntent(testAddress, testAmount, mockWebServer.url("/payment").toString())

        val payment = deferredPayment()
        val liveTx = mockk<org.bitcoinj.core.Transaction>(relaxed = true)
        // Exercise the refund_to arm: a valid testnet address from the
        // SDK address pool goes into the Payment message.
        coEvery { sdkL1SendService.refundAddressOrNull() } returns
            "yWdXnYxGbouNoo8yMvcbZmZ3Gdp6BpySxL"
        coEvery { sdkL1SendService.buildDeferredPayment(any()) } returns payment
        coEvery { sdkL1SendService.broadcastDeferredPayment(payment) } returns
            de.schildbach.wallet.service.platform.sdk.SdkWriteResult.Broadcast(deferredTxidHex)
        coEvery { bridgedTransactionFactory.bridge(deferredTxidHex, payment.rawTxBytes, any(), any(), any()) } returns
            de.schildbach.wallet.service.platform.sdk.BridgedTxResult.Bridged(
                liveTx, adoptedWalletInstance = false
            )

        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(HttpURLConnection.HTTP_OK)
                .setHeader("Content-Type", PaymentProtocol.MIMETYPE_PAYMENTACK)
                .setBody(okio.Buffer().write(createPaymentAck("Payment accepted")))
        )

        val result = sendCoinsTaskRunner.sendDirectPayment(paymentIntent, "TestService")

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

        // Quarantined before the POST, and the quarantine dropped once the ack was committed.
        coVerifyOrder {
            pendingPaymentConfig.add(match { it.txId == deferredTx.txId }, any())
            pendingPaymentConfig.remove(deferredTx.txId)
        }
        assertFalse(pendingPaymentVerifier.isTracked(deferredTx.txId))
    }

    @Test
    fun `a nacked payment stays quarantined and is reported as pending`() = runTest {
        // A nack is answered only after the payee has had the transaction, so it is no proof the
        // payee will not broadcast it: the reservation is held, not released, and the watch goes on.
        val testAddress = Address.fromString(networkParams, "yWdXnYxGbouNoo8yMvcbZmZ3Gdp6BpySxL")
        val testAmount = Coin.parseCoin("1.0")
        val paymentIntent =
            bip70PaymentIntent(testAddress, testAmount, mockWebServer.url("/payment").toString())

        val payment = deferredPayment()
        coEvery { sdkL1SendService.buildDeferredPayment(any()) } returns payment

        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(HttpURLConnection.HTTP_OK)
                .setHeader("Content-Type", PaymentProtocol.MIMETYPE_PAYMENTACK)
                .setBody(okio.Buffer().write(createPaymentAck("nack")))
        )

        try {
            sendCoinsTaskRunner.sendDirectPayment(paymentIntent)
            fail("Expected PaymentSubmissionPendingException for NACK")
        } catch (e: PaymentSubmissionPendingException) {
            assertEquals(deferredTxidHex, e.txId)
        }

        io.mockk.coVerify(exactly = 0) { sdkL1SendService.releaseDeferredPayment(any()) }
        io.mockk.coVerify(exactly = 0) { sdkL1SendService.broadcastDeferredPayment(any()) }
        coVerify(exactly = 0) { pendingPaymentConfig.remove(any()) }
        assertTrue(pendingPaymentVerifier.isTracked(deferredTx.txId))
    }

    @Test
    fun `an HTTP error after the request leaves the payment quarantined as pending`() = runTest {
        val testAddress = Address.fromString(networkParams, "yWdXnYxGbouNoo8yMvcbZmZ3Gdp6BpySxL")
        val testAmount = Coin.parseCoin("1.0")
        val paymentIntent =
            bip70PaymentIntent(testAddress, testAmount, mockWebServer.url("/payment").toString())

        val payment = deferredPayment()
        coEvery { sdkL1SendService.buildDeferredPayment(any()) } returns payment

        mockWebServer.enqueue(MockResponse().setResponseCode(HttpURLConnection.HTTP_INTERNAL_ERROR))

        try {
            sendCoinsTaskRunner.sendDirectPayment(paymentIntent)
            fail("Expected PaymentSubmissionPendingException for HTTP 500")
        } catch (e: PaymentSubmissionPendingException) {
            assertEquals(deferredTxidHex, e.txId)
        }

        io.mockk.coVerify(exactly = 0) { sdkL1SendService.releaseDeferredPayment(any()) }
        io.mockk.coVerify(exactly = 0) { sdkL1SendService.broadcastDeferredPayment(any()) }
        assertTrue(pendingPaymentVerifier.isTracked(deferredTx.txId))
    }

    @Test
    fun `a delivered payment answered with 503 stays pending and is never replayed`() = runTest {
        // A 503 with Retry-After: 0 would make OkHttp replay the request at the HTTP level; the
        // one-shot body stops that, so the payee is sent the payment exactly once.
        val testAddress = Address.fromString(networkParams, "yWdXnYxGbouNoo8yMvcbZmZ3Gdp6BpySxL")
        val paymentIntent =
            bip70PaymentIntent(testAddress, Coin.parseCoin("1.0"), mockWebServer.url("/payment").toString())
        coEvery { sdkL1SendService.buildDeferredPayment(any()) } returns deferredPayment()

        mockWebServer.enqueue(
            MockResponse().setResponseCode(HttpURLConnection.HTTP_UNAVAILABLE).setHeader("Retry-After", "0")
        )
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(HttpURLConnection.HTTP_OK)
                .setHeader("Content-Type", PaymentProtocol.MIMETYPE_PAYMENTACK)
                .setBody(okio.Buffer().write(createPaymentAck()))
        )

        try {
            sendCoinsTaskRunner.sendDirectPayment(paymentIntent)
            fail("Expected PaymentSubmissionPendingException for HTTP 503")
        } catch (e: PaymentSubmissionPendingException) {
            assertEquals(deferredTxidHex, e.txId)
        }

        assertEquals("the payment must be posted once", 1, mockWebServer.requestCount)
    }

    @Test
    fun `a request that never left the device releases the reservation and drops the quarantine`() = runTest {
        // .invalid is reserved by RFC 2606, so this host never resolves: the payee cannot have
        // received anything, and that is the one failure that frees the inputs again.
        val testAddress = Address.fromString(networkParams, "yWdXnYxGbouNoo8yMvcbZmZ3Gdp6BpySxL")
        val paymentIntent =
            bip70PaymentIntent(testAddress, Coin.parseCoin("1.0"), "http://payment.dash-wallet-test.invalid/pay")
        val payment = deferredPayment()
        coEvery { sdkL1SendService.buildDeferredPayment(any()) } returns payment

        try {
            sendCoinsTaskRunner.sendDirectPayment(paymentIntent)
            fail("Expected the DNS failure to surface")
        } catch (e: PaymentSubmissionPendingException) {
            fail("a request that never left the device is not pending: $e")
        } catch (e: java.net.UnknownHostException) {
            assertNotNull(e)
        }

        io.mockk.coVerify(exactly = 1) { sdkL1SendService.releaseDeferredPayment(payment) }
        coVerify(exactly = 1) { pendingPaymentConfig.remove(deferredTx.txId) }
        assertFalse(pendingPaymentVerifier.isTracked(deferredTx.txId))
    }

    @Test
    fun `the order callback runs before anything is posted`() = runTest {
        val testAddress = Address.fromString(networkParams, "yWdXnYxGbouNoo8yMvcbZmZ3Gdp6BpySxL")
        val paymentIntent =
            bip70PaymentIntent(testAddress, Coin.parseCoin("1.0"), mockWebServer.url("/payment").toString())
        val payment = deferredPayment()
        coEvery { sdkL1SendService.broadcastDeferredPayment(payment) } returns
            de.schildbach.wallet.service.platform.sdk.SdkWriteResult.Broadcast(deferredTxidHex)
        coEvery { bridgedTransactionFactory.bridge(deferredTxidHex, any(), any(), any(), any()) } returns
            de.schildbach.wallet.service.platform.sdk.BridgedTxResult.Bridged(
                mockk(relaxed = true), adoptedWalletInstance = false
            )
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(HttpURLConnection.HTTP_OK)
                .setHeader("Content-Type", PaymentProtocol.MIMETYPE_PAYMENTACK)
                .setBody(okio.Buffer().write(createPaymentAck()))
        )

        var postsSeenByCallback = -1
        var recordedTxId: String? = null
        sendCoinsTaskRunner.sendPrebuiltDirectPayment(payment, paymentIntent, "CTXSpend", null) { txId ->
            postsSeenByCallback = mockWebServer.requestCount
            recordedTxId = txId
        }

        assertEquals(deferredTxidHex, recordedTxId)
        assertEquals("the order must be recorded before the payment is posted", 0, postsSeenByCallback)
    }

    @Test
    fun `an acknowledged gift card payment is attributed before its quarantine is cancelled`() = runTest {
        val testAddress = Address.fromString(networkParams, "yWdXnYxGbouNoo8yMvcbZmZ3Gdp6BpySxL")
        val paymentIntent =
            bip70PaymentIntent(testAddress, Coin.parseCoin("1.0"), mockWebServer.url("/payment").toString())
        val payment = deferredPayment()
        val liveTx = mockk<Transaction>(relaxed = true)
        every { liveTx.txId } returns deferredTx.txId
        coEvery { sdkL1SendService.broadcastDeferredPayment(payment) } returns
            de.schildbach.wallet.service.platform.sdk.SdkWriteResult.Broadcast(deferredTxidHex)
        coEvery { bridgedTransactionFactory.bridge(deferredTxidHex, any(), any(), any(), any()) } returns
            de.schildbach.wallet.service.platform.sdk.BridgedTxResult.Bridged(liveTx, adoptedWalletInstance = false)
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(HttpURLConnection.HTTP_OK)
                .setHeader("Content-Type", PaymentProtocol.MIMETYPE_PAYMENTACK)
                .setBody(okio.Buffer().write(createPaymentAck()))
        )

        sendCoinsTaskRunner.sendPrebuiltDirectPayment(
            payment,
            paymentIntent,
            "CTXSpend",
            PaymentRecoveryMetadata(isGiftCardPurchase = true, merchantIconUrl = "https://icon")
        )

        // the quarantine record is the only durable copy of the provider until this is written
        coVerifyOrder {
            metadataProvider.markGiftCardTransaction(any(), "CTXSpend", "https://icon")
            pendingPaymentConfig.remove(deferredTx.txId)
        }
    }

    @Test
    fun `an acknowledged payment whose display bridge fails is pending and stays quarantined`() = runTest {
        val testAddress = Address.fromString(networkParams, "yWdXnYxGbouNoo8yMvcbZmZ3Gdp6BpySxL")
        val paymentIntent =
            bip70PaymentIntent(testAddress, Coin.parseCoin("1.0"), mockWebServer.url("/payment").toString())
        val payment = deferredPayment()
        coEvery { sdkL1SendService.broadcastDeferredPayment(payment) } returns
            de.schildbach.wallet.service.platform.sdk.SdkWriteResult.Broadcast(deferredTxidHex)
        coEvery { bridgedTransactionFactory.bridge(deferredTxidHex, any(), any(), any(), any()) } returns
            de.schildbach.wallet.service.platform.sdk.BridgedTxResult.NotBridged("wallet unavailable")
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(HttpURLConnection.HTTP_OK)
                .setHeader("Content-Type", PaymentProtocol.MIMETYPE_PAYMENTACK)
                .setBody(okio.Buffer().write(createPaymentAck()))
        )

        try {
            sendCoinsTaskRunner.sendPrebuiltDirectPayment(payment, paymentIntent)
            fail("Expected the acked-but-not-displayed payment to be reported")
        } catch (e: PaymentSubmissionPendingException) {
            // callers that keep the order and withhold a retry catch this type
            assertTrue(e is SendCoinsTaskRunner.Bip70AckedDisplayException)
        }

        // acked: consumed by the broadcast, never released, and the record stays for a later commit
        io.mockk.coVerify(exactly = 1) { sdkL1SendService.broadcastDeferredPayment(payment) }
        io.mockk.coVerify(exactly = 0) { sdkL1SendService.releaseDeferredPayment(any()) }
        coVerify(exactly = 0) { pendingPaymentConfig.remove(any()) }
    }

    @Test
    fun `a quarantined payment records which invoice it was for`() = runTest {
        val testAddress = Address.fromString(networkParams, "yWdXnYxGbouNoo8yMvcbZmZ3Gdp6BpySxL")
        val requestHash = ByteArray(32) { 7 }
        val paymentIntent = bip70PaymentIntent(
            testAddress, Coin.parseCoin("1.0"), mockWebServer.url("/payment").toString(), requestHash
        )
        coEvery { sdkL1SendService.buildDeferredPayment(any()) } returns deferredPayment()
        mockWebServer.enqueue(MockResponse().setResponseCode(HttpURLConnection.HTTP_INTERNAL_ERROR))

        try {
            sendCoinsTaskRunner.sendDirectPayment(paymentIntent)
            fail("Expected PaymentSubmissionPendingException")
        } catch (e: PaymentSubmissionPendingException) {
            assertNotNull(e)
        }

        coVerify {
            pendingPaymentConfig.add(
                match { it.paymentRequestId == org.dash.wallet.common.util.Constants.HEX.encode(requestHash) },
                any()
            )
        }
    }

    @Test
    fun `a payment whose wallet was wiped before submission is refused and released`() = runTest {
        val testAddress = Address.fromString(networkParams, "yWdXnYxGbouNoo8yMvcbZmZ3Gdp6BpySxL")
        val paymentIntent =
            bip70PaymentIntent(testAddress, Coin.parseCoin("1.0"), mockWebServer.url("/payment").toString())
        val payment = deferredPayment()
        // a wipe has started on the installed wallet
        runBlocking { wipeListeners.forEach { it() } }

        try {
            sendCoinsTaskRunner.sendPrebuiltDirectPayment(payment, paymentIntent)
            fail("Expected the payment to be refused")
        } catch (e: IllegalStateException) {
            assertNotNull(e)
        }

        assertEquals("nothing may be posted for a wiped wallet", 0, mockWebServer.requestCount)
        io.mockk.coVerify(exactly = 1) { sdkL1SendService.releaseDeferredPayment(payment) }
    }

    @Test
    fun `directPay completes as paid when the engine observes the server broadcast`() = runBlocking {
        val testAddress = Address.fromString(networkParams, "yWdXnYxGbouNoo8yMvcbZmZ3Gdp6BpySxL")
        val testAmount = Coin.parseCoin("1.0")
        val paymentIntent =
            bip70PaymentIntent(testAddress, testAmount, mockWebServer.url("/payment").toString())

        val payment = deferredPayment()
        val liveTx = mockk<org.bitcoinj.core.Transaction>(relaxed = true)
        coEvery { sdkL1SendService.buildDeferredPayment(any()) } returns payment
        // The POST fails at the transport layer…
        mockWebServer.enqueue(MockResponse().setResponseCode(HttpURLConnection.HTTP_INTERNAL_ERROR))
        // …but the engine then OBSERVES the tx on the network at mempool
        // context — the BIP70 server broadcast it despite the failed
        // response. Context 0 is the field-observed arrival state (the
        // pinned engine never surfaces an InstantSend context), and a row
        // can only exist for a tx that reached the network: our own build
        // persists nothing.
        coEvery { l1SendProbeService.observedTxContext(deferredTxidHex) } returns 0
        coEvery { sdkL1SendService.broadcastDeferredPayment(payment) } returns
            de.schildbach.wallet.service.platform.sdk.SdkWriteResult.Broadcast(deferredTxidHex)
        coEvery { bridgedTransactionFactory.bridge(deferredTxidHex, any(), any(), any(), any()) } returns
            de.schildbach.wallet.service.platform.sdk.BridgedTxResult.Bridged(
                liveTx, adoptedWalletInstance = false
            )

        // real time here: the watch runs on its own scope, and its first poll includes MockK's
        // first-call warm-up, which can outlast the 500 ms the other tests allow
        sendCoinsTaskRunner.ambiguousSubmissionWaitMs = 10_000L
        val result = sendCoinsTaskRunner.sendDirectPayment(paymentIntent)

        // Completed as PAID by the pending-payment watch: the reservation was consumed, never
        // released, and the tx was committed through the bridge.
        assertEquals(deferredTx.txId, result.txId)
        io.mockk.coVerify(exactly = 1) { bridgedTransactionFactory.bridge(deferredTxidHex, any(), any(), any(), any()) }
        io.mockk.coVerify(exactly = 0) { sdkL1SendService.releaseDeferredPayment(any()) }
        io.mockk.coVerify(exactly = 1) { sdkL1SendService.broadcastDeferredPayment(payment) }
    }

    @Test
    fun `directPay fails closed on a non-address output before building`() = runTest {
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

        try {
            sendCoinsTaskRunner.sendDirectPayment(paymentIntent)
            fail("Expected IllegalStateException for a non-address output")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("not SDK-routable"))
        }

        io.mockk.coVerify(exactly = 0) { sdkL1SendService.buildDeferredPayment(any()) }
        assertEquals(0, mockWebServer.requestCount)
    }

    @Test
    fun `deferred build mirrors the reservation into wallet output locks until release`() = runTest {
        val testAddress = Address.fromString(networkParams, "yWdXnYxGbouNoo8yMvcbZmZ3Gdp6BpySxL")
        val testAmount = Coin.parseCoin("1.0")
        val paymentIntent =
            bip70PaymentIntent(testAddress, testAmount, mockWebServer.url("/payment").toString())

        // A real fixture-wallet tx gives the mirror real input outpoints —
        // the ones a concurrent dashj-side spender (manual send, CoinJoin
        // mixer) must not double-select while the SDK holds them reserved.
        val realTx = wallet.getTransactions(false).first { it.inputs.isNotEmpty() }
        val payment = de.schildbach.wallet.service.platform.sdk.SdkDeferredPayment(
            txidHex = deferredTxidHex,
            rawTxBytes = realTx.unsafeBitcoinSerialize(),
            feeDuffs = 260L,
            native = null
        )
        coEvery { sdkL1SendService.buildDeferredPayment(any()) } returns payment

        val built = sendCoinsTaskRunner.buildDeferredBip70Payment(paymentIntent)
        val outpoints = realTx.inputs.map { it.outpoint }
        outpoints.forEach { assertTrue("locked after build: $it", wallet.isLockedOutput(it)) }

        sendCoinsTaskRunner.releaseDeferredPayment(built)
        outpoints.forEach { assertTrue("unlocked after release: $it", !wallet.isLockedOutput(it)) }
        io.mockk.coVerify(exactly = 1) { sdkL1SendService.releaseDeferredPayment(payment) }
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

    // ==================== acknowledgement vs a wipe (review 4245734681) ====================

    @Test
    fun `an acknowledgement that arrives after a wipe is not completed on the replacement wallet`() = runTest {
        val testAddress = Address.fromString(networkParams, "yWdXnYxGbouNoo8yMvcbZmZ3Gdp6BpySxL")
        val paymentIntent =
            bip70PaymentIntent(testAddress, Coin.parseCoin("1.0"), mockWebServer.url("/payment").toString())
        val payment = deferredPayment()
        // a real wallet: a relaxed mock hands dashj a mock Context and poisons the tests after it
        val replacement = Wallet.createDeterministic(networkParams, org.bitcoinj.script.Script.ScriptType.P2PKH)
        // stubbed so a regression fails on the assertions below rather than inside MockK
        coEvery { sdkL1SendService.broadcastDeferredPayment(payment) } returns
            de.schildbach.wallet.service.platform.sdk.SdkWriteResult.Broadcast(deferredTxidHex)
        coEvery { bridgedTransactionFactory.bridge(any(), any(), any(), any(), any()) } returns
            de.schildbach.wallet.service.platform.sdk.BridgedTxResult.NotBridged("test")
        // the wipe finishes and installs a replacement while the payee is answering
        mockWebServer.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
            override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest): MockResponse {
                every { walletDataProvider.wallet } returns replacement
                return MockResponse()
                    .setResponseCode(HttpURLConnection.HTTP_OK)
                    .setHeader("Content-Type", PaymentProtocol.MIMETYPE_PAYMENTACK)
                    .setBody(okio.Buffer().write(createPaymentAck()))
            }
        }

        try {
            sendCoinsTaskRunner.sendPrebuiltDirectPayment(payment, paymentIntent)
            fail("an acknowledgement for a wiped wallet was completed")
        } catch (e: IllegalStateException) {
            assertNotNull(e)
        }

        // nothing may be broadcast through, or committed to, the wallet that replaced the signer
        io.mockk.coVerify(exactly = 0) { sdkL1SendService.broadcastDeferredPayment(any()) }
        io.mockk.coVerify(exactly = 0) { bridgedTransactionFactory.bridge(any(), any(), any(), any(), any()) }
        assertNull(replacement.getTransaction(deferredTx.txId))
    }

    @Test
    fun `an acknowledged payment is committed to the wallet that signed it`() = runTest {
        val testAddress = Address.fromString(networkParams, "yWdXnYxGbouNoo8yMvcbZmZ3Gdp6BpySxL")
        val paymentIntent =
            bip70PaymentIntent(testAddress, Coin.parseCoin("1.0"), mockWebServer.url("/payment").toString())
        val payment = deferredPayment()
        val liveTx = mockk<Transaction>(relaxed = true)
        every { liveTx.txId } returns deferredTx.txId
        coEvery { sdkL1SendService.broadcastDeferredPayment(payment) } returns
            de.schildbach.wallet.service.platform.sdk.SdkWriteResult.Broadcast(deferredTxidHex)
        coEvery { bridgedTransactionFactory.bridge(deferredTxidHex, any(), any(), any(), any()) } returns
            de.schildbach.wallet.service.platform.sdk.BridgedTxResult.Bridged(liveTx, adoptedWalletInstance = false)
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(HttpURLConnection.HTTP_OK)
                .setHeader("Content-Type", PaymentProtocol.MIMETYPE_PAYMENTACK)
                .setBody(okio.Buffer().write(createPaymentAck()))
        )

        sendCoinsTaskRunner.sendPrebuiltDirectPayment(payment, paymentIntent)

        // bound to the captured origin, not to whatever walletData.wallet reads at bridge time
        io.mockk.coVerify(exactly = 1) { bridgedTransactionFactory.bridge(deferredTxidHex, any(), any(), any(), wallet) }
    }

    // ==================== plain dash: payments after the cutover (review 4250735559) ==========

    @Test
    fun `a plain dash URI after the cutover records the order and a recovery record before broadcasting`() = runTest {
        // A PiggyCards-style order pays a bare dash: address. Once the SDK owns L1 the payment must
        // be built and reserved first, the order and the pending-payment record written, and only
        // then broadcast: a process death between the broadcast and the order write would
        // otherwise leave a paid order with nothing to redeem it against.
        coEvery { sdkL1SendService.cutoverCommitted() } returns true
        val payment = deferredPayment()
        val liveTx = mockk<Transaction>(relaxed = true)
        every { liveTx.txId } returns deferredTx.txId
        coEvery { sdkL1SendService.buildDeferredPayment(any()) } returns payment
        var callbackRan = false
        var recordWritten = false
        coEvery { pendingPaymentConfig.add(any(), any()) } answers { recordWritten = true }
        coEvery { sdkL1SendService.broadcastDeferredPayment(payment) } answers {
            assertTrue("the order must be recorded before the broadcast", callbackRan)
            assertTrue("the recovery record must exist before the broadcast", recordWritten)
            de.schildbach.wallet.service.platform.sdk.SdkWriteResult.Broadcast(deferredTxidHex)
        }
        coEvery { bridgedTransactionFactory.bridge(deferredTxidHex, any(), any(), any(), any()) } returns
            de.schildbach.wallet.service.platform.sdk.BridgedTxResult.Bridged(liveTx, adoptedWalletInstance = false)
        // the old one-step SDK send, which broadcasts before the caller can record anything
        coEvery { sdkL1SendService.sendToAddress(any(), any(), any(), any()) } returns
            de.schildbach.wallet.service.platform.sdk.SdkWriteResult.Broadcast(deferredTxidHex)

        val result = sendCoinsTaskRunner.payWithDashUrlTx(
            "dash:yWdXnYxGbouNoo8yMvcbZmZ3Gdp6BpySxL?amount=1.0",
            "PiggyCards",
            PaymentRecoveryMetadata(isGiftCardPurchase = true, merchantIconUrl = "https://icon")
        ) { txId ->
            assertEquals(deferredTxidHex, txId)
            callbackRan = true
        }

        assertEquals(deferredTx.txId, result.txId)
        io.mockk.coVerify(exactly = 1) { sdkL1SendService.broadcastDeferredPayment(payment) }
        io.mockk.coVerify(exactly = 0) { sdkL1SendService.sendToAddress(any(), any(), any(), any()) }
        // resolved: attributed, then the record dropped
        coVerifyOrder {
            metadataProvider.markGiftCardTransaction(any(), "PiggyCards", "https://icon")
            pendingPaymentConfig.remove(deferredTx.txId)
        }
    }

    @Test
    fun `a plain dash URI after the cutover that is refused before the network releases and reports it`() = runTest {
        coEvery { sdkL1SendService.cutoverCommitted() } returns true
        val payment = deferredPayment()
        coEvery { sdkL1SendService.buildDeferredPayment(any()) } returns payment
        coEvery { sdkL1SendService.broadcastDeferredPayment(payment) } returns
            de.schildbach.wallet.service.platform.sdk.SdkWriteResult.NotBroadcast("refused pre-network")

        try {
            sendCoinsTaskRunner.payWithDashUrlTx("dash:yWdXnYxGbouNoo8yMvcbZmZ3Gdp6BpySxL?amount=1.0", "PiggyCards")
            fail("a refused broadcast was reported as sent")
        } catch (e: PaymentSubmissionPendingException) {
            fail("a definitive refusal is not pending: $e")
        } catch (e: IllegalStateException) {
            assertNotNull(e)
        }

        io.mockk.coVerify(exactly = 1) { sdkL1SendService.releaseDeferredPayment(payment) }
        coVerify(exactly = 1) { pendingPaymentConfig.remove(deferredTx.txId) }
    }

    @Test
    fun `a plain dash URI after the cutover whose broadcast outcome is unknown stays pending`() = runTest {
        coEvery { sdkL1SendService.cutoverCommitted() } returns true
        val payment = deferredPayment()
        coEvery { sdkL1SendService.buildDeferredPayment(any()) } returns payment
        coEvery { sdkL1SendService.broadcastDeferredPayment(payment) } returns
            de.schildbach.wallet.service.platform.sdk.SdkWriteResult.Ambiguous(RuntimeException("timeout"))

        try {
            sendCoinsTaskRunner.payWithDashUrlTx("dash:yWdXnYxGbouNoo8yMvcbZmZ3Gdp6BpySxL?amount=1.0", "PiggyCards")
            fail("an unknown broadcast outcome was reported as definite")
        } catch (e: PaymentSubmissionPendingException) {
            assertEquals(deferredTxidHex, e.txId)
        }

        io.mockk.coVerify(exactly = 0) { sdkL1SendService.releaseDeferredPayment(any()) }
        coVerify(exactly = 0) { pendingPaymentConfig.remove(any()) }
        assertTrue(pendingPaymentVerifier.isTracked(deferredTx.txId))
    }
}
