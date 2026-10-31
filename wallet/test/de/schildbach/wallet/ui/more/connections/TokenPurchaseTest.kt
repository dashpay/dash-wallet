package de.schildbach.wallet.ui.more.connections

import android.app.Application
import androidx.datastore.preferences.core.stringPreferencesKey
import de.schildbach.wallet.ui.more.connections.protocol.DashConnectNetwork
import de.schildbach.wallet.ui.more.connections.protocol.DashStRequest
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.dash.wallet.common.WalletDataProvider
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.math.BigInteger

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28], manifest = Config.NONE)
class TokenPurchaseTest {
    @Test fun `price and quantity retain unsigned 64 bit precision`() {
        val approval = TokenPurchaseApproval(DashStRequest(byteArrayOf(1), DashConnectNetwork.DEVNET),
            "scope", "id", "owner", "contract", "token", BigInteger("18446744073709551615"),
            BigInteger("50000000000"), "Token", 8, byteArrayOf(1))
        assertEquals("184467440737.09551615", approval.quantity)
        assertEquals("0.5", approval.maximumDash)
        assertThrows(IllegalArgumentException::class.java) { approval.copy(amount = BigInteger.ZERO) }
        assertThrows(IllegalArgumentException::class.java) { approval.copy(maximumCredits = BigInteger.ONE.shiftLeft(64)) }
    }

    @Test fun `uncertain and submitted receipts prevent replay and corruption is not an empty store`() = runBlocking {
        val config = DashConnectConfig(RuntimeEnvironment.getApplication(), mockk<WalletDataProvider>(relaxed = true))
        config.clearAll()
        assertFalse(config.purchaseWasSubmitted("scope:request"))
        val receipt = JSONObject().put("id", "scope:request").put("status", "outcomeUnknown")
        config.savePurchase(receipt)
        assertTrue(config.purchaseWasSubmitted("scope:request"))
        assertFalse(config.purchaseWasSubmitted("another-scope:request"))
        config.savePurchase(receipt.put("status", "submitted"))
        assertTrue(config.purchaseWasSubmitted("scope:request"))
        config.set(stringPreferencesKey("dash_st_purchases"), "not-json")
        assertThrows(Exception::class.java) { runBlocking { config.purchaseWasSubmitted("scope:request") } }
        Unit
    }

    @Test fun `canonical receipts block equivalent envelopes but allow a new signed operation`() = runBlocking {
        val config = DashConnectConfig(RuntimeEnvironment.getApplication(), mockk<WalletDataProvider>(relaxed = true))
        config.clearAll()
        val terms = JSONObject().put("scope", "scope").put("ownerId", "owner")
            .put("contractId", "contract").put("tokenId", "token").put("amount", "100").put("maximumCredits", "500")
        config.savePurchase(JSONObject(terms.toString()).put("id", "scope:canonical-operation-42")
            .put("canonicalPurchase", true).put("status", "outcomeUnknown"))
        // Both byte-distinct envelopes decode to this native operation ID.
        assertTrue(config.purchaseWasSubmitted("scope:canonical-operation-42", terms))
        assertTrue(config.purchaseWasSubmitted("scope:canonical-operation-42", JSONObject(terms.toString()).put("name", "Renamed")))
        // A later purchase carries a different nonce, which the SDK now preserves on chain.
        assertFalse(config.purchaseWasSubmitted("scope:canonical-operation-43", terms))
        config.savePurchase(JSONObject(terms.toString()).put("id", "scope:old-raw-byte-hash")
            .put("status", "outcomeUnknown"))
        // Old SDK receipts lost the submitted nonce. They cannot safely be migrated into a new intent.
        assertTrue(config.purchaseWasSubmitted("scope:canonical-operation-43", terms))
        val anotherWallet = JSONObject(terms.toString()).put("scope", "other-scope")
        assertFalse(config.purchaseWasSubmitted("other-scope:canonical-operation-43", anotherWallet))
        Unit
    }
}
