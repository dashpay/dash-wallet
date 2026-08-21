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

package de.schildbach.wallet.service.platform.sdk

import android.app.Application
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Host-JVM tests for the pooled spendable figure's pure pieces
 * ([parseBip44SpendableDuffs] / [pooledSpendableDuffs]) — the post-cutover
 * send-screen max quote AND the send-all floor's balance base: the
 * spendable sum over exactly the accounts the engine's `ALL_SPENDABLE`
 * funding pools (v41int19, dashpay/platform#4329) — BIP44 + BIP32 at the
 * funding index plus every DashPay receival account, delivered in ONE
 * pooled transaction. CoinJoin (typeTag 1) and watch-only (typeTag 13)
 * never count.
 *
 * Robolectric runner: the parsers use `org.json`, which the plain
 * unit-test android.jar stubs out (returnDefaultValues would silently
 * parse everything as empty).
 *
 * Row shape per the JNI bridge's `walletManagerAccountBalances`
 * (rs-unified-sdk-jni `dashpay.rs`); `typeTag` values per
 * `AccountTypeTagFFI` (rs-platform-wallet-ffi `wallet_restore_types.rs`:
 * Standard = 0, CoinJoin = 1, DashpayReceivingFunds = 12,
 * DashpaySendingFunds = 13); `standardTag` per `StandardAccountTypeTagFFI`
 * (Bip44 = 0, Bip32 = 1).
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [29], manifest = Config.NONE)
class SdkL1SendMaxSendableParseTest {

    private fun row(
        typeTag: Int,
        standardTag: Int = 0,
        index: Int = 0,
        confirmed: Long = 0L,
        unconfirmed: Long = 0L
    ): String =
        """{"typeTag":$typeTag,"standardTag":$standardTag,"index":$index,
        "registrationIndex":0,"keyClass":0,
        "userIdentityId":"${"00".repeat(32)}","friendIdentityId":"${"22".repeat(32)}",
        "confirmed":$confirmed,"unconfirmed":$unconfirmed,"immature":0,
        "locked":0,"keysUsed":0,"keysTotal":40,"derivationPath":null}"""

    private fun bip44Row(confirmed: Long, unconfirmed: Long = 0L, index: Int = 0): String =
        row(typeTag = 0, standardTag = 0, index = index, confirmed = confirmed, unconfirmed = unconfirmed)

    private fun bip32Row(confirmed: Long, unconfirmed: Long = 0L, index: Int = 0): String =
        row(typeTag = 0, standardTag = 1, index = index, confirmed = confirmed, unconfirmed = unconfirmed)

    private fun receivalRow(confirmed: Long, unconfirmed: Long = 0L): String =
        row(typeTag = 12, confirmed = confirmed, unconfirmed = unconfirmed)

    // ── parseBip44SpendableDuffs ──────────────────────────────────────

    @Test
    fun bip44Spendable_sumsConfirmedAndUnconfirmed() {
        val json = "[${bip44Row(confirmed = 5_000_000L, unconfirmed = 250_000L)}]"
        assertEquals(5_250_000L, parseBip44SpendableDuffs(json))
    }

    @Test
    fun bip44Spendable_ignoresNonZeroIndexAndOtherAccountTypes() {
        val json = "[${bip44Row(confirmed = 999L, index = 1)},${receivalRow(confirmed = 777L)}," +
            "${bip44Row(confirmed = 42L)}]"
        assertEquals(42L, parseBip44SpendableDuffs(json))
    }

    @Test
    fun bip44Spendable_missingRowOrMalformed_isUnknownNotZero() {
        // A snapshot without a BIP44 index-0 row cannot AFFIRM the balance —
        // null (unknown), never 0, so the display falls back to the
        // wallet-wide total instead of quoting a zero max.
        assertNull(parseBip44SpendableDuffs("[${receivalRow(confirmed = 777L)}]"))
        assertNull(parseBip44SpendableDuffs(null))
        assertNull(parseBip44SpendableDuffs(""))
        assertNull(parseBip44SpendableDuffs("not json"))
    }

    // ── pooledSpendableDuffs ──────────────────────────────────────────

    @Test
    fun pooled_sumsBip44Bip32AndEveryReceivalAccount() {
        // The pooled ALL_SPENDABLE set delivers everything in ONE
        // transaction — no per-account sweep-fee headrooms anymore.
        val json = "[${bip44Row(confirmed = 5_000_000L, unconfirmed = 50_000L)}," +
            "${bip32Row(confirmed = 300_000L)}," +
            "${receivalRow(confirmed = 2_000_000L)},${receivalRow(confirmed = 1_000L)}]"
        assertEquals(5_050_000L + 300_000L + 2_000_000L + 1_000L, pooledSpendableDuffs(json))
    }

    @Test
    fun pooled_countsReceivalUnconfirmed_likeEveryPooledAccount() {
        // Pooling removed the sweeps, so receival funds follow the same
        // spendable convention as BIP44 (confirmed + unconfirmed — dashj's
        // ESTIMATED display figure; the engine funds them once IS-locked).
        val json = "[${bip44Row(confirmed = 1_000_000L)}," +
            "${receivalRow(confirmed = 100_000L, unconfirmed = 400_000L)}]"
        assertEquals(1_500_000L, pooledSpendableDuffs(json))
    }

    @Test
    fun pooled_excludesCoinJoinWatchOnlyAndNonFundingIndexes() {
        val json = "[${bip44Row(confirmed = 1_000_000L)}," +
            "${row(typeTag = 1, confirmed = 900_000L)}," + // CoinJoin: separate privacy domain
            "${row(typeTag = 13, confirmed = 800_000L)}," + // watch-only: not signable
            "${bip44Row(confirmed = 700_000L, index = 1)}," + // non-funding index
            "${bip32Row(confirmed = 600_000L, index = 2)}]" // non-funding index
        assertEquals(1_000_000L, pooledSpendableDuffs(json))
    }

    @Test
    fun pooled_bip44Only_equalsBip44Spendable() {
        val json = "[${bip44Row(confirmed = 5_000_000L, unconfirmed = 100_000L)}]"
        assertEquals(5_100_000L, pooledSpendableDuffs(json))
    }

    @Test
    fun pooled_withoutABip44Row_isUnknownNotZero() {
        assertNull(pooledSpendableDuffs("[${receivalRow(confirmed = 2_000_000L)}]"))
        assertNull(pooledSpendableDuffs(null))
        assertNull(pooledSpendableDuffs("not json"))
    }
}
