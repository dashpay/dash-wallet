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

package org.dash.wallet.integrations.crowdnode.model

import com.google.gson.annotations.SerializedName

data class CrowdNodeTx(
    @SerializedName("FundingType")
    val fundingType: String,
    @SerializedName("Amount")
    val amount: Double,
    @SerializedName("Time")
    val time: Int,
    @SerializedName("TimeReceived")
    val timeReceived: Int,
    @SerializedName("TxId")
    val txId: String,
    @SerializedName("PortalUserId")
    val portalUserId: Int,
    @SerializedName("Status")
    val status: String,
    @SerializedName("Comment")
    val comment: String,
    @SerializedName("TimeUTC")
    val timeUTC: String,
    @SerializedName("Id")
    val id: Int,
    @SerializedName("UpdatedOn")
    val updatedOn: String,
    @SerializedName("SyncFromPrivateOn")
    val syncFromPrivateOn: String,
    @SerializedName("UpdatedInPrivateOn")
    val updatedInPrivateOn: String
)
