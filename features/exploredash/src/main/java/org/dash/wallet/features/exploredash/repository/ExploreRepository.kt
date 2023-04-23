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
import androidx.annotation.VisibleForTesting
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthException
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.storage.FirebaseStorage
import com.google.firebase.storage.StorageReference
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.tasks.await
import net.lingala.zip4j.ZipFile
import org.dash.wallet.common.util.Constants
import org.dash.wallet.features.exploredash.utils.ExploreConfig
import org.slf4j.LoggerFactory
import java.io.*
import java.lang.System.currentTimeMillis
import javax.inject.Inject
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.max

interface ExploreRepository {
    suspend fun getRemoteTimestamp(): Long
    fun getDatabaseInputStream(file: File): InputStream?
    fun getTimestamp(file: File): Long
    fun getUpdateFile(): File
    suspend fun download()
    fun markDbForDeletion(dbFile: File)
    suspend fun preloadFromAssetsInto(dbUpdateFile: File, checkTestDB: Boolean): Boolean
    fun finalizeUpdate()
}

@Suppress("BlockingMethodInNonBlockingContext")
class GCExploreDatabase @Inject constructor(
    @ApplicationContext private val context: Context,
    private val exploreConfig: ExploreConfig,
    private val auth: FirebaseAuth,
    private val storage: FirebaseStorage
) : ExploreRepository {

    companion object {
        const val DATA_FILE_NAME = "explore.db"
        const val DATA_TMP_FILE_NAME = "explore.tmp"

        private val log = LoggerFactory.getLogger(GCExploreDatabase::class.java)

        private fun getPreloadedDbFileName(isTestDB: Boolean): String {
            return if (isTestDB) {
                "explore/explore-testnet.db"
            } else {
                "explore/explore.db"
            }
        }
    }

    private var remoteDataRef: StorageReference? = null
    private var updateTimestampCache = -1L

    @VisibleForTesting
    val configScope = CoroutineScope(Dispatchers.IO)

    override suspend fun getRemoteTimestamp(): Long {
        val remoteDataInfo =
            try {
                ensureAuthenticated()
                remoteDataRef = storage.reference.child(Constants.EXPLORE_GC_FILE_PATH)
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
        exploreConfig.set(ExploreConfig.LAST_SYNC_TIMESTAMP, currentTimeMillis())

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
            auth
                .signInAnonymously()
                .addOnSuccessListener { result ->
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
                }
                .addOnFailureListener {
                    if (coroutine.isActive) {
                        coroutine.resumeWithException(it)
                    }
                }
        }
    }

    private fun extractComment(zipFile: ZipFile): Array<String> {
        return zipFile.comment.split("#".toRegex()).toTypedArray()
    }

    @Throws(IOException::class)
    override fun getTimestamp(file: File): Long {
        val zipFile = ZipFile(file)
        val comment = extractComment(zipFile)
        return comment[0].toLong()
    }

    @Throws(IOException::class)
    override fun getDatabaseInputStream(file: File): InputStream? {
        val zipFile = ZipFile(file)
        val comment = extractComment(zipFile)
        updateTimestampCache = comment[0].toLong()
        val checksum = comment[1]
        log.info("package timestamp {}, checksum {}", updateTimestampCache, checksum)
        zipFile.setPassword(checksum.toCharArray())
        val zipHeader = zipFile.getFileHeader("explore.db")
        return zipFile.getInputStream(zipHeader)
    }

    override fun getUpdateFile(): File {
        return File(context.cacheDir, DATA_FILE_NAME)
    }

    override fun markDbForDeletion(dbFile: File) {
        try {
            if (dbFile.exists()) {
                dbFile.deleteOnExit()
            }
            val dbShmFile = File(dbFile.absolutePath + "-shm")
            if (dbShmFile.exists()) {
                dbShmFile.deleteOnExit()
            }
            val dbWalFile = File(dbFile.absolutePath + "-wal")
            if (dbWalFile.exists()) {
                dbWalFile.deleteOnExit()
            }
            log.info("existing explore db files to be deleted on exit")
        } catch (ex: SecurityException) {
            log.warn("unable to delete explore db", ex)
        }
    }

    override suspend fun preloadFromAssetsInto(dbUpdateFile: File, checkTestDB: Boolean): Boolean {
        log.info("preloading explore db from assets ${dbUpdateFile.absolutePath}, test database: $checkTestDB")
        val preloadedDbFileName = getPreloadedDbFileName(checkTestDB)

        try {
            withContext(Dispatchers.IO) {
                context.assets.open(preloadedDbFileName).use { inputStream ->
                    FileOutputStream(dbUpdateFile).use { outputStream -> inputStream.copyTo(outputStream) }
                }
            }
            exploreConfig.set(ExploreConfig.PRELOADED_TEST_DB, checkTestDB)
            return true
        } catch (ex: FileNotFoundException) {
            return if (checkTestDB) {
                preloadFromAssetsInto(dbUpdateFile, false)
            } else {
                log.error("missing preloaded database {}", preloadedDbFileName)
                false
            }
        }
    }

    override fun finalizeUpdate() {
        log.info("finalizing update: $updateTimestampCache")
        configScope.launch {
            val prefs = exploreConfig.exploreDatabasePrefs.first()
            exploreConfig.saveExploreDatabasePrefs(
                prefs.copy(localDbTimestamp = max(prefs.localDbTimestamp, updateTimestampCache))
            )
        }
    }
}
