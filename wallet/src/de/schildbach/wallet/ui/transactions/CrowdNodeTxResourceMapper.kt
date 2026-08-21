/*
 * Copyright 2022 Dash Core Group.
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

package de.schildbach.wallet.ui.transactions

import android.text.format.DateUtils
import androidx.annotation.StringRes
import de.schildbach.wallet_test.R
import org.bitcoinj.core.Transaction
import org.bitcoinj.core.TransactionBag
import de.schildbach.wallet.transactions.TransactionUtils.isEntirelySelf
import de.schildbach.wallet.transactions.toTxInfo
import org.dash.wallet.integrations.crowdnode.transactions.*
import de.schildbach.wallet.util.format
import de.schildbach.wallet.util.setAmount
import de.schildbach.wallet.util.setFiatAmount
import de.schildbach.wallet.util.toDashjFiat
import de.schildbach.wallet.util.toDashjCoin
import de.schildbach.wallet.util.toNeutralCoin
import de.schildbach.wallet.util.toNeutralFiat
import de.schildbach.wallet.util.toTxId
import de.schildbach.wallet.util.toSha256Hash

class CrowdNodeTxResourceMapper: TxResourceMapper() {
    override val dateTimeFormat: Int
        get() = DateUtils.FORMAT_SHOW_TIME

    @StringRes
    override fun getTransactionTypeName(tx: Transaction, bag: TransactionBag): Int {
        if ((tx.type != Transaction.Type.TRANSACTION_NORMAL &&
            tx.type != Transaction.Type.TRANSACTION_UNKNOWN) ||
            tx.confidence.hasErrors() ||
            tx.isCoinBase
        ) {
            return super.getTransactionTypeName(tx, bag)
        }

        val txInfo = tx.toTxInfo(bag, tx.params)
        val networkId = tx.params.id

        return if (tx.isEntirelySelf(bag)) {
            R.string.shuffle_coins
        } else if (CrowdNodeSignUpTx(networkId).matches(txInfo)) {
            R.string.account_create
        } else if (CrowdNodeAcceptTermsTx(networkId).matches(txInfo)) {
            R.string.accept_terms
        } else if (CrowdNodeAcceptTermsResponse(networkId).matches(txInfo) || PossibleAcceptTermsResponse(null).matches(txInfo)) {
            R.string.accept_terms_response
        } else if (CrowdNodeWelcomeToApiResponse(networkId).matches(txInfo) || PossibleWelcomeResponse(null).matches(txInfo)) {
            R.string.welcome_response
        } else {
            super.getTransactionTypeName(tx, bag)
        }
    }
}