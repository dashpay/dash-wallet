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
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.transform
import kotlinx.coroutines.withContext
import net.lingala.zip4j.ZipFile
import org.dash.wallet.features.exploredash.data.model.Atm
import org.dash.wallet.features.exploredash.data.model.Merchant
import org.dash.wallet.features.exploredash.data.model.Protos
import java.io.File
import java.io.InputStream
import java.lang.ref.WeakReference
import javax.inject.Inject

interface ExploreRepository {
    suspend fun init()
    fun getLastUpdate(): Long
    fun getAtmDataSize(): Int
    fun getMerchantDataSize(): Int
    suspend fun getAtmData(skipFirst: Int): Flow<Atm>
    suspend fun getMerchantData(skipFirst: Int): Flow<Merchant>
}

@Suppress("BlockingMethodInNonBlockingContext")
class AssetExploreDatabase @Inject constructor(@ApplicationContext context: Context) :
    ExploreRepository {

    companion object {
        private const val HEADER_SIZE = 2 //update date, data version
        private const val DATA_FILE_NAME = "explore.dat"
    }

    private var contextRef: WeakReference<Context> = WeakReference(context)

    private lateinit var exploreDataStream: InputStream

    private var updateDate = -1L
    private var atmDataSize = -1
    private var merchantDataSize = -1

    private var offset = -1

    override suspend fun init() = withContext(Dispatchers.IO) {
        val cacheDir = contextRef.get()!!.cacheDir
        val file = File(cacheDir, DATA_FILE_NAME)
//        file.createNewFile()
        contextRef.get()!!.assets.open("explore/$DATA_FILE_NAME").use { input ->
            file.outputStream().use { output ->
                input.copyTo(output)
            }
        }
        val zipFile = ZipFile(file)
        val pass =
            zipFile.comment.toLong().toString(16).hashCode().toString(16).reversed().toCharArray()
        zipFile.setPassword(pass)
        val zipHeader = zipFile.getFileHeader("explore.bin")
        exploreDataStream = zipFile.getInputStream(zipHeader)
        updateDate = Int64Value.parseDelimitedFrom(exploreDataStream).value
        val dataVersion = Int32Value.parseDelimitedFrom(exploreDataStream).value
        offset = HEADER_SIZE
    }

    override fun getLastUpdate(): Long {
        return updateDate
    }

    override fun getAtmDataSize(): Int {
        if (offset == HEADER_SIZE) {
            atmDataSize = Int32Value.parseDelimitedFrom(exploreDataStream).value
        } else {
            throw IllegalStateException("Read header first")
        }
        return atmDataSize
    }

    override fun getMerchantDataSize(): Int {
        if (offset == (HEADER_SIZE + atmDataSize)) {
            merchantDataSize = Int32Value.parseDelimitedFrom(exploreDataStream).value
        } else {
            throw IllegalStateException("Read header first!")
        }
        return merchantDataSize
    }

    override suspend fun getAtmData(skipFirst: Int): Flow<Atm> = flow {
        while (offset < (HEADER_SIZE + atmDataSize)) {
            val atmData = Protos.AtmData.parseDelimitedFrom(exploreDataStream)
            offset++
            if (offset <= (skipFirst + HEADER_SIZE)) continue
            emit(atmData)
        }
    }.transform {
        emit(convert(it))
    }.buffer()

    override suspend fun getMerchantData(skipFirst: Int): Flow<Merchant> = flow {
        while (offset < (HEADER_SIZE + atmDataSize + merchantDataSize)) {
            val merchantData = Protos.MerchantData.parseDelimitedFrom(exploreDataStream)
            offset++
            if (offset <= (skipFirst + HEADER_SIZE + atmDataSize)) continue
            emit(merchantData)
        }
    }.transform {
        emit(convert(it))
    }.buffer()

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