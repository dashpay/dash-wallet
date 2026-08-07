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
import org.dash.wallet.common.money.Dash
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Display decision for the More-screen "Shielded" balance card
 * ([mapShieldedCardDisplay] / [hasShieldedContext]).
 *
 * The card shows "Syncing…" ONLY when there is genuinely nothing better to
 * show; otherwise it prefers a real amount — the live balance when READY,
 * else a persisted last-known (cached) balance. A factory-fresh wallet (no
 * identity, shielded never configured — the runtime can never come up) shows
 * its honest zero; a wallet WITH a shielded context and NO cache keeps the
 * placeholder until READY; a background re-scan on relaunch shows the cached
 * balance; and a just-completed local spend forces "Syncing…" over the now
 * stale cache. Display-only — the gating trust rule elsewhere is not
 * exercised here.
 */
class ShieldedCardDisplayTest {

    private val cached = Dash(150_000_000L) // 1.5 DASH persisted last-known

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
    fun `fresh wallet - NOT_READY with no shielded context and no cache shows the live zero`() {
        assertEquals(
            ShieldedCardDisplay.LIVE_AMOUNT,
            mapShieldedCardDisplay(
                ShieldedSyncStatus.NOT_READY, hasShieldedContext = false,
                cachedBalance = null, balanceMaybeStale = false
            )
        )
    }

    @Test
    fun `migrated wallet mid-resync - NOT_READY with a context and no cache keeps the placeholder`() {
        // Case (a): first balance fetch, startup pass not complete, nothing
        // cached yet — never flash a bare zero for a funded/migrated wallet.
        assertEquals(
            ShieldedCardDisplay.SYNCING,
            mapShieldedCardDisplay(
                ShieldedSyncStatus.NOT_READY, hasShieldedContext = true,
                cachedBalance = null, balanceMaybeStale = false
            )
        )
    }

    @Test
    fun `background re-scan on relaunch with a cached balance shows the cache, not the placeholder`() {
        // The core fix: a durable cached balance is shown while the runtime
        // re-binds/re-scans in the background — no "Syncing…" flash.
        for (status in listOf(ShieldedSyncStatus.NOT_READY, ShieldedSyncStatus.SYNCING)) {
            assertEquals(
                ShieldedCardDisplay.CACHED_AMOUNT,
                mapShieldedCardDisplay(
                    status, hasShieldedContext = true,
                    cachedBalance = cached, balanceMaybeStale = false
                )
            )
        }
    }

    @Test
    fun `just-completed local spend forces the placeholder over a stale cache`() {
        // Case (b): a shielded spend from this app just happened; the cached
        // balance is known-stale until the runtime re-settles → "Syncing…".
        assertEquals(
            ShieldedCardDisplay.SYNCING,
            mapShieldedCardDisplay(
                ShieldedSyncStatus.SYNCING, hasShieldedContext = true,
                cachedBalance = cached, balanceMaybeStale = true
            )
        )
        // Even with no cache the stale signal keeps the placeholder (e.g. a
        // first shield on an otherwise-fresh wallet).
        assertEquals(
            ShieldedCardDisplay.SYNCING,
            mapShieldedCardDisplay(
                ShieldedSyncStatus.NOT_READY, hasShieldedContext = false,
                cachedBalance = null, balanceMaybeStale = true
            )
        )
    }

    @Test
    fun `ready always shows the live balance - even with a cache or a stale one-shot`() {
        // A fully-synced runtime is authoritative: live wins over any cache
        // and any lingering stale signal (which is cleared on READY anyway).
        for (context in listOf(true, false)) {
            assertEquals(
                ShieldedCardDisplay.LIVE_AMOUNT,
                mapShieldedCardDisplay(
                    ShieldedSyncStatus.READY, hasShieldedContext = context,
                    cachedBalance = cached, balanceMaybeStale = true
                )
            )
        }
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
