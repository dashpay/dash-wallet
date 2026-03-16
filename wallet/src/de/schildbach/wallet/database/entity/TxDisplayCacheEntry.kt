/*
 * Copyright 2024 Dash Core Group.
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

package de.schildbach.wallet.database.entity

import android.content.Context
import android.text.format.DateUtils
import androidx.room.Entity
import androidx.room.PrimaryKey
import de.schildbach.wallet.ui.transactions.TransactionRowView
import de.schildbach.wallet_test.R
import org.bitcoinj.core.Coin
import org.bitcoinj.utils.ExchangeRate
import org.bitcoinj.utils.Fiat
import org.dash.wallet.common.util.ResourceString

/**
 * Caches fully-rendered display data for each transaction row so the home screen can
 * show transactions immediately on startup without accessing the wallet.
 *
 * No Android resource IDs are stored — icon and background are stored as stable enum
 * constants ([ICON_*], [BG_*]) that survive app upgrades; title and status are stored
 * as resolved strings.
 *
 * The table is kept up-to-date by reactive observers:
 *  - full rebuild after [wrapAllTransactions] completes
 *  - targeted upserts when transaction metadata or contacts change
 *
 * Room's built-in [PagingSource] reads from this table directly, sorted newest-first
 * by [time].  Room auto-invalidates the pager when any row changes.
 */
@Entity(tableName = "tx_display_cache")
data class TxDisplayCacheEntry(
    /** Unique row id: txId (hex) for individual transactions, groupId for CoinJoin/CrowdNode. */
    @PrimaryKey val rowId: String,
    /** Resolved display title (e.g. "Received", "CoinJoin mixing"). Never a resource ID. */
    val title: String,
    /** Transaction value in satoshis (negative = sent). */
    val valueSatoshis: Long,
    /** Stable icon type constant — see [ICON_*] companions. Converted to drawable at display time. */
    val iconType: Int,
    /** Stable background type constant — see [BG_*] companions. Converted to style at display time. */
    val iconBgType: Int,
    /** Resolved confirmation/status text (e.g. "Processing…"). Empty string if none. */
    val statusText: String,
    /** User memo / payment comment. */
    val comment: String,
    /** Number of underlying transactions (> 1 for CoinJoin/CrowdNode groups). */
    val transactionAmount: Int,
    /** Epoch-millis of the primary transaction's update time. Used for ORDER BY in paging query. */
    val time: Long,
    val hasErrors: Boolean,
    /** Service name (e.g. "CrowdNode") or null for plain sends/receives. */
    val service: String?,
    /** Currency code of the historical exchange rate (e.g. "USD"), or null if unknown. */
    val exchangeRateFiatCode: String?,
    /** Fiat value of the historical exchange rate in smallest fiat unit, or null if unknown. */
    val exchangeRateFiatValue: Long?,
    /** DashPay contact username, or null for non-contact transactions. */
    val contactUsername: String?,
    /** DashPay contact display name, or null for non-contact transactions. */
    val contactDisplayName: String?,
    /** DashPay contact avatar URL, or null for non-contact transactions. */
    val contactAvatarUrl: String?
) {
    companion object {
        // ── Icon type constants (stable across app versions) ────────────────────────
        const val ICON_RECEIVED  = 0
        const val ICON_SENT      = 1
        const val ICON_INTERNAL  = 2
        const val ICON_ERROR     = 3
        const val ICON_GIFT_CARD = 4
        const val ICON_COINJOIN  = 5
        const val ICON_CROWDNODE = 6

        // ── Background type constants ────────────────────────────────────────────────
        const val BG_RECEIVED = 0
        const val BG_SENT     = 1
        const val BG_ERROR    = 2
        const val BG_ORANGE   = 3
        const val BG_NONE     = 4

        /** Cached date/time format — same as [TxResourceMapper.dateTimeFormat] but avoids instantiation. */
        private val DATE_TIME_FORMAT = DateUtils.FORMAT_SHOW_TIME

        /** Convert a [TransactionRowView] (which uses current resource IDs) to a cache entry. */
        fun fromTransactionRowView(row: TransactionRowView, context: Context): TxDisplayCacheEntry {
            val iconType = when (row.icon) {
                R.drawable.ic_transaction_sent        -> ICON_SENT
                R.drawable.ic_internal                -> ICON_INTERNAL
                R.drawable.ic_transaction_failed      -> ICON_ERROR
                R.drawable.ic_gift_card_tx            -> ICON_GIFT_CARD
                R.drawable.ic_coinjoin_mixing_group   -> ICON_COINJOIN
                R.drawable.ic_crowdnode_logo          -> ICON_CROWDNODE
                else                                  -> ICON_RECEIVED
            }
            val iconBgType = when (row.iconBackground) {
                R.style.TxSentBackground     -> BG_SENT
                R.style.TxErrorBackground    -> BG_ERROR
                R.style.TxOrangeBackground   -> BG_ORANGE
                R.style.TxNoBackground       -> BG_NONE
                else                         -> BG_RECEIVED
            }
            val title = row.title?.format(context.resources) ?: ""
            val statusText = if (row.statusRes > 0) context.getString(row.statusRes) else ""
            return TxDisplayCacheEntry(
                rowId                  = row.id,
                title                  = title,
                valueSatoshis          = row.value.value,
                iconType               = iconType,
                iconBgType             = iconBgType,
                statusText             = statusText,
                comment                = row.comment,
                transactionAmount      = row.transactionAmount,
                time                   = row.time,
                hasErrors              = row.hasErrors,
                service                = row.service,
                exchangeRateFiatCode   = row.exchangeRate?.fiat?.currencyCode,
                exchangeRateFiatValue  = row.exchangeRate?.fiat?.value,
                contactUsername        = row.contact?.username,
                contactDisplayName     = row.contact?.displayName,
                contactAvatarUrl       = row.contact?.avatarUrl
            )
        }
    }

    /** Convert back to [TransactionRowView] for the adapter, optionally injecting [contact]. */
    fun toTransactionRowView(contact: DashPayProfile? = null): TransactionRowView {
        val iconRes = when (iconType) {
            ICON_SENT      -> R.drawable.ic_transaction_sent
            ICON_INTERNAL  -> R.drawable.ic_internal
            ICON_ERROR     -> R.drawable.ic_transaction_failed
            ICON_GIFT_CARD -> R.drawable.ic_gift_card_tx
            ICON_COINJOIN  -> R.drawable.ic_coinjoin_mixing_group
            ICON_CROWDNODE -> R.drawable.ic_crowdnode_logo
            else           -> R.drawable.ic_transaction_received
        }
        val bgRes = when (iconBgType) {
            BG_SENT    -> R.style.TxSentBackground
            BG_ERROR   -> R.style.TxErrorBackground
            BG_ORANGE  -> R.style.TxOrangeBackground
            BG_NONE    -> R.style.TxNoBackground
            else       -> R.style.TxReceivedBackground
        }
        val rate = if (exchangeRateFiatCode != null && exchangeRateFiatValue != null) {
            ExchangeRate(Coin.COIN, Fiat.valueOf(exchangeRateFiatCode, exchangeRateFiatValue))
        } else null
        val resolvedContact = contact ?: if (contactUsername != null) {
            DashPayProfile(
                userId        = rowId,
                username      = contactUsername,
                displayName   = contactDisplayName ?: "",
                avatarUrl     = contactAvatarUrl ?: ""
            )
        } else null
        return TransactionRowView(
            title             = if (title.isNotEmpty()) ResourceString(resolvedText = title) else null,
            id                = rowId,
            value             = Coin.valueOf(valueSatoshis),
            exchangeRate      = rate,
            contact           = resolvedContact,
            icon              = iconRes,
            iconBitmap        = null,    // service icons loaded separately from metadata
            iconBackground    = bgRes,
            statusRes         = -1,      // use statusText field instead for cached rows
            comment           = comment,
            transactionAmount = transactionAmount,
            time              = time,
            timeFormat        = DATE_TIME_FORMAT,
            hasErrors         = hasErrors,
            service           = service,
            txWrapper         = null,
            statusText        = statusText.ifEmpty { null }
        )
    }
}