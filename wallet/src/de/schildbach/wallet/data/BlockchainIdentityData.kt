/*
 * Copyright 2020 Dash Core Group.
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

package de.schildbach.wallet.data

import androidx.room.Entity
import androidx.room.Ignore
import androidx.room.PrimaryKey
import org.bitcoinj.core.Coin
import org.bitcoinj.core.Sha256Hash
import org.bitcoinj.evolution.CreditFundingTransaction
import org.bitcoinj.wallet.Wallet
import org.dashevo.dashpay.BlockchainIdentity
import org.dashevo.dpp.identity.IdentityPublicKey

@Entity(tableName = "blockchain_identity")
data class BlockchainIdentityData(var creationState: CreationState = CreationState.NONE,
                                  var creationStateErrorMessage: String?,
                                  var username: String?,
                                  var restoring: Boolean,
                                  var creditFundingTxId: Sha256Hash? = null,
                                  var preorderSalt: ByteArray? = null,
                                  var registrationStatus: BlockchainIdentity.RegistrationStatus? = null,
                                  var usernameStatus: BlockchainIdentity.UsernameStatus? = null,
                                  var creditBalance: Coin? = null,
                                  var activeKeyCount: Int? = null,
                                  var totalKeyCount: Int? = null,
                                  var keysCreated: Long? = null,
                                  var currentMainKeyIndex: Int? = null,
                                  var currentMainKeyType: IdentityPublicKey.TYPES? = null) {

    @PrimaryKey
    var id = 1
        set(value) {
            field = 1
        }

    @Ignore
    private var creditFundingTransactionCache: CreditFundingTransaction? = null

    fun findCreditFundingTransaction(wallet: Wallet?): CreditFundingTransaction? {
        if (creditFundingTxId == null) {
            return null
        }
        if (wallet != null) {
            creditFundingTransactionCache = wallet.getTransaction(creditFundingTxId)?.run {
                wallet.getCreditFundingTransaction(this)
            }
        }
        return creditFundingTransactionCache
    }

    fun getIdentity(wallet: Wallet?): String? = findCreditFundingTransaction(wallet)?.let { it.creditBurnIdentityIdentifier.toStringBase58() }

    enum class CreationState {
        NONE,   // this should always be the first value
        UPGRADING_WALLET,
        CREDIT_FUNDING_TX_CREATING,
        CREDIT_FUNDING_TX_SENDING,
        CREDIT_FUNDING_TX_SENT,
        CREDIT_FUNDING_TX_CONFIRMED,
        IDENTITY_REGISTERING,
        IDENTITY_REGISTERED,
        PREORDER_REGISTERING,
        PREORDER_REGISTERED,
        USERNAME_REGISTERING,
        USERNAME_REGISTERED,
        DASHPAY_PROFILE_CREATING,
        DASHPAY_PROFILE_CREATED,
        DONE,
        DONE_AND_DISMISS // this should always be the last value
    }
}