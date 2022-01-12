package org.dash.wallet.common.services

import org.bitcoinj.core.Address
import org.bitcoinj.core.Coin
import org.bitcoinj.core.Transaction

interface SendPaymentService {
    suspend fun sendCoins(address: Address, amount: Coin, constrainInputsTo: Address? = null): Transaction
}