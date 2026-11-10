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

/**
 * Advanced-mode "Internal Transfers" (MO-1125): move funds between the user's OWN balances
 * ([SendBalance]) under the pair matrix [internalTransferSupport]. There is no address entry:
 * the destination is always the wallet's own address for the To balance, resolved by this
 * service at send time and failing closed. Basic mode does not use this.
 *
 * Results reuse [ExternalSendResult] / [NotSentReason]. Nothing amount-, address- or
 * identity-derived is logged.
 */
interface InternalTransferService {
    /** [ExternalSendService.observeBalance]: live spendable balance of [balance]; null = unknown. */
    fun observeBalance(balance: SendBalance): Flow<Dash?>

    /** [ExternalSendService.observeHasIdentity]; null = could not tell (the UI hides Identity). */
    fun observeHasIdentity(): Flow<Boolean?>

    /**
     * `USE_KOTLIN_SDK_SHIELDED`, error-preserving: true/false when read (absent = false), null
     * when unreadable (unknown: treated as off for gating, never as a confirmed "off"); keeps
     * observing and never completes on a transient error.
     */
    fun observeShieldedEnabled(): Flow<Boolean?>

    /** [ExternalSendService.currentWalletIdOrNull]: captured, without suspending, at confirmation. */
    fun currentWalletIdOrNull(): String?

    /** The fee of a [from] → [to] transfer, null when it cannot be known before the send (never fabricated). */
    suspend fun estimateFee(from: SendBalance, to: SendBalance): FeeEstimate?

    /** The Max of a [from] → [to] transfer (the balance minus the fee paid on top), null when unknown. */
    suspend fun maxSendable(from: SendBalance, to: SendBalance): Dash?

    /**
     * Move [amount] from [from] to the wallet's own [to] balance. The caller has ALREADY
     * authenticated and passes exactly what the user confirmed, including
     * [expectedWalletIdHex] ([currentWalletIdOrNull] at confirmation). The implementation
     * enforces [internalTransferSupport] itself, refuses unless that wallet is the bound one
     * (re-checked right before the write), and resolves the own destination after that check;
     * an unavailable destination is [NotSentReason.GENERIC] with nothing submitted. A Core
     * destination is NEVER the advertised receive address.
     *
     * Shielded legs block for a ~30s proof: run this on an application-scoped owner.
     */
    suspend fun transfer(
        from: SendBalance,
        to: SendBalance,
        amount: Dash,
        isMax: Boolean,
        expectedWalletIdHex: String?
    ): ExternalSendResult
}
