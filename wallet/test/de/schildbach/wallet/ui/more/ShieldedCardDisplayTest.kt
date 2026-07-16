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

package de.schildbach.wallet.ui.more

import de.schildbach.wallet.database.entity.BlockchainIdentityBaseData
import de.schildbach.wallet.database.entity.IdentityCreationState
import de.schildbach.wallet.service.platform.sdk.ShieldedSyncStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Display decision for the More-screen "Shielded" balance card
 * ([mapShieldedCardDisplay] / [hasShieldedContext]): a factory-fresh wallet
 * (no identity, shielded never configured — the runtime can never come up)
 * shows its honest zero instead of a permanent "Syncing…", while a wallet
 * WITH a shielded context keeps the placeholder until the balance is
 * trustworthy (READY). Display-only — the gating trust rule ("non-READY
 * means don't trust the zero") elsewhere is not exercised here.
 */
class ShieldedCardDisplayTest {

    private fun identity(
        creationState: IdentityCreationState = IdentityCreationState.NONE,
        userId: String? = null,
        restoring: Boolean = false
    ) = BlockchainIdentityBaseData(
        creationState = creationState,
        creationStateErrorMessage = null,
        username = null,
        usernameSecondary = null,
        userId = userId,
        restoring = restoring
    )

    // ── mapShieldedCardDisplay ────────────────────────────────────────

    @Test
    fun `fresh wallet - NOT_READY with no shielded context shows the zero amount`() {
        assertEquals(
            ShieldedCardDisplay.AMOUNT,
            mapShieldedCardDisplay(ShieldedSyncStatus.NOT_READY, hasShieldedContext = false)
        )
    }

    @Test
    fun `migrated wallet mid-resync - NOT_READY with a shielded context keeps the syncing placeholder`() {
        assertEquals(
            ShieldedCardDisplay.SYNCING,
            mapShieldedCardDisplay(ShieldedSyncStatus.NOT_READY, hasShieldedContext = true)
        )
    }

    @Test
    fun `pass in flight keeps the syncing placeholder regardless of identity`() {
        // A genuinely scanning runtime always wins over the identity signal
        // (defensive: SYNCING implies a bound wallet anyway).
        assertEquals(
            ShieldedCardDisplay.SYNCING,
            mapShieldedCardDisplay(ShieldedSyncStatus.SYNCING, hasShieldedContext = true)
        )
        assertEquals(
            ShieldedCardDisplay.SYNCING,
            mapShieldedCardDisplay(ShieldedSyncStatus.SYNCING, hasShieldedContext = false)
        )
    }

    @Test
    fun `ready shows the real balance regardless of identity`() {
        assertEquals(
            ShieldedCardDisplay.AMOUNT,
            mapShieldedCardDisplay(ShieldedSyncStatus.READY, hasShieldedContext = true)
        )
        assertEquals(
            ShieldedCardDisplay.AMOUNT,
            mapShieldedCardDisplay(ShieldedSyncStatus.READY, hasShieldedContext = false)
        )
    }

    // ── hasShieldedContext (mirrors SdkWalletBinder's bind eligibility) ──

    @Test
    fun `fresh wallet has no shielded context`() {
        assertFalse(hasShieldedContext(identity()))
    }

    @Test
    fun `identity creation in progress is a shielded context`() {
        assertTrue(hasShieldedContext(identity(creationState = IdentityCreationState.UPGRADING_WALLET)))
    }

    @Test
    fun `registered identity is a shielded context`() {
        assertTrue(hasShieldedContext(identity(userId = "someIdentityId")))
    }

    @Test
    fun `restoring flag alone is not a context - same as the binder gate`() {
        // SdkWalletBinder binds on creationState/userId only; a bare
        // restoring flag with neither set cannot bring the runtime up.
        assertFalse(hasShieldedContext(identity(restoring = true)))
    }
}
