package de.schildbach.wallet.util

import de.schildbach.wallet.Constants
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * DashConnect is testnet-only: the Tools entry and dash-key QR interception are both
 * gated on [Constants.SUPPORTS_CONNECT], so it must be false in the production flavour.
 */
class ConnectFlagTest {
    @Test
    fun supportsConnect_matchesFlavour() {
        val expected = !Constants.IS_PROD_BUILD
        assertEquals(
            "SUPPORTS_CONNECT should be ${expected} for flavour ${de.schildbach.wallet_test.BuildConfig.FLAVOR}",
            expected,
            Constants.SUPPORTS_CONNECT
        )
    }
}
