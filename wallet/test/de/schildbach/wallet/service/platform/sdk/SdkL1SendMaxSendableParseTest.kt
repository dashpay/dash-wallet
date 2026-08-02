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
 * Host-JVM tests for the MAX-send display figure's pure pieces
 * ([parseBip44SpendableDuffs] / [maxSendableDuffs]) — the post-cutover
 * send-screen quote: BIP44 spendable + Σ sweepable DashPay receival
 * accounts' confirmed, net of the per-sweep fee headroom (what the
 * sweep-then-drain send-all actually delivers, gross of the drain fee).
 *
 * Robolectric runner: the parsers use `org.json`, which the plain
 * unit-test android.jar stubs out (returnDefaultValues would silently
 * parse everything as empty).
 *
 * Row shape per the JNI bridge's `walletManagerAccountBalances`
 * (rs-unified-sdk-jni `dashpay.rs`); `typeTag` values per
 * `AccountTypeTagFFI` (rs-platform-wallet-ffi `wallet_restore_types.rs`:
 * Standard = 0, DashpayReceivingFunds = 12).
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [29], manifest = Config.NONE)
class SdkL1SendMaxSendableParseTest {

    private val headroom = RECEIVAL_FALLBACK_FEE_HEADROOM_DUFFS

    private fun bip44Row(confirmed: Long, unconfirmed: Long = 0L, index: Int = 0): String =
        """{"typeTag":0,"standardTag":0,"index":$index,
        "registrationIndex":0,"keyClass":0,
        "userIdentityId":"${"00".repeat(32)}","friendIdentityId":"${"00".repeat(32)}",
        "confirmed":$confirmed,"unconfirmed":$unconfirmed,"immature":0,
        "locked":0,"keysUsed":0,"keysTotal":40,"derivationPath":null}"""

    private fun receivalRow(
        confirmed: Long,
        unconfirmed: Long = 0L,
        derivationPath: String? = "m/9'/1'/15'/0'/0x11/0x22"
    ): String {
        val path = derivationPath?.let { "\"$it\"" } ?: "null"
        return """{"typeTag":12,"standardTag":0,"index":0,
        "registrationIndex":0,"keyClass":0,
        "userIdentityId":"${"11".repeat(32)}","friendIdentityId":"${"22".repeat(32)}",
        "confirmed":$confirmed,"unconfirmed":$unconfirmed,"immature":0,
        "locked":0,"keysUsed":0,"keysTotal":40,"derivationPath":$path}"""
    }

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

    // ── maxSendableDuffs ──────────────────────────────────────────────

    @Test
    fun maxSendable_addsSweepableReceivalNetOfHeadroom() {
        // The on-device bug's shape: main-account funds + 0.02 DASH received
        // from a contact. The quote must count the receival funds — net of
        // the per-sweep fee headroom, so the sweep-then-drain always
        // delivers at least the quote (minus the drain fee it absorbs).
        val json = "[${bip44Row(confirmed = 5_000_000L)},${receivalRow(confirmed = 2_000_000L)}]"
        assertEquals(5_000_000L + 2_000_000L - headroom, maxSendableDuffs(json))
    }

    @Test
    fun maxSendable_sumsAcrossMultipleReceivalAccounts() {
        val json = "[${bip44Row(confirmed = 1_000_000L, unconfirmed = 50_000L)}," +
            "${receivalRow(confirmed = 2_000_000L)},${receivalRow(confirmed = 300_000L)}]"
        assertEquals(
            1_050_000L + (2_000_000L - headroom) + (300_000L - headroom),
            maxSendableDuffs(json)
        )
    }

    @Test
    fun maxSendable_excludesDustAndPathlessReceivalRows_andReceivalUnconfirmed() {
        // Non-sweepable receival funds can never be delivered by a max send:
        // dust at/below the headroom (a sweep's output must be positive),
        // rows the engine reports no fundingPath for, and unconfirmed
        // receival funds (sweeps spend confirmed only) — none may inflate
        // the quote.
        val json = "[${bip44Row(confirmed = 1_000_000L)}," +
            "${receivalRow(confirmed = headroom)}," + // dust: not sweepable
            "${receivalRow(confirmed = 500_000L, derivationPath = null)}," + // pathless
            "${receivalRow(confirmed = 0L, unconfirmed = 400_000L)}]" // unconfirmed only
        assertEquals(1_000_000L, maxSendableDuffs(json))
    }

    @Test
    fun maxSendable_bip44Only_equalsBip44Spendable() {
        val json = "[${bip44Row(confirmed = 5_000_000L, unconfirmed = 100_000L)}]"
        assertEquals(5_100_000L, maxSendableDuffs(json))
    }

    @Test
    fun maxSendable_withoutABip44Row_isUnknownNotZero() {
        assertNull(maxSendableDuffs("[${receivalRow(confirmed = 2_000_000L)}]"))
        assertNull(maxSendableDuffs(null))
        assertNull(maxSendableDuffs("not json"))
    }
}
