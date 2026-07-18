/*
 * Copyright 2026 Dash Core Group.
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

package org.dash.wallet.common.services

import android.graphics.Bitmap
import com.google.zxing.BarcodeFormat
import kotlinx.coroutines.flow.Flow
import org.dash.wallet.common.data.TxId
import org.dash.wallet.common.data.entity.TransactionMetadata

// ---------------------------------------------------------------------------------------------
// Neutral (dashj-free) adapters over TransactionMetadataProvider taking hex tx ids
// , for feature/integration modules that must not depend on dashj.
// They delegate to the Sha256Hash-typed interface methods, so behavior is identical.
// ---------------------------------------------------------------------------------------------

/** Neutral counterpart of [TransactionMetadataProvider.getTransactionMetadata]. */
suspend fun TransactionMetadataProvider.getTransactionMetadata(txId: String): TransactionMetadata? =
    getTransactionMetadata(TxId.wrap(txId))

/** Neutral counterpart of [TransactionMetadataProvider.observeTransactionMetadata]. */
fun TransactionMetadataProvider.observeTransactionMetadata(txId: String): Flow<TransactionMetadata?> =
    observeTransactionMetadata(TxId.wrap(txId))

/** Neutral counterpart of [TransactionMetadataProvider.markGiftCardTransaction]. */
suspend fun TransactionMetadataProvider.markGiftCardTransaction(txId: String, service: String, iconUrl: String?) =
    markGiftCardTransaction(TxId.wrap(txId), service, iconUrl)

/** Neutral counterpart of [TransactionMetadataProvider.updateGiftCardBarcode]. */
suspend fun TransactionMetadataProvider.updateGiftCardBarcode(
    txId: String,
    index: Int,
    barcodeValue: String,
    barcodeFormat: BarcodeFormat
) = updateGiftCardBarcode(TxId.wrap(txId), index, barcodeValue, barcodeFormat)

/** Neutral counterpart of [TransactionMetadataProvider.getIcon] taking the icon id as a hex string. */
suspend fun TransactionMetadataProvider.getIcon(iconId: String): Bitmap? = getIcon(TxId.wrap(iconId))
