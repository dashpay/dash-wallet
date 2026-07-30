/*
 * Copyright 2023 Dash Core Group.
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

package org.dash.wallet.common.data

import android.graphics.Bitmap
import androidx.room.Ignore
import org.dash.wallet.common.data.TxId
import org.dash.wallet.common.data.entity.SwapOrder

data class PresentableTxMetadata(
    var txId: TxId,
    var memo: String = "",
    var service: String? = null,
    var customIconId: TxId? = null
) {
    @Ignore var icon: Bitmap? = null
    @Ignore var title: String? = null

    /** Present when this tx funded a DEX swap; drives the conversion row on the home screen. */
    @Ignore var swapOrder: SwapOrder? = null

    // The tx display cache diffs these objects to decide which rows to rebuild, so the
    // @Ignore fields that affect rendering (title, swapOrder) must count in equality.
    // icon stays excluded: bitmaps are re-decoded per emission and compare by identity,
    // which would mark every icon'd row as changed on each emission.
    override fun equals(other: Any?): Boolean {
        return other is PresentableTxMetadata &&
            txId == other.txId &&
            memo == other.memo &&
            service == other.service &&
            customIconId == other.customIconId &&
            title == other.title &&
            swapOrder == other.swapOrder
    }

    override fun hashCode(): Int {
        var result = txId.hashCode()
        result = 31 * result + memo.hashCode()
        result = 31 * result + (service?.hashCode() ?: 0)
        result = 31 * result + (customIconId?.hashCode() ?: 0)
        result = 31 * result + (title?.hashCode() ?: 0)
        result = 31 * result + (swapOrder?.hashCode() ?: 0)
        return result
    }
}
