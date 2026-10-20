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
 * Host-JVM tests for the mixed-funds detector's pure pieces: the
 * CoinJoin-row parse of the `accountBalances` JSON snapshot
 * ([parseCoinJoinConfirmedDuffs]) and the cutover-aware SDK/dashj source
 * selection ([mixedFundsDuffs]).
 *
 * Robolectric runner: the parser uses `org.json`, which the plain
 * unit-test android.jar stubs out (returnDefaultValues would silently
 * parse everything as empty).
 *
 * Row shape per the JNI bridge's `walletManagerAccountBalances`
 * (rs-unified-sdk-jni `dashpay.rs`); `typeTag` values per
 * `AccountTypeTagFFI` (rs-platform-wallet-ffi `wallet_restore_types.rs`:
 * Standard = 0, CoinJoin = 1, DashpayReceivingFunds = 12).
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [29], manifest = Config.NONE)
class CoinJoinFundsMigrationServiceTest {

    private fun row(
        typeTag: Int,
        index: Int = 0,
        confirmed: Long = 0L,
        standardTag: Int = 0
    ): String = """{"typeTag":$typeTag,"standardTag":$standardTag,"index":$index,
        "registrationIndex":0,"keyClass":0,
        "userIdentityId":"${"00".repeat(32)}","friendIdentityId":"${"00".repeat(32)}",
        "confirmed":$confirmed,"unconfirmed":0,"immature":0,
        "locked":0,"keysUsed":0,"keysTotal":40,"derivationPath":null}"""

    @Test
    fun promptHardSuppression_isDisabledForCutoverRecovery() {
        assertEquals(false, MIXED_FUNDS_PROMPT_HARD_SUPPRESSED)
    }

    // ── parseCoinJoinConfirmedDuffs ───────────────────────────────────

    @Test
    fun parse_coinJoinRow_returnsItsConfirmedBalance() {
        val json = "[${row(typeTag = 0, confirmed = 5_000L)}," + // BIP44
            "${row(typeTag = ACCOUNT_TYPE_TAG_COIN_JOIN, confirmed = 123_456_789L)}," +
            "${row(typeTag = 12, confirmed = 77L)}]" // DashPay receival
        assertEquals(123_456_789L, parseCoinJoinConfirmedDuffs(json))
    }

    @Test
    fun parse_coinJoinRowWithZeroBalance_affirmativelyReportsZero() {
        val json = "[${row(typeTag = ACCOUNT_TYPE_TAG_COIN_JOIN, confirmed = 0L)}]"
        assertEquals(0L, parseCoinJoinConfirmedDuffs(json))
    }

    @Test
    fun parse_onlyIndexZeroCounts() {
        // Both migrations can only move account 0's coins (the shield's
        // fundingPath and the drain both target index 0), so a non-zero
        // index must never contribute to the quoted amount.
        val json = "[${row(typeTag = ACCOUNT_TYPE_TAG_COIN_JOIN, index = 1, confirmed = 999L)}," +
            "${row(typeTag = ACCOUNT_TYPE_TAG_COIN_JOIN, index = 0, confirmed = 42L)}]"
        assertEquals(42L, parseCoinJoinConfirmedDuffs(json))
    }

    @Test
    fun parse_noCoinJoinRow_isUnknownNotZero() {
        // A snapshot without a CoinJoin index-0 row cannot AFFIRM the
        // account's balance — null (unknown), never 0, so the merge falls
        // back to the dashj source alone.
        val json = "[${row(typeTag = 0, confirmed = 5_000L)}]"
        assertNull(parseCoinJoinConfirmedDuffs(json))
    }

    @Test
    fun parse_coinJoinTagIsNotTheBuilderSelectorValue() {
        // CoreTransactionBuilder.AccountType.COIN_JOIN's numeric value is 2
        // (the builder's 0=BIP44/1=BIP32/2=CoinJoin selector) — a row tagged
        // 2 in THIS json is AccountTypeTagFFI::IdentityRegistration and must
        // not be counted as CoinJoin.
        val json = "[${row(typeTag = 2, confirmed = 999L)}]"
        assertNull(parseCoinJoinConfirmedDuffs(json))
    }

    @Test
    fun parse_missingOrNonNumericFields_areUnknownNotZero() {
        val cj = ACCOUNT_TYPE_TAG_COIN_JOIN
        val bad = listOf(
            """[{"typeTag":$cj,"index":0}]""", // confirmed missing
            """[{"typeTag":$cj,"index":0,"confirmed":"5"}]""", // a string
            """[{"typeTag":$cj,"index":0,"confirmed":1.5}]""", // fractional
            """[{"typeTag":$cj,"index":0,"confirmed":null}]""",
            """[{"typeTag":$cj,"index":0,"confirmed":-1}]""", // negative
            """[{"typeTag":$cj,"confirmed":5}]""", // index missing
            """[{"index":0,"confirmed":5},{"typeTag":$cj,"index":0,"confirmed":5}]""" // a row without its tag
        )
        for (json in bad) assertNull(json, parseCoinJoinConfirmedDuffs(json))
        assertEquals(5L, parseCoinJoinConfirmedDuffs("""[{"typeTag":$cj,"index":0,"confirmed":5}]"""))
    }

    @Test
    fun parse_nullEmptyOrMalformed_isUnknown() {
        assertNull(parseCoinJoinConfirmedDuffs(null))
        assertNull(parseCoinJoinConfirmedDuffs(""))
        assertNull(parseCoinJoinConfirmedDuffs("not json"))
        assertNull(parseCoinJoinConfirmedDuffs("{\"an\":\"object, not an array\"}"))
    }

    // ── mixedFundsDuffs ───────────────────────────────────────────────

    @Test
    fun preCutover_bothUnknown_isUnknown() {
        assertNull(mixedFundsDuffs(false, null, null))
    }

    @Test
    fun preCutover_takesTheMaxAndTreatsAnUnknownSourceAsZero() {
        assertEquals(150_000_000L, mixedFundsDuffs(false, 150_000_000L, 0L))
        assertEquals(150_000_000L, mixedFundsDuffs(false, 150_000_000L, null))
        // The SDK is passive (unknown) pre-cutover and dashj is the authority.
        assertEquals(150_000_000L, mixedFundsDuffs(false, null, 150_000_000L))
        assertEquals(200L, mixedFundsDuffs(false, 100L, 200L))
        assertEquals(200L, mixedFundsDuffs(false, 200L, 100L))
    }

    @Test
    fun preCutover_bothZero_isZeroNotUnknown() {
        assertEquals(0L, mixedFundsDuffs(false, 0L, 0L))
    }

    @Test
    fun postCutover_staleDashjBalanceIsIgnoredWhenSdkReadsZero() {
        // Field bug: an UPGRADED wallet's held dashj engine is frozen at its
        // pre-cutover snapshot (57 DASH mixed) while the SDK — the live
        // engine — sees the coins already shielded from another device.
        assertEquals(0L, mixedFundsDuffs(true, 0L, 5_734_663_282L))
    }

    @Test
    fun postCutover_usesTheSdkAmountAlone() {
        assertEquals(150_000_000L, mixedFundsDuffs(true, 150_000_000L, 0L))
        assertEquals(150_000_000L, mixedFundsDuffs(true, 150_000_000L, null))
        // Never the larger stale dashj figure.
        assertEquals(100L, mixedFundsDuffs(true, 100L, 200L))
    }

    @Test
    fun postCutover_unreadableSdkIsUnknownEvenWithDashjFunds() {
        assertNull(mixedFundsDuffs(true, null, 5_734_663_282L))
        assertNull(mixedFundsDuffs(true, null, null))
    }

    // ── the in-flight marker (format/parse/expiry) ────────────────────

    @Test
    fun inFlightMarker_roundTripsBothActions() {
        for (action in MixedFundsMigrationAction.entries) {
            val marker = InFlightMixedFundsMigration(action = action, startedAtMillis = 1_760_000_000_123L)
            assertEquals(marker, parseInFlightMigration(formatInFlightMigration(marker)))
        }
    }

    @Test
    fun inFlightMarker_persistedFormIsStable() {
        // The persisted form is a compatibility contract: older builds still read the third
        // (legacy baseline) field and complete only once a balance EXCEEDS it, which none can.
        val marker = InFlightMixedFundsMigration(MixedFundsMigrationAction.SHIELD, 1_760_000_000_000L)
        assertEquals("SHIELD|1760000000000|${Long.MAX_VALUE}", formatInFlightMigration(marker))
    }

    @Test
    fun inFlightMarker_definitiveFailureRoundTripsAndOlderFormStaysUnfailed() {
        val marker = InFlightMixedFundsMigration(MixedFundsMigrationAction.SHIELD, 1_760_000_000_000L, failed = true)
        assertEquals("SHIELD|1760000000000|${Long.MAX_VALUE}|failed", formatInFlightMigration(marker))
        assertEquals(marker, parseInFlightMigration(formatInFlightMigration(marker)))
        assertEquals(false, parseInFlightMigration("SHIELD|1760000000000|5")?.failed)
    }

    @Test
    fun inFlightMarker_everyLegacyBaselineParsesAndIsIgnored() {
        // 0 (older markExpired/COMBINE), a balance, -1 (UNKNOWN), other legacy numbers and MAX.
        val legacy = listOf("0", "5", "42000000", "-1", "-2", Long.MIN_VALUE.toString(), Long.MAX_VALUE.toString())
        for (baseline in legacy) {
            for (failed in listOf("", "|failed")) {
                assertEquals(
                    "$baseline$failed",
                    InFlightMixedFundsMigration(MixedFundsMigrationAction.SHIELD, 123L, failed = failed.isNotEmpty()),
                    parseInFlightMigration("SHIELD|123|$baseline$failed")
                )
            }
        }
    }

    @Test
    fun inFlightMarker_blankOrAbsentReadsAsNone() {
        // "" is the CLEARED value (DataStore has no remove here) and null is
        // never-written — both mean "nothing in flight".
        assertNull(parseInFlightMigration(null))
        assertNull(parseInFlightMigration(""))
        assertNull(parseInFlightMigration("   "))
    }

    @Test
    fun inFlightMarker_malformedReadsAsNone_neverWedgesProcessingUi() {
        assertNull(parseInFlightMigration("SHIELD|123"))
        assertNull(parseInFlightMigration("SHIELD|123|456|789"))
        assertNull(parseInFlightMigration("TELEPORT|123|456"))
        assertNull(parseInFlightMigration("SHIELD|not-a-number|456"))
        assertNull(parseInFlightMigration("SHIELD|123|not-a-number"))
    }

    @Test
    fun inFlightMarker_expiresExactlyPastTheHonestyWindow() {
        val startedAt = 1_000_000L
        val marker = InFlightMixedFundsMigration(MixedFundsMigrationAction.COMBINE, startedAt)
        assertEquals(false, marker.isExpired(nowMillis = startedAt))
        assertEquals(false, marker.isExpired(nowMillis = startedAt + MIXED_FUNDS_IN_FLIGHT_TIMEOUT_MS))
        assertEquals(true, marker.isExpired(nowMillis = startedAt + MIXED_FUNDS_IN_FLIGHT_TIMEOUT_MS + 1))
    }
}
