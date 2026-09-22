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

package org.dash.wallet.integrations.maya.utils

import org.bitcoinj.core.Coin

/**
 * The arithmetic behind the sell flow's Max button.
 *
 * A max sell is carried out as a sweep ("empty wallet"), and the miner fee comes out of the
 * sweep's single output — so what the swap actually delivers is `balance − fee`, never the whole
 * balance. Entering the gross balance is what made Max "show unreal value, that exceed the real
 * balance" (MO-997): the quote and the deposit were both computed from the sweep output while the
 * enter-amount screen displayed a figure the swap could never use.
 *
 * Kept free of Android and wallet types so the rules can be exercised on the host JVM.
 */
object MaxSendable {
    /**
     * What a sweep of [balance] delivers once [fee] is taken out of its single output, floored at
     * zero: a wallet whose fee is at or above its balance can send nothing, and a negative amount
     * would otherwise flow into the entered value. Mirrors the coercion
     * `de.schildbach.wallet.payments.MaxOutputAmountCoinSelector` applies on the send screen.
     */
    fun sendableFromBalance(balance: Coin, fee: Coin): Coin =
        balance.subtract(fee).coerceAtLeast(Coin.ZERO)

    /**
     * Floors an estimator's already-netted sweep output at zero.
     * `SendPaymentService.estimateNetworkFee(…, emptyWallet = true).amountToSend` is
     * `balance − fee` and can come back non-positive for a dust-level wallet.
     */
    fun coerceSendable(sweepOutput: Coin): Coin = sweepOutput.coerceAtLeast(Coin.ZERO)

    /**
     * Whether an amount the user typed by hand should still be treated as a max (sweep) rather
     * than a partial swap. This is the fallback for someone who types the full sendable figure
     * instead of pressing Max — the button's own intent is tracked explicitly, because a
     * fiat-anchored Max doesn't survive the round trip back to DASH as an exact match.
     *
     * Compares with `>=` rather than `==`: anything strictly above [maxSendable] is rejected by
     * the entry bound before it can be swapped, so the only entries that reach a swap through
     * this path are exactly the sendable amount. A wallet that can send nothing is never a max.
     */
    fun isTypedMax(enteredDash: Coin, maxSendable: Coin): Boolean =
        maxSendable.isPositive && enteredDash >= maxSendable

    /** The combined rule: the Max button was used, or the typed amount amounts to the same thing. */
    fun isMaxSwap(maxButtonUsed: Boolean, enteredDash: Coin, maxSendable: Coin): Boolean =
        maxButtonUsed || isTypedMax(enteredDash, maxSendable)
}
