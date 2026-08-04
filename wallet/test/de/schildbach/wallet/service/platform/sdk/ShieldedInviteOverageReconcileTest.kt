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
import androidx.core.net.toUri
import androidx.datastore.preferences.core.Preferences
import de.schildbach.wallet.data.InvitationLinkData
import de.schildbach.wallet.database.entity.BlockchainIdentityBaseData
import de.schildbach.wallet.database.entity.BlockchainIdentityConfig
import de.schildbach.wallet.database.entity.IdentityCreationState
import de.schildbach.wallet.ui.dashpay.utils.DashPayConfig
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.dashj.platform.dpp.identifier.Identifier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Robolectric tests (real `android.net.Uri`) for the completed-claim overage
 * RECONCILE end to end — the S22 retro-fit: the claim succeeded via the
 * RESUME path but no overage record was ever minted, so the launch-time
 * reconcile must re-derive the provable 0.05 from PERSISTED state (the
 * identity record + the persisted INVITE_LINK's `amt`) and mint the record
 * exactly once.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [29], manifest = Config.NONE)
class ShieldedInviteOverageReconcileTest {

    private companion object {
        const val OSK = "0011223344556677889900aabbccddeeff00112233445566778899aabbccddee"
        const val AMT = 30_000_000_000L
        const val OVERAGE = 5_000_000_000L
    }

    private val identityId = Identifier.from(ByteArray(32) { 7 }).toString()

    /** The S22-shaped link: shielded params + the funded note value. */
    private fun shieldedLinkWithAmt() = InvitationLinkData(
        "dashpay://invite?du=tst56&osk=$OSK&bh=0&amt=$AMT".toUri()
    )

    private fun configFake(backing: MutableMap<Preferences.Key<*>, Any>): DashPayConfig {
        val config = mockk<DashPayConfig>()
        coEvery { config.get(any<Preferences.Key<Any>>()) } answers {
            @Suppress("UNCHECKED_CAST")
            backing[firstArg<Preferences.Key<Any>>()]
        }
        coEvery { config.set(any<Preferences.Key<Any>>(), any()) } answers {
            backing[firstArg<Preferences.Key<Any>>()] = secondArg()
        }
        coEvery { config.remove(any<Preferences.Key<Any>>()) } answers {
            backing.remove(firstArg<Preferences.Key<Any>>())
        }
        return config
    }

    private fun completedClaimRecord(invite: InvitationLinkData?) = BlockchainIdentityBaseData(
        IdentityCreationState.DONE_AND_DISMISS, null, "fhjf-2", "fhjf-2", identityId, false,
        usingInvite = true, invite = invite
    )

    private fun service(
        backing: MutableMap<Preferences.Key<*>, Any>,
        base: BlockchainIdentityBaseData?
    ): ShieldedInviteOverageTopUp {
        val identityConfig = mockk<BlockchainIdentityConfig> {
            coEvery { loadBase() } returns (base ?: completedClaimRecord(null))
        }
        return ShieldedInviteOverageTopUp(
            configFake(backing),
            mockk<ShieldedBalanceService>(),
            mockk<InviteOverageSource>(),
            identityConfig
        )
    }

    /**
     * THE RESUMED-CLAIM PROVENANCE: on a resume, the claim's fundingCredits
     * come from the PERSISTED link string reparsed through Uri — `amt` must
     * survive that round trip, or neither the claim-path recording nor the
     * reconcile can ever prove the overage.
     */
    @Test
    fun persistedLinkRoundTrip_keepsAmt() {
        val original = shieldedLinkWithAmt()
        assertEquals(AMT, original.shieldedFundingCredits)
        // The exact persistence round trip: link.toString() → Uri.parse.
        val reparsed = InvitationLinkData(original.link.toString().toUri(), false)
        assertEquals(AMT, reparsed.shieldedFundingCredits)
        assertTrue(reparsed.isShielded)
    }

    @Test
    fun reconcile_completedClaim_mintsTheRecordExactlyOnce() = runTest {
        val backing = mutableMapOf<Preferences.Key<*>, Any>()
        val service = service(backing, completedClaimRecord(shieldedLinkWithAmt()))

        // First launch after the gap: the record is minted from persisted state.
        assertTrue(service.reconcileCompletedClaim())
        assertEquals(identityId, backing[DashPayConfig.INVITE_OVERAGE_IDENTITY_ID])
        assertEquals(OVERAGE, backing[DashPayConfig.INVITE_OVERAGE_CREDITS])
        assertEquals(identityId, backing[DashPayConfig.INVITE_OVERAGE_RECONCILED_IDENTITY])

        // Second launch while the record is pending: suppressed.
        assertFalse(service.reconcileCompletedClaim())

        // After the drain (record keys cleared, marker retained): still
        // suppressed — the reconcile can never re-mint a drained record.
        backing.remove(DashPayConfig.INVITE_OVERAGE_IDENTITY_ID)
        backing.remove(DashPayConfig.INVITE_OVERAGE_CREDITS)
        assertFalse(service.reconcileCompletedClaim())
    }

    @Test
    fun reconcile_linkWithoutAmt_neverMints() = runTest {
        val backing = mutableMapOf<Preferences.Key<*>, Any>()
        val legacyLink = InvitationLinkData("dashpay://invite?du=tst56&osk=$OSK&bh=0".toUri())
        val service = service(backing, completedClaimRecord(legacyLink))

        assertFalse(service.reconcileCompletedClaim())
        assertTrue(backing.isEmpty())
    }

    /**
     * THE S22 END-STATE: claim completed but the persisted link is GONE (the
     * completion path cleared the handler copy and the record carried no
     * usable link) — the reconcile must decline WITHOUT crashing and mint
     * nothing. (The production path also emits the one-line WARN diagnostic
     * for exactly this shape, so a silent no-op can never again cost an hour
     * of on-device forensics.)
     */
    @Test
    fun reconcile_completedClaimWithMissingLink_declinesAndMintsNothing() = runTest {
        val backing = mutableMapOf<Preferences.Key<*>, Any>()
        val service = service(backing, completedClaimRecord(invite = null))

        assertFalse(service.reconcileCompletedClaim())
        assertTrue(backing.isEmpty())
        // Repeated launches stay clean too (the WARN is one line per launch,
        // never a record or marker mutation).
        assertFalse(service.reconcileCompletedClaim())
        assertTrue(backing.isEmpty())
    }

    @Test
    fun reconcile_withoutIdentityConfig_isInert() = runTest {
        val backing = mutableMapOf<Preferences.Key<*>, Any>()
        val service = ShieldedInviteOverageTopUp(
            configFake(backing),
            mockk<ShieldedBalanceService>(),
            mockk<InviteOverageSource>()
        )
        assertFalse(service.reconcileCompletedClaim())
        assertTrue(backing.isEmpty())
    }
}
