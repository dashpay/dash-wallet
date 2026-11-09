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

// Pure, host-testable model of the Advanced-mode "Internal Transfers" (MO-1125): moving
// funds between the user's OWN balances. No native, no Android, no I/O.

/** Whether a From → To pair of own balances can be transferred, and if not, why. */
enum class InternalTransferSupport {
    SUPPORTED,

    /** From and To are the same balance: never allowed. */
    SAME_BALANCE,

    /** The SDK (or the wallet) cannot do this pair yet: shown disabled, "not available yet". */
    NOT_SUPPORTED_YET,

    /** One side is the Shielded balance and the shielded feature is off (or unreadable). */
    SHIELDED_OFF,

    /** One side is the Identity balance and the user has no identity (or it is unknown). */
    NO_IDENTITY
}

/**
 * The one pair matrix of the internal transfer, enforced by the UI AND by
 * [InternalTransferService.transfer]:
 *
 * | From \ To | Core | Shielded | Platform | Identity |
 * |---|---|---|---|---|
 * | Core | — | yes | no (1) | yes |
 * | Shielded | yes | — | yes | yes |
 * | Platform | yes (2) | yes | — | yes |
 * | Identity | yes | no (3) | yes | — |
 *
 * 1. The Platform-address funding from an asset lock is not wired for this flow yet.
 * 2. The External Send's partial Platform → Core withdrawal (`withdrawCredits` with the
 *    inputs the wallet selects) to the wallet's own unadvertised Core address.
 * 3. `shieldedShieldFromIdentity` (Type 21) is not bound in the wallet yet.
 *
 * [shieldedEnabled] and [hasIdentity] are the error-preserving reads: unknown is false.
 */
fun internalTransferSupport(
    from: SendBalance,
    to: SendBalance,
    shieldedEnabled: Boolean,
    hasIdentity: Boolean
): InternalTransferSupport {
    if (from == to) return InternalTransferSupport.SAME_BALANCE
    if (!internalTransferPairSupported(from, to)) return InternalTransferSupport.NOT_SUPPORTED_YET
    if ((from == SendBalance.SHIELDED || to == SendBalance.SHIELDED) && !shieldedEnabled) {
        return InternalTransferSupport.SHIELDED_OFF
    }
    if ((from == SendBalance.IDENTITY || to == SendBalance.IDENTITY) && !hasIdentity) {
        return InternalTransferSupport.NO_IDENTITY
    }
    return InternalTransferSupport.SUPPORTED
}

/** The bare matrix of [internalTransferSupport]: whether the wallet can move [from] → [to] at all. */
fun internalTransferPairSupported(from: SendBalance, to: SendBalance): Boolean = when (from) {
    SendBalance.CORE -> to == SendBalance.SHIELDED || to == SendBalance.IDENTITY
    SendBalance.SHIELDED -> to != SendBalance.SHIELDED
    SendBalance.PLATFORM -> to != SendBalance.PLATFORM
    SendBalance.IDENTITY -> to == SendBalance.CORE || to == SendBalance.PLATFORM
}

/**
 * The balances the From / To pickers list, in order. Identity only for a user known to have
 * an identity ([hasIdentity] true); unknown hides it. Shielded is always listed: when the
 * feature is off it shows disabled.
 */
fun internalTransferBalances(hasIdentity: Boolean?): List<SendBalance> =
    SendBalance.entries.filter { it != SendBalance.IDENTITY || hasIdentity == true }

/** An ordered pair of own balances; [from] != [to] always. [toString] carries no amounts or ids. */
data class InternalTransferPair(val from: SendBalance, val to: SendBalance) {
    init {
        require(from != to) { "an internal transfer needs two different balances" }
    }

    /** The swap button (AC3): From and To exchange. */
    fun swapped(): InternalTransferPair = InternalTransferPair(to, from)

    /** Pick [balance] as From; picking the current To swaps the two (AC4). */
    fun withFrom(balance: SendBalance): InternalTransferPair = when (balance) {
        from -> this
        to -> swapped()
        else -> copy(from = balance)
    }

    /** Pick [balance] as To; picking the current From swaps the two (AC4). */
    fun withTo(balance: SendBalance): InternalTransferPair = when (balance) {
        to -> this
        from -> swapped()
        else -> copy(to = balance)
    }
}

/**
 * The pair the Internal tab opens with: From Core; To Shielded when the shielded feature is
 * on, otherwise Identity when the user has one, otherwise Platform.
 */
fun defaultInternalTransferPair(shieldedEnabled: Boolean, hasIdentity: Boolean): InternalTransferPair =
    InternalTransferPair(
        SendBalance.CORE,
        when {
            shieldedEnabled -> SendBalance.SHIELDED
            hasIdentity -> SendBalance.IDENTITY
            else -> SendBalance.PLATFORM
        }
    )

/** Whether a transfer [from] → [to] involves a ~30 s shielded proof. */
fun internalTransferHasShieldedLeg(from: SendBalance, to: SendBalance): Boolean =
    from == SendBalance.SHIELDED || to == SendBalance.SHIELDED
