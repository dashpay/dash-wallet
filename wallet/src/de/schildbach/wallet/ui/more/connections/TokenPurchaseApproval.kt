/* Copyright 2026 Dash Core Group. SPDX-License-Identifier: GPL-3.0-or-later */
package de.schildbach.wallet.ui.more.connections

import de.schildbach.wallet.ui.more.connections.protocol.DashStRequest
import java.math.BigInteger

data class TokenPurchaseApproval(
    val request: DashStRequest,
    val scope: String,
    val requestId: String,
    val ownerId: String,
    val contractId: String,
    val tokenId: String,
    val amount: BigInteger,
    val maximumCredits: BigInteger,
    val tokenName: String,
    val decimals: Int,
    internal val summary: ByteArray
) {
    init {
        require(decimals in 0..255)
        require(amount.signum() > 0 && amount.bitLength() <= 64)
        require(maximumCredits.signum() > 0 && maximumCredits.bitLength() <= 64)
    }
    val quantity: String get() = amount.toBigDecimal().movePointLeft(decimals).stripTrailingZeros().toPlainString()
    val maximumDash: String get() = formatDash(maximumCredits)
}

enum class TokenPurchaseStatus { READY, SUBMITTING, SUBMITTED, OUTCOME_UNKNOWN, FAILED }

class TokenPurchaseOutcomeUnknown(cause: Throwable) : Exception(
    "Purchase may have been submitted. Check your token and credit balances before making another purchase. This request cannot be retried.", cause)
