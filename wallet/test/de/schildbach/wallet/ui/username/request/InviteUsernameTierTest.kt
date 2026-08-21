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

package de.schildbach.wallet.ui.username.request

import android.app.Application
import androidx.core.net.toUri
import de.schildbach.wallet.Constants
import de.schildbach.wallet.data.InvitationLinkData
import org.bitcoinj.core.Coin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Regression cover for the contested-invitation claim bug reported off the
 * S22 (build 11.10.46).
 *
 * The product owner created a CONTESTED shielded invitation (0.3 DASH debited
 * from the shielded pool) and claimed it on a second phone. The claim screen
 * showed three things at once that cannot all be true:
 *
 *  1. "You can only create a non-contested username using this invitation";
 *  2. the entered name `test-me` failing BOTH non-contested qualifiers, i.e.
 *     the screen's own rows declaring it contested;
 *  3. the "Request Username" button ENABLED.
 *
 * Cause: the notice was drawn from `getInvitationAmount()`, which only ever
 * resolves an L1 asset lock. A shielded invite has none, so it returned
 * Coin.ZERO and EVERY shielded invite compared as non-contested — the amount
 * was never merely misread, it was never readable. Meanwhile the submit gate
 * short-circuited shielded invites to an unconditional `true`, so the two
 * never had to agree.
 *
 * These tests pin the two invariants that broke: the tier is derived from one
 * place, and an unreadable tier is UNKNOWN rather than silently non-contested.
 *
 * Robolectric supplies the real `android.net.Uri` the link accessors parse.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [29], manifest = Config.NONE)
class InviteUsernameTierTest {

    private companion object {
        const val OSK = "0011223344556677889900aabbccddeeff00112233445566778899aabbccddee"

        /** The two shielded exit denominations, in Platform credits. */
        const val NON_CONTESTED_CREDITS = 10_000_000_000L // 0.1 DASH
        const val CONTESTED_CREDITS = 30_000_000_000L // 0.3 DASH

        fun shielded(credits: Long?) = InvitationLinkData.createShielded(
            username = "claudetest53",
            displayName = "",
            avatarUrl = "",
            oneTimeKeyHex = OSK,
            fundingHeight = 0,
            fundingCredits = credits
        )

        /** The exact link the product owner claimed on the S22 — no `amt`. */
        fun reportedLink() = InvitationLinkData(
            "dashpay://invite?du=claudetest53&osk=$OSK&bh=0".toUri()
        )

        fun l1() = InvitationLinkData(
            ("dashpay://invite?du=alice&assetlocktx=abc123&pk=cP5W&islock=deadbeef").toUri()
        )
    }

    // ── Shielded invites ──────────────────────────────────────────────

    @Test
    fun `a shielded invite funded at the contested denomination reads as CONTESTED`() {
        assertEquals(
            InviteUsernameTier.CONTESTED,
            inviteUsernameTier(shielded(CONTESTED_CREDITS), Coin.ZERO)
        )
    }

    @Test
    fun `a shielded invite funded at the non-contested denomination reads as NON_CONTESTED`() {
        assertEquals(
            InviteUsernameTier.NON_CONTESTED,
            inviteUsernameTier(shielded(NON_CONTESTED_CREDITS), Coin.ZERO)
        )
    }

    @Test
    fun `the reported S22 link reads as UNKNOWN, never as non-contested`() {
        // THE regression: this link carries no amount, so the app cannot know
        // the tier. Reporting NON_CONTESTED here is what produced the false
        // "you can only create a non-contested username" notice on an invite
        // that had in fact paid the 0.3 DASH contested fee.
        assertEquals(InviteUsernameTier.UNKNOWN, inviteUsernameTier(reportedLink(), Coin.ZERO))
    }

    @Test
    fun `no invitation at all is UNKNOWN`() {
        assertEquals(InviteUsernameTier.UNKNOWN, inviteUsernameTier(null, Coin.ZERO))
    }

    // ── L1 invites keep their existing behaviour ──────────────────────

    @Test
    fun `an L1 invite is classified from its asset-lock amount`() {
        assertEquals(
            InviteUsernameTier.CONTESTED,
            inviteUsernameTier(l1(), Constants.DASH_PAY_FEE_CONTESTED)
        )
        assertEquals(
            InviteUsernameTier.NON_CONTESTED,
            inviteUsernameTier(l1(), Constants.DASH_PAY_FEE)
        )
    }

    @Test
    fun `an L1 invite whose asset lock has not been read yet stays non-contested as before`() {
        // Unchanged legacy behaviour: the lookup is async and the observers
        // re-render when it lands. Only the SHIELDED path changes here.
        assertEquals(InviteUsernameTier.NON_CONTESTED, inviteUsernameTier(l1(), Coin.ZERO))
    }

    // ── The notice and the submit gate must agree ─────────────────────

    @Test
    fun `a contested invite permits a contested username`() {
        // The product owner's case: he paid 0.3, so `test-me` must be allowed
        // AND the "non-contested only" notice must not be shown.
        val tier = inviteUsernameTier(shielded(CONTESTED_CREDITS), Coin.ZERO)
        assertTrue(inviteTierAllowsUsername(tier, contestable = true))
        assertEquals(InviteUsernameTier.CONTESTED, tier)
    }

    @Test
    fun `a known non-contested invite blocks a contested username`() {
        // This is the pairing that was broken the other way: the notice said
        // "non-contested only" while the gate returned true regardless.
        val tier = inviteUsernameTier(shielded(NON_CONTESTED_CREDITS), Coin.ZERO)
        assertFalse(inviteTierAllowsUsername(tier, contestable = true))
        assertTrue(inviteTierAllowsUsername(tier, contestable = false))
    }

    @Test
    fun `an unknown tier does not block a contested username`() {
        // Refusing on a guess would strand exactly the user who reported this:
        // he paid the contested fee and would be locked out of using it. The
        // claim itself fails closed if the note turns out to be too small.
        val tier = inviteUsernameTier(reportedLink(), Coin.ZERO)
        assertTrue(inviteTierAllowsUsername(tier, contestable = true))
        assertTrue(inviteTierAllowsUsername(tier, contestable = false))
    }

    @Test
    fun `every tier permits a non-contested username`() {
        InviteUsernameTier.entries.forEach { tier ->
            assertTrue(
                "tier $tier wrongly blocked a non-contested username",
                inviteTierAllowsUsername(tier, contestable = false)
            )
        }
    }

    @Test
    fun `the notice is shown exactly when the gate would block a contested name`() {
        // The screen showed the notice whenever the tier was not CONTESTED,
        // while the gate ignored the tier entirely. Tie them together: the
        // definite "non-contested only" claim is legitimate only where a
        // contested name is actually refused.
        InviteUsernameTier.entries.forEach { tier ->
            val assertsNonContestedOnly = tier == InviteUsernameTier.NON_CONTESTED
            assertEquals(
                "tier $tier: notice and submit gate disagree",
                assertsNonContestedOnly,
                !inviteTierAllowsUsername(tier, contestable = true)
            )
        }
    }
}
