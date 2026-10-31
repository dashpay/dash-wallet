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

@file:OptIn(FlowPreview::class)

package de.schildbach.wallet.transactions

import de.schildbach.wallet.service.platform.sdk.SdkTxDetail
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.suspendCancellableCoroutine
import org.bitcoinj.core.Address
import org.bitcoinj.core.AddressFormatException
import org.bitcoinj.core.Coin
import org.bitcoinj.core.Sha256Hash
import org.bitcoinj.core.Transaction
import org.bitcoinj.core.TransactionBag
import org.bitcoinj.core.TransactionConfidence
import org.bitcoinj.script.ScriptException
import org.bitcoinj.script.ScriptPattern
import org.bitcoinj.utils.Threading
import org.dash.wallet.common.transactions.TransactionCategory
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume

/**
 * Dashj-typed transaction helpers for wallet-module internals — the former
 * `org.dash.wallet.common.transactions.TransactionUtils`, moved here unchanged when the
 * common module was neutralized.
 */
object TransactionUtils {
    fun getWalletAddressOfReceived(tx: Transaction, bag: TransactionBag): Address? {
        for (output in tx.outputs) {
            try {
                if (output.isMine(bag)) {
                    return output.scriptPubKey.getToAddress(tx.params, true)
                }
            } catch (x: ScriptException) {
                // swallow
            }
        }
        return null
    }

    fun getFromAddressOfSent(tx: Transaction): List<Address> {
        val result = mutableListOf<Address>()

        for (input in tx.inputs) {
            try {
                val connectedTransaction = input.connectedTransaction
                if (connectedTransaction != null) {
                    val output = connectedTransaction.getOutput(input.outpoint.index)
                    result.add(output.scriptPubKey.getToAddress(tx.params, true))
                }
            } catch (x: ScriptException) {
                // swallow
            }
        }

        return result
    }

    fun getToAddressOfReceived(tx: Transaction, bag: TransactionBag): List<Address> {
        val result = mutableListOf<Address>()

        for (output in tx.outputs) {
            try {
                if (output.isMine(bag)) {
                    result.add(output.scriptPubKey.getToAddress(tx.params, true))
                }
            } catch (x: ScriptException) {
                // swallow
            }
        }

        return result
    }

    fun getToAddressOfSent(tx: Transaction, bag: TransactionBag): List<Address> {
        val result = mutableListOf<Address>()

        for (output in tx.outputs) {
            try {
                if (!output.isMine(bag)) {
                    result.add(output.scriptPubKey.getToAddress(tx.params, true))
                }
            } catch (x: ScriptException) {
                // swallow
            }
        }

        return result
    }

    /**
     * Bug A: "Sent to" addresses for the transaction-details sheet of a
     * dashj-held, SDK-authored send. The held dashj wallet doesn't recognize
     * such a send's own change output as "mine" ([getToAddressOfSent]'s
     * `isMine(bag)` check is false for it too), so it would otherwise list
     * that change address as a second recipient. [sdkOverride], when present
     * and successfully decoded, carries the SDK's own change-filtered output
     * addresses (derived from its TXO ownership records) and is preferred —
     * except when it comes back empty: that means the SDK couldn't resolve a
     * recipient address at all (e.g. a bare P2PK output; see
     * [SdkTxDetail.outputAddresses]), not that there is none, so the
     * recipient list is withheld rather than falling back to
     * [getToAddressOfSent] (see below). The override's addresses can also be
     * encoded in a scheme dashj's base58 decoder rejects outright (e.g. a
     * bech32 witness-program output — the pinned SDK encodes those, not as
     * base58); a decode failure is treated the same as an empty list rather
     * than propagating, since crashing the details sheet is worse than
     * showing no recipient. [getToAddressOfSent] is
     * only used as a fallback when there is NO override at all (a plain
     * dashj-tracked tx, where its `isMine(bag)` check is trustworthy) —
     * when an override IS present but unusable, `bag` is the very wallet
     * the override exists to correct for (see class doc), so its
     * `isMine(bag)` can reproduce MO-1108's own bug (the change output
     * misread as a second recipient); no recipient list is shown instead,
     * consistent with the SDK-only detail's own unresolved-output handling
     * (`SdkTxDetailProvider.buildSdkTxDetail`'s `hasUnresolvedPaymentOutput`).
     */
    fun getSentToAddressesForDetail(tx: Transaction, bag: TransactionBag, sdkOverride: SdkTxDetail?): List<Address> {
        if (sdkOverride == null) return getToAddressOfSent(tx, bag)
        val overrideAddresses = sdkOverride.takeIf { !it.decodeFailed }?.outputAddresses?.takeIf { it.isNotEmpty() }
        val decoded = overrideAddresses?.let { addresses ->
            try {
                addresses.map { Address.fromBase58(tx.params, it) }
            } catch (x: AddressFormatException) {
                null
            }
        }
        return decoded ?: emptyList()
    }

    /**
     * Bug A: the fee shown on the transaction-details sheet. `tx.fee` is null
     * for a dashj-held, SDK-authored send whose inputs the held wallet can't
     * fully connect (it never tracked the spent outputs) — [sdkOverride]'s
     * SDK-recorded/derived fee is preferred when present.
     */
    fun getFeeForDetail(tx: Transaction, sdkOverride: SdkTxDetail?): Coin? =
        sdkOverride?.feeDuffs?.let { Coin.valueOf(it) } ?: tx.fee

    /** get OP_RETURNS of sent tx's */
    fun getOpReturnsOfSent(
        tx: Transaction,
        bag: TransactionBag
    ): List<String> {
        val result = mutableListOf<String>()
        if (!tx.isCoinBase) {
            for (output in tx.outputs) {
                try {
                    if (!output.isMine(bag) && ScriptPattern.isOpReturn(output.scriptPubKey)) {
                        result.add("OP RETURN")
                    }
                } catch (x: ScriptException) {
                    // swallow
                }
            }
        }

        return result
    }

    fun Transaction.isEntirelySelf(bag: TransactionBag): Boolean {
        // A transaction that spends nothing of ours cannot be a self-transfer.
        // Without this guard, an input-less transaction — e.g. a Platform
        // credit-withdrawal (asset-unlock) payout, which is funded from the
        // credit pool and quorum-signed in its payload — whose outputs all pay
        // our own addresses satisfies both loops below vacuously and renders
        // as an internal transfer instead of a receive.
        if (inputs.isEmpty()) {
            return false
        }

        for (input in inputs) {
            val connectedOutput = input.connectedOutput

            if (connectedOutput == null || !connectedOutput.isMine(bag)) {
                return false
            }
        }

        for (output in outputs) {
            if (!output.isMine(bag)) {
                return false
            }
        }

        return true
    }

    val Transaction.allOutputAddresses: List<Address>
        get() {
            val result = mutableListOf<Address>()

            outputs.forEach {
                try {
                    val script = it.scriptPubKey
                    result.add(script.getToAddress(this.params, true))
                } catch (x: ScriptException) {
                    // swallow
                }
            }
            return result
        }
}

fun Flow<Transaction>.batchAndFilterUpdates(timeInterval: Long = 500): Flow<List<Transaction>> {
    val latestTransactions = ConcurrentHashMap<Sha256Hash, Transaction>()

    return this
        .onEach { transaction ->
            // Update the latest transaction for the hash
            latestTransactions[transaction.txId] = transaction
        }
        .sample(timeInterval) // Emit events every [timeInterval]
        .map {
            latestTransactions.values.toList().also {
                latestTransactions.clear()
            }
        }
        .filter { it.isNotEmpty() }
}

/** The former dashj-typed `TransactionCategory.fromTransaction`, as a companion extension. */
fun TransactionCategory.Companion.fromTransaction(
    type: Transaction.Type,
    value: Coin,
    isInternal: Boolean
): TransactionCategory {
    return when (type) {
        Transaction.Type.TRANSACTION_COINBASE -> TransactionCategory.MiningReward
        Transaction.Type.TRANSACTION_PROVIDER_REGISTER -> TransactionCategory.MasternodeRegister
        Transaction.Type.TRANSACTION_PROVIDER_UPDATE_REGISTRAR -> TransactionCategory.MasternodeUpdateRegistrar
        Transaction.Type.TRANSACTION_PROVIDER_UPDATE_SERVICE -> TransactionCategory.MasternodeUpdateService
        Transaction.Type.TRANSACTION_PROVIDER_UPDATE_REVOKE -> TransactionCategory.MasternodeUpdateRevoke
        else -> {
            when {
                value.isPositive -> TransactionCategory.Received
                isInternal -> TransactionCategory.Internal
                else -> TransactionCategory.Sent
            }
        }
    }
}

/** The former `org.dash.wallet.common.transactions.waitToMatchFilters`, on dashj filters. */
suspend fun Transaction.waitToMatchFilters(vararg filters: WalletTransactionFilter) {
    return suspendCancellableCoroutine { continuation ->
        var transactionConfidenceListener: TransactionConfidence.Listener? = null
        transactionConfidenceListener = TransactionConfidence.Listener { _, _ ->
            if (filters.isEmpty() || filters.any { it.matches(this) }) {
                confidence.removeEventListener(transactionConfidenceListener)

                if (continuation.isActive) {
                    continuation.resume(Unit)
                }
            }
        }

        // Check if already matches
        if (filters.isEmpty() || filters.any { it.matches(this) }) {
            if (continuation.isActive) {
                continuation.resume(Unit)
            }
            return@suspendCancellableCoroutine
        }

        this.confidence.addEventListener(Threading.USER_THREAD, transactionConfidenceListener)

        continuation.invokeOnCancellation {
            confidence.removeEventListener(transactionConfidenceListener)
        }
    }
}
