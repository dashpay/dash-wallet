/*
 * Copyright 2021 Dash Core Group.
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

package org.dash.wallet.features.exploredash.repository

import android.content.Context
import com.google.protobuf.Int32Value
import com.google.protobuf.Int64Value
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import org.dash.wallet.features.exploredash.data.model.Atm
import org.dash.wallet.features.exploredash.data.model.Merchant
import org.dash.wallet.features.exploredash.data.model.Protos
import java.io.InputStream
import java.lang.ref.WeakReference
import javax.inject.Inject

interface ExploreRepository {
    suspend fun init()
    fun getLastUpdate(): Long
    fun getAtmDataSize(): Int
    fun getMerchantDataSize(): Int
    suspend fun getAtmData(): Flow<Atm>
    suspend fun getMerchantData(skipFirst: Int): Flow<Merchant>
}

class AssetExploreDatabase @Inject constructor(@ApplicationContext context: Context) :
    ExploreRepository {

    companion object {
        private const val HEADER_SIZE = 3
    }

    private var contextRef: WeakReference<Context> = WeakReference(context)

    private val tmpAtm = Atm()
    private val tmpMerchant = Merchant()

    private lateinit var exploreDataStream: InputStream

    private var updateDate = -1L
    private var atmDataSize = -1
    private var merchantDataSize = -1

    private var offset = 0

    override suspend fun init() = withContext(Dispatchers.IO) {
        exploreDataStream = contextRef.get()!!.assets.open("explore/exploredata.bin")
        updateDate = Int64Value.parseDelimitedFrom(exploreDataStream).value
        atmDataSize = Int32Value.parseDelimitedFrom(exploreDataStream).value
        merchantDataSize = Int32Value.parseDelimitedFrom(exploreDataStream).value
        offset += HEADER_SIZE
    }

    override fun getLastUpdate(): Long {
        return updateDate
    }

    override fun getAtmDataSize(): Int {
        return atmDataSize
    }

    override fun getMerchantDataSize(): Int {
        return merchantDataSize
    }

    override suspend fun getAtmData(): Flow<Atm> = flow {
        for (i in 0 until atmDataSize) {
            val atmData = Protos.AtmData.parseDelimitedFrom(exploreDataStream)
            emit(convert(atmData))
        }
    }

    override suspend fun getMerchantData(skipFirst: Int): Flow<Merchant> = flow {
        for (i in 0 until merchantDataSize) {
            val merchantData = Protos.MerchantData.parseDelimitedFrom(exploreDataStream)
            if (i < skipFirst) continue
            emit(convert(merchantData))
        }
    }

    private fun convert(merchantData: Protos.MerchantData): Merchant {
        return Merchant().apply {
            deeplink = merchantData.deeplink
            plusCode = merchantData.plusCode
            addDate = merchantData.addDate
            updateDate = merchantData.updateDate
            paymentMethod = merchantData.paymentMethod
            merchantId = merchantData.merchantId
            active = merchantData.active
            name = merchantData.name
            address1 = merchantData.address1
            address2 = merchantData.address2
            address3 = merchantData.address2
            address4 = merchantData.address4
            latitude = merchantData.latitude
            longitude = merchantData.longitude
            website = merchantData.website
            phone = merchantData.phone
            territory = merchantData.territory
            city = merchantData.city
            sourceId = merchantData.sourceId
            source = merchantData.source
            logoLocation = merchantData.logoLocation
            googleMaps = merchantData.googleMaps
            coverImage = merchantData.coverImage
            type = merchantData.type
        }
    }

    private fun convert(atmData: Protos.AtmData): Atm {
        return Atm().apply {
            postcode = atmData.postcode
            manufacturer = atmData.manufacturer
            active = atmData.active
            name = atmData.name
            address1 = atmData.address
            latitude = atmData.latitude
            longitude = atmData.longitude
            website = atmData.website
            phone = atmData.phone
            territory = atmData.territory
            city = atmData.city
            source = atmData.source
            sourceId = atmData.sourceId
            logoLocation = atmData.logoLocation
            latitude = atmData.latitude
            coverImage = atmData.coverImage
            type = atmData.type
        }
    }
}