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

import kotlinx.coroutines.flow.Flow
import org.dash.wallet.common.money.Dash

/** A fee for one exact send. [exact] false = an estimate or an upper bound, shown as "~". */
data class FeeEstimate(val fee: Dash, val exact: Boolean)

/** [ExternalSendService.resolveIdentity] for the confirm step. Never carries the id. */
sealed class IdentityResolution {
    /** The identity exists on Platform; [username] is its DPNS name when one could be read. */
    data class Found(val username: String?) : IdentityResolution() {
        override fun toString(): String = "Found(<redacted>)"
    }

    /** Platform answered that no such identity exists. */
    object NotFound : IdentityResolution()

    /** The lookup failed (network, SDK not ready): unknown, never "not found". */
    object Unknown : IdentityResolution()
}

/** Why a send provably did not spend anything. The UI maps each to its own string; no SDK text. */
enum class NotSentReason {
    INSUFFICIENT_FUNDS,

    /** The engine/pool is not caught up yet (L1 funding gate closed, shielded runtime not ready). */
    NOT_SYNCED,

    /** The signer could not sign (device locked / Keystore refused); the same send works after unlock. */
    SIGNER_LOCKED,

    /** The pair is not supported ([externalSendSupport]) or the feature is off. */
    NOT_SUPPORTED,

    /** An earlier payment's outcome is still unknown and its inputs cannot be kept out of this one. */
    PAYMENT_STILL_CONFIRMING,

    /** Another external send is already running. */
    IN_PROGRESS,

    /** Anything else refused before submission (including the wallet changing under the send). */
    GENERIC
}

/** The outcome of [ExternalSendService.send]. */
sealed class ExternalSendResult {
    /** Submitted. [txIdOrNull] is the Core txid when the route reports one. */
    data class Sent(val txIdOrNull: String?) : ExternalSendResult()

    /** Provably nothing was spent; the user may retry. */
    data class NotSent(val reason: NotSentReason) : ExternalSendResult()

    /** The outcome is unknown: terminal "may have gone through", NEVER a retry. */
    object MayHaveGoneThrough : ExternalSendResult()

    /**
     * Core → Shielded only: the L1 asset lock went out but the shield transition to the
     * recipient provably did not. The wallet's pending-shield sweep completes it to the
     * user's OWN shielded balance instead; the recipient is not paid. Terminal; no retry.
     */
    object InterruptedFundsReturnToShielded : ExternalSendResult()
}

/**
 * Advanced-mode "External Send" (MO-1121): send from one of the four balances
 * ([SendBalance]) to an external destination ([SendDestination]) under the support
 * matrix [externalSendSupport]. Basic mode does not use this.
 *
 * Every send needs the cutover committed (the SDK owns L1); shielded legs also need
 * `USE_KOTLIN_SDK_SHIELDED`. Both are read error-preserving: unreadable = refused.
 * Nothing amount-, address- or identity-derived is logged.
 */
interface ExternalSendService {
    /**
     * Live spendable balance of [source]; null = unknown (never [Dash.ZERO] for
     * unknown). Shielded is only known while the pool is READY and not stale after a
     * spend; Identity is null when the user has no identity. Never throws.
     */
    fun observeBalance(source: SendBalance): Flow<Dash?>

    /**
     * Whether the user has a registered identity (the IDENTITY source and the
     * `hasIdentity` input of [externalSendSupport]); null = could not tell.
     */
    fun observeHasIdentity(): Flow<Boolean?>

    /** Whether this wallet manages the identity; null = could not tell (fail closed). */
    suspend fun isOwnIdentity(identityIdBase58: String): Boolean?

    /** Identity lookup for the confirm step (AC8). Never logs the id. */
    suspend fun resolveIdentity(identityIdBase58: String): IdentityResolution

    /** Fee for this exact send, or null when it cannot be known before the send (never fabricated). */
    suspend fun estimateFee(source: SendBalance, destination: SendDestination, amount: Dash): FeeEstimate?

    /** Balance minus fee for Max, or null when unknown. */
    suspend fun maxSendable(source: SendBalance, destination: SendDestination): Dash?

    /**
     * Send [amount] from [source] to [destination]. The caller has ALREADY
     * authenticated (PIN/biometric) for this send and passes exactly what the user
     * confirmed. The implementation captures the wallet before its first suspension
     * and re-checks it right before the native call (a change → NotSent, nothing
     * submitted), enforces [externalSendSupport] itself, and runs one attempt (plus, for
     * a shielded-funded or Core→Shielded [isMax] send, one fee-adjusted retry after a
     * provably pre-broadcast shortfall).
     *
     * Shielded legs block for a ~30s proof: run this on an application-scoped owner so
     * leaving the screen does not cancel it.
     */
    suspend fun send(
        source: SendBalance,
        destination: SendDestination,
        amount: Dash,
        isMax: Boolean
    ): ExternalSendResult
}
