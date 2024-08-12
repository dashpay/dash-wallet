package de.schildbach.wallet.data

import org.bitcoinj.core.Coin

/*
 * Tests in July 2024: Contact Requests cost about 80,000,000 - 90,000,000
 *                     Profile Updates cost 10,000,000 - 100,000,000
 */
data class CreditBalanceInfo(
    val balance: Long
) {
    companion object {
        const val CREDITS_PER_DUFF = 1_000
        const val MAX_OPERATION_COST = 100_000_000.toLong()
        val MAX_OPERATION_COST_COIN = MAX_OPERATION_COST / CREDITS_PER_DUFF
        const val LOW_BALANCE = MAX_OPERATION_COST * 10
    }
    fun isBalanceEnough(): Boolean {
        return balance >= MAX_OPERATION_COST
    }

    fun isBalanceWarning(): Boolean {
        return balance <= LOW_BALANCE
    }
}
