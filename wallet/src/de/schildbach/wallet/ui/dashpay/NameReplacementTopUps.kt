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

import org.bitcoinj.core.Transaction
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
            if (tx.getConfidence(wallet.context).confidenceType == TransactionConfidence.ConfidenceType.DEAD) {
                return Resolution.Dead
            }
            return withKey(wallet, tx)?.let { Resolution.Ready(it) } ?: noKey(tx)
        }
        var pending: String? = null
        for (input in saved.inputs) {
            val parent = wallet.getTransaction(input.outpoint.hash)
            val spender = parent?.getOutput(input.outpoint.index)?.spentBy?.parentTransaction
            val reason = when {
                parent == null -> "the wallet does not know an input of top-up ${saved.txId} yet"
                spender?.getConfidence(wallet.context)?.confidenceType ==
                    TransactionConfidence.ConfidenceType.BUILDING -> return Resolution.Dead
                spender != null -> "an input of top-up ${saved.txId} is spent by unconfirmed ${spender.txId}"
                wallet.isLockedOutput(input.outpoint) -> "an input of top-up ${saved.txId} is locked by the wallet"
                else -> null
            }
            pending = pending ?: reason
        }
        pending?.let { return Resolution.Pending(it) }
        val topUp = withKey(wallet, saved) ?: return noKey(saved)
        wallet.commitTx(topUp)
        return Resolution.Ready(topUp)
    }

    private fun withKey(wallet: Wallet, tx: Transaction): AssetLockTransaction? =
        authentication(wallet).getAssetLockTransaction(tx)?.takeIf { it.assetLockPublicKey != null }

    private fun noKey(tx: Transaction) = Resolution.Pending("the wallet has no key for top-up ${tx.txId}")

    private fun authentication(wallet: Wallet) =
        wallet.getKeyChainExtension(AuthenticationGroupExtension.EXTENSION_ID) as AuthenticationGroupExtension
}
