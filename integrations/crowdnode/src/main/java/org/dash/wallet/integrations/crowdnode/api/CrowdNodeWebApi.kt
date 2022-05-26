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

package org.dash.wallet.integrations.crowdnode.api

import org.dash.wallet.integrations.crowdnode.model.*
import retrofit2.Response
import retrofit2.http.*

interface CrowdNodeWebApi {
    @GET("odata/apifundings/GetFunds(address='{address}')")
    suspend fun getTransactions(
        @Path("address") address: String
    ): Response<List<CrowdNodeTx>>

    @GET("odata/apifundings/GetBalance(address='{address}')")
    suspend fun getBalance(
        @Path("address") address: String
    ): Response<CrowdNodeBalance>

    @GET("odata/apiaddresses/IsApiAddressInUse(address='{address}')")
    suspend fun isAddressInUse(
        @Path("address") address: String
    ): Response<IsAddressInUse>

    @GET("odata/apiaddresses/AddressStatus(address='{address}')")
    suspend fun addressStatus(
        @Path("address") address: String
    ): Response<AddressStatus>

    @GET("odata/apimessages/SendMessage(address='{address}',message='{message}',signature='{signature}',messagetype=1)")
    suspend fun sendSignedMessage(
        @Path("address") address: String,
        @Path("message") message: String,
        @Path("signature") signature: String,
    ): Response<SendMessageResult>
}
