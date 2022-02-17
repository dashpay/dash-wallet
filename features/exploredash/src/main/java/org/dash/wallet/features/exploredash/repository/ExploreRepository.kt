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
import net.lingala.zip4j.ZipFile
import org.slf4j.LoggerFactory
import java.io.*
import java.lang.System.currentTimeMillis
import java.lang.ref.WeakReference
import javax.inject.Inject
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

interface ExploreRepository {
    suspend fun getRemoteTimestamp(): Long
    fun getDatabaseInputStream(file: File): InputStream?
    fun getUpdateFile(): File
    var localTimestamp: Long
    suspend fun download()
    fun deleteOldDB(dbFile: File)
    fun preloadFromAssets(dbUpdateFile: File)
    fun finalizeUpdate(dbUpdateFile: File)
}

@Suppress("BlockingMethodInNonBlockingContext")
class GCExploreDatabase @Inject constructor(@ApplicationContext context: Context) :
    ExploreRepository {

    companion object {
        const val DATA_FILE_NAME = "explore.db"
        const val DATA_TMP_FILE_NAME = "explore.tmp"
        private const val GC_FILE_PATH = "explore/explore.db"
        private const val DB_ASSET_FILE_NAME = "explore/$DATA_FILE_NAME"

        private const val SHARED_PREFS_NAME = "explore"
        private const val PREFS_LOCAL_DB_TIMESTAMP_KEY = "local_db_timestamp"

        private val log = LoggerFactory.getLogger(GCExploreDatabase::class.java)
    }

    private val auth = Firebase.auth
    private val storage = Firebase.storage

    private var contextRef: WeakReference<Context> = WeakReference(context)

    private var remoteDataRef: StorageReference? = null

    private val preferences = context.getSharedPreferences(SHARED_PREFS_NAME, Context.MODE_PRIVATE)

    private var updateTimestampCache = -1L

    override var localTimestamp: Long
        get() = preferences.getLong(PREFS_LOCAL_DB_TIMESTAMP_KEY, 0)
        set(value) {
            preferences.edit().apply {
                putLong(PREFS_LOCAL_DB_TIMESTAMP_KEY, value)
            }.apply()
        }

    override suspend fun getRemoteTimestamp(): Long {
        ensureAuthenticated()
        val remoteDataInfo = try {
            remoteDataRef = storage.reference.child(GC_FILE_PATH)
            remoteDataRef!!.metadata.await()
        } catch (ex: Exception) {
            log.warn("error getting remote data timestamp", ex)
            null
        }
        val dataTimestamp = remoteDataInfo?.getCustomMetadata("Data-Timestamp")?.toLong()
        return dataTimestamp ?: -1L
    }

    override suspend fun download() {
        ensureAuthenticated()

        val context = contextRef.get()!!
        val cacheDir = context.cacheDir

        val tmpFile = File(cacheDir, DATA_TMP_FILE_NAME)
        val tmpFileDelete = tmpFile.delete()

        log.info("downloading explore db from server ($tmpFileDelete)")
        val startTime = currentTimeMillis()
        val result = remoteDataRef!!.getFile(tmpFile).await()
        val totalTime = (currentTimeMillis() - startTime).toFloat() / 1000
        log.info("downloaded $remoteDataRef (${result.bytesTransferred} as $tmpFile [$totalTime s]")

        val updateFile = File(cacheDir, DATA_FILE_NAME)
        val updateFileDelete = updateFile.delete()
        val tmpFileRename = tmpFile.renameTo(updateFile)

        log.info("update file $updateFile created ($updateFileDelete, $tmpFileRename)")
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

    @Throws(IOException::class)
    override fun getDatabaseInputStream(file: File): InputStream? {
        val zipFile = ZipFile(file)
        val comment: Array<String> = zipFile.comment.split("#".toRegex()).toTypedArray()
        updateTimestampCache = comment[0].toLong()
        val checksum = comment[1]
        log.info("package timestamp {}, checksum {}", updateTimestampCache, checksum)
        zipFile.setPassword(checksum.toCharArray())
        val zipHeader = zipFile.getFileHeader("explore.db")
        return zipFile.getInputStream(zipHeader)
    }

    override fun getUpdateFile(): File {
        return File(contextRef.get()!!.cacheDir, DATA_FILE_NAME)
    }

    override fun deleteOldDB(dbFile: File) {
        try {
            var dbDelete = false
            if (dbFile.exists()) {
                dbDelete = dbFile.delete()
            }
            val dbShmFile = File(dbFile.absolutePath + "-shm")
            var dbShmDelete = false
            if (dbShmFile.exists()) {
                dbShmDelete = dbShmFile.delete()
            }
            val dbWalFile = File(dbFile.absolutePath + "-wal")
            var dbWalDelete = false
            if (dbWalFile.exists()) {
                dbWalDelete = dbWalFile.delete()
            }
            log.info("delete existing explore db ({}, {}, {})", dbDelete, dbShmDelete, dbWalDelete)
        } catch (ex: SecurityException) {
            log.warn("unable to delete explore db", ex)
        }
    }

    @Throws(IOException::class)
    override fun preloadFromAssets(dbUpdateFile: File) {
        log.info("preloading explore db from assets ${dbUpdateFile.absolutePath}")
        try {
            contextRef.get()!!.assets.open(DB_ASSET_FILE_NAME).use { inputStream ->
                FileOutputStream(dbUpdateFile).use { outputStream ->
                    inputStream.copyTo(outputStream)
                }
            }
        } catch (ex: FileNotFoundException) {
            log.warn("missing {}, explore db will be empty", DB_ASSET_FILE_NAME)
        }
    }

    override fun finalizeUpdate(dbUpdateFile: File) {
        if (!dbUpdateFile.delete()) {
            log.error("unable to delete " + dbUpdateFile.absolutePath)
        }
        localTimestamp = updateTimestampCache
        log.info("successfully loaded new version of explode db")
    }
}
