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

package de.schildbach.wallet.service.platform.sdk

import org.dash.wallet.common.data.TxId
import org.dash.wallet.common.data.entity.TransactionMetadata

/**
 * Resolves a txid against the Kotlin SDK's transaction store, for the metadata
 * provider. A transaction the SDK found on its own (a DashPay one-way-contact
 * receive found by the contact backfill, any post-cutover tx) is absent from
 * the held dashj wallet, so the provider cannot derive a metadata row from a
 * dashj Transaction and would otherwise drop that tx's metadata.
 *
 * Kept narrow (no SDK or dashj types) so the provider stays host-JVM testable.
 */
interface SdkTxMetadataSource {
    /**
     * A default (no memo, no user-set category) metadata row for [txId] built
     * from the SDK's stored transaction, or null when the SDK store is not
     * open or does not hold the transaction. Must be cheap: the provider may
     * call it once per platform metadata document during a sync.
     */
    suspend fun defaultMetadataFor(txId: TxId): TransactionMetadata?
}
