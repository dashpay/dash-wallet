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
package de.schildbach.wallet.service.platform

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import org.junit.runners.Parameterized.Parameters

/**
 * Host-JVM tests for [requireWithinApprovedAmount] — the shared confirmed-amount
 * funding guard every entry point calls before building or signing anything
 * ([TopUpRepositoryImpl.createAssetLockTransaction] for the first/fresh asset
 * lock, [TopUpRepositoryImpl.createTopupTransaction] for a resumed identity
 * top-up). A fresh resolution may only CONFIRM or LOWER the amount the user
 * approved — never raise it silently (MO-1069 review 5447932359: the legacy
 * resume top-up branch funded `createTopupTransaction` with no comparison to
 * the confirmed cap at all).
 */
@RunWith(Parameterized::class)
class TopUpRepositoryGuardTest(
    private val scenario: String,
    private val approvedAmountDuffs: Long,
    private val resolvedAmountDuffs: Long,
    private val expectRefused: Boolean
) {
    companion object {
        // The protocol-14 CURRENT contested fee (0.15 DASH) vs. the protocol-13
        // LEGACY contested fee (0.25 DASH) a null/failed funding-time protocol
        // read falls back to — see ContestedUsernameFees.
        private const val CURRENT_CONTESTED_DUFFS = 15_000_000L
        private const val LEGACY_CONTESTED_DUFFS = 25_000_000L

        // A CURRENT name-only top-up approval (0.10 DASH) vs. the LEGACY
        // name-only top-up amount (0.20 DASH) — the exact resumed-top-up
        // numbers from the review's reported exploit.
        private const val CURRENT_TOPUP_DUFFS = 10_000_000L
        private const val LEGACY_TOPUP_DUFFS = 20_000_000L

        @JvmStatic
        @Parameters(name = "{0}")
        fun scenarios(): List<Array<Any>> = listOf(
            arrayOf(
                "fresh asset-lock funding: protocol-14 quote, funding-time read falls back to LEGACY -> refused",
                CURRENT_CONTESTED_DUFFS, LEGACY_CONTESTED_DUFFS, true
            ),
            arrayOf(
                "fresh asset-lock funding: funding-time read confirms the same protocol-14 amount -> funds",
                CURRENT_CONTESTED_DUFFS, CURRENT_CONTESTED_DUFFS, false
            ),
            arrayOf(
                "fresh asset-lock funding: funding-time read resolves LOWER than approved -> funds",
                LEGACY_CONTESTED_DUFFS, CURRENT_CONTESTED_DUFFS, false
            ),
            arrayOf(
                "resumed top-up: CURRENT name-only approval, live read falls back to LEGACY top-up -> refused",
                CURRENT_TOPUP_DUFFS, LEGACY_TOPUP_DUFFS, true
            ),
            arrayOf(
                "resumed top-up: live read confirms the same CURRENT top-up amount -> funds",
                CURRENT_TOPUP_DUFFS, CURRENT_TOPUP_DUFFS, false
            ),
            arrayOf(
                "resumed top-up: live read resolves LOWER than approved -> funds",
                LEGACY_TOPUP_DUFFS, CURRENT_TOPUP_DUFFS, false
            )
        )
    }

    @Test
    fun fundedNeverExceedsConfirmed() {
        if (expectRefused) {
            val e = assertThrows(FundingAmountExceededException::class.java) {
                requireWithinApprovedAmount(resolvedAmountDuffs, approvedAmountDuffs)
            }
            assertEquals(
                "resolved funding amount ($resolvedAmountDuffs duffs) exceeds the confirmed amount ($approvedAmountDuffs duffs)",
                e.message
            )
        } else {
            // Must not throw — the caller proceeds to build/sign.
            requireWithinApprovedAmount(resolvedAmountDuffs, approvedAmountDuffs)
        }
    }
}
