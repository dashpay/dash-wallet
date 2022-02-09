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
import com.google.firebase.storage.StorageReference
import com.google.firebase.storage.ktx.storage
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.tasks.await
import org.slf4j.LoggerFactory
import java.io.File
import java.lang.System.currentTimeMillis
import java.lang.ref.WeakReference
import javax.inject.Inject
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

interface ExploreRepository {
    suspend fun getLastUpdate(): Long
    suspend fun download()
}

@Suppress("BlockingMethodInNonBlockingContext")
class AssetExploreDatabase @Inject constructor(@ApplicationContext context: Context) :
    ExploreRepository {

    companion object {
        const val DATA_FILE_NAME = "explore.db"
        private const val GC_FILE_PATH = "explore/explore.db"

        private val log = LoggerFactory.getLogger(AssetExploreDatabase::class.java)
    }

    private val auth = Firebase.auth
    private val storage = Firebase.storage

    private var contextRef: WeakReference<Context> = WeakReference(context)

    private var remoteDataRef: StorageReference? = null

    override suspend fun getLastUpdate(): Long {
        ensureAuthenticated()
        val remoteDataInfo = try {
            remoteDataRef = storage.reference.child(GC_FILE_PATH)
            remoteDataRef!!.metadata.await()
        } catch (ex: Exception) {
            log.warn("error getting remote data timestamp")
            null
        }
        return remoteDataInfo?.updatedTimeMillis ?: -1L
    }

    override suspend fun download() {
        ensureAuthenticated()
        val context = contextRef.get()!!
        val cacheDir = context.cacheDir
        val exploreDatFile = File(cacheDir, DATA_FILE_NAME)

        log.info("downloading explore db from server")
        val startTime = currentTimeMillis()
        val result = remoteDataRef!!.getFile(exploreDatFile).await()
        val totalTime = (currentTimeMillis() - startTime).toFloat() / 1000
        log.info("downloaded $remoteDataRef (${result.bytesTransferred} as $exploreDatFile [$totalTime s]")
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
                            FirebaseAuthException("-1", "User is null after anon sign in")
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