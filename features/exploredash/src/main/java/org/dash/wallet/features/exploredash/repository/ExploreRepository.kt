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
import com.google.firebase.auth.FirebaseAuthException
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.ktx.auth
import com.google.firebase.ktx.Firebase
import com.google.firebase.storage.StorageMetadata
import com.google.firebase.storage.StorageReference
import com.google.firebase.storage.ktx.storage
import com.google.protobuf.Int32Value
import com.google.protobuf.Int64Value
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.transform
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import net.lingala.zip4j.ZipFile
import org.dash.wallet.features.exploredash.data.model.Atm
import org.dash.wallet.features.exploredash.data.model.Merchant
import org.dash.wallet.features.exploredash.data.model.Protos
import org.slf4j.LoggerFactory
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.lang.System.currentTimeMillis
import java.lang.ref.WeakReference
import javax.inject.Inject
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

interface ExploreRepository {
    suspend fun initMetadata()
    suspend fun initData(freshSync: Boolean)
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
        private const val GC_FILE_PATH = "explore/explore.dat"

        private val log = LoggerFactory.getLogger(AssetExploreDatabase::class.java)
    }

    private val auth = Firebase.auth
    private val storage = Firebase.storage

    private var contextRef: WeakReference<Context> = WeakReference(context)

    private lateinit var exploreDataStream: InputStream

    private var updateDate = -1L
    private var atmDataSize = -1
    private var merchantDataSize = -1

    private var offset = -1

    private var assetDataInfo: Pair<String, Long>? = null

    private var remoteDataRef: StorageReference? = null
    private var remoteDataInfo: StorageMetadata? = null

    private var useRemoteData = false

    override suspend fun initMetadata() = withContext(Dispatchers.IO) {

        remoteDataInfo = try {
            ensureAuthenticated()
            remoteDataRef = storage.reference.child(GC_FILE_PATH)
            remoteDataRef!!.metadata.await()
        } catch (ex: Exception) {
            log.warn("Error getting remote data timestamp")
            null
        }
        val remoteDataTimestamp = remoteDataInfo?.updatedTimeMillis ?: -1L

        assetDataInfo = getAssetInfo()
        val assetDataTimestamp = assetDataInfo!!.second

        log.info("Asset data timestamp: $assetDataTimestamp, remote data timestamp: $remoteDataTimestamp")

        useRemoteData = assetDataTimestamp >= remoteDataTimestamp
        updateDate = maxOf(assetDataTimestamp, remoteDataTimestamp)
    }

    override suspend fun initData(freshSync: Boolean) = withContext(Dispatchers.IO) {

        val context = contextRef.get()!!

        val cacheDir = context.cacheDir
        val exploreDatFile = File(cacheDir, DATA_FILE_NAME)

        if (useRemoteData) {
            log.info("Loading remote data")
            val startTime = currentTimeMillis()
            val dataChecksum = remoteDataInfo!!.getCustomMetadata("Data-Checksum")
            log.info("Downloading $remoteDataRef (updated: $updateDate, Data-Checksum: $dataChecksum)")
            val result = remoteDataRef!!.getFile(exploreDatFile).await()
            val totalTime = (currentTimeMillis() - startTime).toFloat() / 1000
            log.info("Downloaded $remoteDataRef (${result.bytesTransferred} as $exploreDatFile [$totalTime s]")
        } else {
            log.info("Loading assets data")
            val assetDataName = assetDataInfo!!.first
            context.assets.open("explore/$assetDataName").use { input ->
                exploreDatFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
        }

        val zipFile = ZipFile(exploreDatFile)
        val dataChecksum = zipFile.comment.toLong()
        val dataChecksumHex = dataChecksum.toString(16)
        log.info("$exploreDatFile Data-Checksum: $dataChecksum (${dataChecksumHex})")
        val pass = dataChecksumHex.hashCode().toString(16).reversed().toCharArray()
        zipFile.setPassword(pass)
        val zipHeader = zipFile.getFileHeader("explore.bin")
        exploreDataStream = zipFile.getInputStream(zipHeader)
        updateDate = Int64Value.parseDelimitedFrom(exploreDataStream).value
        val dataVersion = Int32Value.parseDelimitedFrom(exploreDataStream).value
        log.info("$exploreDatFile data version: $dataVersion")
        offset = HEADER_SIZE
    }

    @Throws(IOException::class)
    private fun getAssetInfo(): Pair<String, Long> {
        val exploreAssets = contextRef.get()!!.assets.list("explore")
        log.info("Assets data: ${exploreAssets?.joinToString(",")}")
        if (exploreAssets?.size == 1) {
            val assetName = exploreAssets[0]
            val timestamp = assetName.replace(".dat", "").toLong()
            return Pair(assetName, timestamp)
        }
        throw IOException("Broken explore assets $exploreAssets")
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

    private suspend fun ensureAuthenticated() {
        if (auth.currentUser == null) {
            signingAnonymously()
        }
    }

    private suspend fun signingAnonymously(): FirebaseUser {
        return suspendCancellableCoroutine { coroutine ->
            auth.signInAnonymously().addOnSuccessListener { result ->
                if (coroutine.isActive) {
                    val user = result.user
                    if (user != null) {
                        coroutine.resume(user)
                    } else {
                        coroutine.resumeWithException(
                            FirebaseAuthException(
                                "-1",
                                "User is null after anon sign in"
                            )
                        )
                    }
                }
            }.addOnFailureListener {
                if (coroutine.isActive) {
                    coroutine.resumeWithException(it)
                }
            }
        }
    }
}