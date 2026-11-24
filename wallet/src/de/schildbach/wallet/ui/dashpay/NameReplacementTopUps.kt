/*
 * Copyright 2026 Dash Core Group
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package de.schildbach.wallet.ui.dashpay

import org.bitcoinj.core.TransactionConfidence
import org.bitcoinj.evolution.AssetLockTransaction
import org.bitcoinj.wallet.AuthenticationKeyChain
import org.bitcoinj.wallet.Wallet
import org.bitcoinj.wallet.authentication.AuthenticationGroupExtension

/** Finds the top-up an attempt dispatched in the wallet, without ever building another one. */
open class NameReplacementTopUps {
    sealed interface Resolution {
        data class Ready(val tx: AssetLockTransaction) : Resolution
        object Dead : Resolution
        data class Pending(val reason: String) : Resolution
    }

    open fun currentKeyHash(wallet: Wallet): ByteArray =
        authentication(wallet).currentKey(AuthenticationKeyChain.KeyChainType.BLOCKCHAIN_IDENTITY_TOPUP).pubKeyHash

    open fun findByKey(wallet: Wallet, keyHash: ByteArray): AssetLockTransaction? =
        authentication(wallet).topupFundingTransactions.firstOrNull {
            it.assetLockPublicKeyId.bytes.contentEquals(keyHash)
        }

    open fun resolve(wallet: Wallet, savedTx: ByteArray): Resolution {
        val saved = AssetLockTransaction(wallet.params, savedTx)
        wallet.getTransaction(saved.txId)?.let { tx ->
            return if (tx.getConfidence(wallet.context).confidenceType == TransactionConfidence.ConfidenceType.DEAD) {
                Resolution.Dead
            } else {
                Resolution.Ready(authentication(wallet).getAssetLockTransaction(tx))
            }
        }
        for (input in saved.inputs) {
            val parent = wallet.getTransaction(input.outpoint.hash)
                ?: return Resolution.Pending("the wallet does not know an input of top-up ${saved.txId} yet")
            val spender = parent.getOutput(input.outpoint.index).spentBy?.parentTransaction ?: continue
            return if (spender.getConfidence(wallet.context).confidenceType == TransactionConfidence.ConfidenceType.BUILDING) {
                Resolution.Dead
            } else {
                Resolution.Pending("an input of top-up ${saved.txId} is spent by unconfirmed ${spender.txId}")
            }
        }
        wallet.commitTx(saved)
        return Resolution.Ready(saved)
    }

    private fun authentication(wallet: Wallet) =
        wallet.getKeyChainExtension(AuthenticationGroupExtension.EXTENSION_ID) as AuthenticationGroupExtension
}
