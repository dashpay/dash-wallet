package de.schildbach.wallet.ui.dashpay.utils

import android.content.Context
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.common.api.Scope
import com.google.api.client.http.ByteArrayContent
import com.google.api.client.http.HttpRequestInitializer
import com.google.api.client.http.javanet.NetHttpTransport
import com.google.api.client.json.gson.GsonFactory
import com.google.api.services.drive.Drive
import com.google.api.services.drive.DriveScopes
import com.google.api.services.drive.model.File
import com.google.api.services.drive.model.Permission
import dagger.hilt.android.qualifiers.ApplicationContext
import de.schildbach.wallet_test.BuildConfig
import de.schildbach.wallet_test.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.io.IOException

class GoogleDriveService(
    @ApplicationContext private val context: Context
) {
    companion object {
        private val log = LoggerFactory.getLogger(GoogleDriveService::class.java)
        private const val BACKUP_FOLDER_NAME = "dashpay-profile-picture-${BuildConfig.FLAVOR}"
    }

    suspend fun uploadImage(credential: HttpRequestInitializer, fileName: String?, imageBytes: ByteArray?, secureId: String): String {
        return withContext(Dispatchers.IO) {
            log.info("creating new backup file on gdrive with name={}", fileName)
            val drive = getDrive(credential)

            // 1 - create folder
            val folder = getOrCreateImageFolder(drive, secureId)

            // 2 - metadata
            val metadata = File()
                .setParents(listOf(folder.id))
                .setMimeType("image/jpeg")
                .setName(fileName)

            // 3 - content
            val content = ByteArrayContent("image/jpeg", imageBytes)

            // 4 - execute
            val file = drive.files()
                .create(metadata, content)
                .setFields("id,parents,appProperties")
                .execute()
                ?: throw IOException("failed to create file on gdrive with null result")
            val id = file.id

            // 5 - set permission to public
            val permission = Permission()
            permission.setType("anyone").role = "reader"

            // 6 - permissions execute
            drive.permissions().create(id, permission).execute()
                ?: throw IOException("failed to set permissions on gdrive with null result")
            return@withContext id
        }
    }

    private fun getDrive(credential: HttpRequestInitializer): Drive {
        return Drive.Builder(NetHttpTransport(), GsonFactory(), credential)
            .setApplicationName(context.getString(R.string.app_name))
            .build()
    }

    @Throws(IOException::class)
    private fun getOrCreateImageFolder(drive: Drive, secureId: String): File {
        log.info("gdrive: getOrCreateImageFolder($secureId)")
        // retrieve folder if it exists
        val folders = drive.files().list()
            .setQ("name='$BACKUP_FOLDER_NAME' and mimeType='application/vnd.google-apps.folder'")
            .setSpaces("drive")
            .setFields("files(id)").execute()
        log.info("gdrive: getOrCreateImageFolder folders results(${folders.files.size}):${folders.toPrettyString()}")
        return if (folders.isEmpty() || folders.files.isEmpty()) {
            val folderMeta = File()
            folderMeta.setParents(listOf("root"))
                .setMimeType("application/vnd.google-apps.folder").name = BACKUP_FOLDER_NAME
            drive.files().create(folderMeta).setFields("id,parents,mimeType").execute()
        } else {
            log.info("gdrive: getOrCreateImageFolder folders result:" + folders.files[0])
            if(folders.files.size == 1) {
                folders.files[0]
            } else {
                folders.files.find {
                    it["mimeType"] == "application/vnd.google-apps.folder" && it["name"] == BACKUP_FOLDER_NAME
                } ?: throw IllegalStateException("gdrive: cannot find the image folder")
            }
        }
    }

    fun getAuthRequest() = AuthorizationRequest
        .builder()
        .setRequestedScopes(
            listOf(Scope(DriveScopes.DRIVE_FILE))
        )
        .setOptOutIncludingGrantedScopes(false)
        .build()
}