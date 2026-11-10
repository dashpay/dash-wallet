package de.schildbach.wallet.util

import de.schildbach.wallet.Constants
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * DashConnect is built into every flavour, mainnet included; whether it is offered depends on the
 * network running Platform protocol version 14 (see ConnectAvailability).
 */
class ConnectFlagTest {
    @Test
    fun supportsConnect_matchesFlavour() {
        val expected = true
        assertEquals(
            "SUPPORTS_CONNECT should be ${expected} for flavour ${de.schildbach.wallet_test.BuildConfig.FLAVOR}",
            expected,
            Constants.SUPPORTS_CONNECT
        )
    }
}
