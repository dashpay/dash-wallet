package de.schildbach.wallet.ui.dashpay.utils

import android.content.Context
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.common.api.Scope
import com.google.android.gms.tasks.Task
import com.google.android.gms.tasks.Tasks
import com.google.api.client.googleapis.extensions.android.gms.auth.GoogleAccountCredential
import com.google.api.client.http.ByteArrayContent
import com.google.api.client.http.javanet.NetHttpTransport
import com.google.api.client.json.gson.GsonFactory
import com.google.api.services.drive.Drive
import com.google.api.services.drive.DriveScopes
import com.google.api.services.drive.model.File
import com.google.api.services.drive.model.Permission
import de.schildbach.wallet_test.BuildConfig
import de.schildbach.wallet_test.R
import org.slf4j.LoggerFactory
import java.io.IOException
import java.util.*
import java.util.concurrent.Executor
import java.util.concurrent.Executors

object GoogleDriveService {

    var log = LoggerFactory.getLogger(GoogleDriveService::class.java)
    var BACKUP_FOLDER_NAME = "dashpay-profile-picture-${BuildConfig.FLAVOR}"

    fun getDriveServiceFromAccount(context: Context, signInAccount: GoogleSignInAccount): Drive? {
        val credential = GoogleAccountCredential
                .usingOAuth2(context, getGdriveScope())
                .setSelectedAccount(signInAccount.account)
        return Drive.Builder(NetHttpTransport(), GsonFactory(), credential)
                .setApplicationName(context.getString(R.string.app_name))
                .build()
    }

    @Throws(IOException::class)
    fun getOrCreateImageFolder(drive: Drive, secureId: String): File {
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
                    .setMimeType("application/vnd.google-apps.folder").name = BACKUP_FOLDER_NAME //+ "-" + secureId
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

    fun uploadImage(drive: Drive, fileName: String?, imageBytes: ByteArray?, secureId: String): String {
        log.info("creating new backup file on gdrive with name={}", fileName)

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
        return id
    }

    fun isGDriveAvailable(context: Context): Boolean {
        val connectionResult = GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(context)
        return if (connectionResult != ConnectionResult.SUCCESS) {
            log.info("gdrive: Google play services are not available (code {})", connectionResult)
            false
        } else {
            true
        }
    }

    fun getGdriveScope(): Set<String>? {
        val requiredScopes: MutableSet<String> = HashSet(2)
        requiredScopes.add(DriveScopes.DRIVE_FILE)
        return requiredScopes
    }

    fun getSigninAccount(context: Context?): GoogleSignInAccount? {
        val opts = getGoogleSigninOptions()
        val account = GoogleSignIn.getLastSignedInAccount(context)
        val permissions = arrayOf(Scope(DriveScopes.DRIVE_FILE))
        return if (GoogleSignIn.hasPermissions(account, *permissions)) {
            account
        } else {
            log.info("gdrive sign-in account={} does not have correct permissions, revoking access", account)
            try {
                GoogleSignIn.getClient(context!!, opts)
                        .revokeAccess()
                        .addOnSuccessListener { log.warn("revoked gdrive access",) }
                        .addOnFailureListener { log.warn("could not revoke gdrive access: ${it.localizedMessage}") }
            } catch (e: Exception) {
                log.warn("could not revoke gdrive access: {}", e.localizedMessage)
            }
            null
        }
    }

    fun getGoogleSigninOptions(): GoogleSignInOptions {
        return GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
                .requestEmail()
                .requestScopes(Scope(DriveScopes.DRIVE_FILE))
                .build()
    }
}