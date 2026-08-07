package de.schildbach.wallet.data

import de.schildbach.wallet.database.entity.TxDisplayCacheEntry
import org.bitcoinj.core.Sha256Hash
import org.bitcoinj.core.Transaction

/**
 * A contact/notification payment row.
 *
 * Backed by EITHER a dashj [tx] (the pre-cutover / bridged path) OR, when [tx] is null,
 * SOLELY by the SDK-authored [correctedDisplay] cache entry — a received contact payment
 * the SDK owns but that was never bridged into the held dashj wallet, so dashj has no
 * [Transaction] for it. At least one of [tx] / [correctedDisplay] is always present.
 *
 * @param correctedDisplay the SDK-corrected display record from `tx_display_cache`
 *   (pre-resolved in the ViewModel, keyed by lowercase display-hex txid). When [tx] is
 *   non-null this CORRECTS the dashj row whose [Transaction] mis-reads direction/amount/
 *   status (a contact send surfaces only its +change). When [tx] is null it is the SOLE
 *   source for the row (an SDK-only received contact payment). Null only for a pre-cutover
 *   dashj row, which renders from [tx] exactly as before the cutover.
 */
data class NotificationItemPayment(
    val tx: Transaction? = null,
    val correctedDisplay: TxDisplayCacheEntry? = null
) : NotificationItem() {

    /**
     * The txid this row represents: the dashj [tx]'s id when present, otherwise parsed from
     * the SDK-only [correctedDisplay]'s rowId (lowercase display-hex, the same convention
     * [Sha256Hash.toString] uses). Lets the row open the transaction-detail dialog without
     * needing a dashj [Transaction].
     */
    val txId: Sha256Hash
        get() = tx?.txId ?: Sha256Hash.wrap(correctedDisplay!!.rowId)

    override fun getId() = txId.toString()

    // Both sources are ALREADY epoch milliseconds — dashj's `updateTime.time` and the cache
    // entry's `time` — and every other NotificationItem returns millis too, so returning them
    // unscaled keeps the sort key and the relative-time display consistent across row types.
    // (Do not reintroduce a `* 1000` here: it would push payment rows far past contact/alert
    // rows in the mixed-type sort and break relative-time rendering. See master's fix.)
    override fun getDate() = tx?.updateTime?.time ?: correctedDisplay!!.time
}
