/*
 * Copyright 2020 Dash Core Group
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package de.schildbach.wallet.ui

import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.database.Cursor
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.RectF
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import androidx.lifecycle.Observer
import androidx.lifecycle.ViewModelProvider
import com.amulyakhare.textdrawable.TextDrawable
import com.bumptech.glide.Glide
import com.bumptech.glide.signature.ObjectKey
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.android.gms.common.api.ApiException
import com.google.api.services.drive.Drive
import de.schildbach.wallet.Constants
import de.schildbach.wallet.data.DashPayProfile
import de.schildbach.wallet.livedata.Status
import de.schildbach.wallet.ui.dashpay.*
import de.schildbach.wallet.ui.dashpay.utils.GoogleDriveService
import de.schildbach.wallet.ui.dashpay.utils.ProfilePictureDisplay
import de.schildbach.wallet.ui.dashpay.work.UpdateProfileError
import de.schildbach.wallet_test.R
import kotlinx.android.synthetic.main.activity_edit_profile.*
import org.slf4j.LoggerFactory
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream

class EditProfileActivity : BaseMenuActivity() {

    companion object {
        const val REQUEST_CODE_URI = 0
        const val REQUEST_CODE_IMAGE = 1
        const val REQUEST_CODE_CHOOSE_PICTURE_PERMISSION = 2
        const val REQUEST_CODE_TAKE_PICTURE_PERMISSION = 3
        const val REQUEST_CODE_CROP_IMAGE = 4
        const val REQUEST_CODE_GOOGLE_DRIVE_SIGN_IN = 5

        protected val log = LoggerFactory.getLogger(EditProfileActivity::class.java)

    }

    private lateinit var editProfileViewModel: EditProfileViewModel
    private lateinit var selectProfilePictureSharedViewModel: SelectProfilePictureSharedViewModel
    private lateinit var externalUrlSharedViewModel: ExternalUrlProfilePictureViewModel

    private var isEditing: Boolean = false
    private var defaultAvatar: TextDrawable? = null

    private var profilePictureChanged = false
    private var uploadProfilePictureStateDialog: UploadProfilePictureStateDialog? = null

    private var mDrive: Drive? = null

    override fun getLayoutId(): Int {
        return R.layout.activity_edit_profile
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_HIDDEN)

        setTitle(R.string.edit_profile)

        initViewModel()

        display_name.onFocusChangeListener = View.OnFocusChangeListener { _, hasFocus ->
            display_name_char_count.visibility = if (hasFocus) View.VISIBLE else View.INVISIBLE
        }
        val redTextColor = ContextCompat.getColor(this, R.color.dash_red)
        val mediumGrayTextColor = ContextCompat.getColor(this, R.color.medium_gray)
        display_name.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                setEditingState(true)
                val charCount = s?.trim()?.length ?: 0
                display_name_char_count.text = getString(R.string.char_count, charCount,
                        Constants.DISPLAY_NAME_MAX_LENGTH)
                if (charCount > Constants.DISPLAY_NAME_MAX_LENGTH) {
                    display_name_char_count.setTextColor(redTextColor)
                } else {
                    display_name_char_count.setTextColor(mediumGrayTextColor)
                }
                activateDeactivateSave()
            }

            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {

            }

            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {

            }
        })

        about_me.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                setEditingState(true)
                aboutMeCharCount.visibility = View.VISIBLE
                val charCount = s?.trim()?.length ?: 0
                aboutMeCharCount.text = getString(R.string.char_count, charCount,
                        Constants.ABOUT_ME_MAX_LENGTH)
                if (charCount > Constants.ABOUT_ME_MAX_LENGTH) {
                    aboutMeCharCount.setTextColor(redTextColor)
                } else {
                    aboutMeCharCount.setTextColor(mediumGrayTextColor)
                }
                activateDeactivateSave()
            }

            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {

            }

            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {

            }

        })
        save.setOnClickListener {
            save()
        }

        profile_edit_icon.setOnClickListener {
            selectImage(this)
        }

        selectProfilePictureSharedViewModel.onFromGravatarCallback.observe(this, Observer<Void> {
            externalUrlSharedViewModel.shouldCrop = false
            pictureFromGravatar()
        })

        selectProfilePictureSharedViewModel.onFromUrlCallback.observe(this, Observer<Void> {
            externalUrlSharedViewModel.shouldCrop = true
            pictureFromUrl()
        })

        selectProfilePictureSharedViewModel.onTakePictureCallback.observe(this, Observer<Void> {
            takePictureWithPermission()
        })

        selectProfilePictureSharedViewModel.onChoosePictureCallback.observe(this, Observer<Void> {
            choosePictureWithPermission()
        })

        //selectProfilePictureSharedViewModel.onChooseStorageService.observe(this, Observer<EditProfileViewModel.ProfilePictureStorageService> {
        //    uploadImage(it)
        //})

        editProfileViewModel.onTmpPictureReadyForEditEvent.observe(this, Observer {
            cropProfilePicture()
        })
    }

    private fun pictureFromGravatar() {
        if (editProfileViewModel.createTmpPictureFile()) {
            GravatarProfilePictureDialog.newInstance().show(supportFragmentManager, "")
        } else {
            Toast.makeText(this, "Unable to create temporary file", Toast.LENGTH_LONG).show()
        }
    }

    private fun pictureFromUrl() {
        if (editProfileViewModel.createTmpPictureFile()) {
            val initialUrl = externalUrlSharedViewModel.externalUrl?.toString()
                    ?: editProfileViewModel.dashPayProfile?.avatarUrl
            ExternalUrlProfilePictureDialog.newInstance(initialUrl).show(supportFragmentManager, "")
        } else {
            Toast.makeText(this, "Unable to create temporary file", Toast.LENGTH_LONG).show()
        }
    }

    private fun initViewModel() {
        editProfileViewModel = ViewModelProvider(this).get(EditProfileViewModel::class.java)
        selectProfilePictureSharedViewModel = ViewModelProvider(this).get(SelectProfilePictureSharedViewModel::class.java)
        externalUrlSharedViewModel = ViewModelProvider(this).get(ExternalUrlProfilePictureViewModel::class.java)

        // first ensure that we have a registered username
        editProfileViewModel.dashPayProfileData.observe(this, Observer { dashPayProfile ->
            if (dashPayProfile != null) {
                showProfileInfo(dashPayProfile)
            } else {
                finish()
            }
        })
        editProfileViewModel.updateProfileRequestState.observe(this, Observer {
            when (it.status) {
                Status.LOADING -> {
                    if (it.progress == 100) {
                        Toast.makeText(this@EditProfileActivity, "Update successful", Toast.LENGTH_LONG).show()
                    }
                }
                Status.ERROR -> {
                    var msg = it.message
                    if (msg == null) {
                        msg = "!!Error!!  ${it.exception!!.message}"
                    }
                    Toast.makeText(this@EditProfileActivity, msg, Toast.LENGTH_LONG).show()
                    setEditingState(true)
                }
                else -> {
                    // ignore
                }
            }
            setEditingState(it.status != Status.SUCCESS)
            activateDeactivateSave()
        })
        externalUrlSharedViewModel.validUrlChosenEvent.observe(this, Observer {
            if (it != null) {
                editProfileViewModel.saveExternalBitmap(it)
            } else {
                val username = editProfileViewModel.dashPayProfile!!.username
                ProfilePictureDisplay.displayDefault(dashpayUserAvatar, username)
            }
            profilePictureChanged = true
        })

        editProfileViewModel.profilePictureUploadLiveData.observe(this, Observer {
            when (it.status) {
                Status.LOADING -> {
                    setEditingState(true)
                    showUploadingDialog()
                }
                Status.SUCCESS -> {
                    showUploadedProfilePicture(it.data)
                }
                Status.ERROR -> {
                    showUploadErrorDialog(UpdateProfileError.UPLOAD)
                }
            }
        })
        editProfileViewModel.uploadDialogAcceptLiveData.observe(this, Observer { accepted ->
            if (accepted) {
                walletApplication.configuration.setAcceptedUploadPolicy(editProfileViewModel.storageService.name, true)
                startUploadProcess()
            }
        })
        editProfileViewModel.deleteProfilePictureConfirmationLiveData.observe(this, Observer { accepted ->
            if (accepted) {
                showProfilePictureServiceDialog(false)
            }
        })
    }

    private fun startUploadProcess() {
        when (editProfileViewModel.storageService) {
            EditProfileViewModel.ProfilePictureStorageService.IMGUR -> editProfileViewModel.uploadProfilePicture()
            EditProfileViewModel.ProfilePictureStorageService.GOOGLE_DRIVE -> requestGDriveAccess()
        }
    }

    private fun showUploadingDialog() {
        if (uploadProfilePictureStateDialog != null) {
            uploadProfilePictureStateDialog!!.dialog?.dismiss()
        }
        uploadProfilePictureStateDialog = UploadProfilePictureStateDialog.newInstance()
        uploadProfilePictureStateDialog!!.show(supportFragmentManager, null)
    }

    private fun showUploadedProfilePicture(url: String?) {
        profilePictureChanged = true
        if (uploadProfilePictureStateDialog != null && uploadProfilePictureStateDialog!!.dialog!!.isShowing) {
            uploadProfilePictureStateDialog!!.dismiss()
        }
        Glide.with(this).load(url).circleCrop().into(dashpayUserAvatar)
    }

    private fun showUploadErrorDialog(error: UpdateProfileError) {
        if (uploadProfilePictureStateDialog != null && uploadProfilePictureStateDialog!!.dialog!!.isShowing) {
            uploadProfilePictureStateDialog!!.showError(error)
            return
        } else if (uploadProfilePictureStateDialog != null) {
            uploadProfilePictureStateDialog!!.dialog?.dismiss()
        }
        uploadProfilePictureStateDialog = UploadProfilePictureStateDialog.newInstance(error)
        uploadProfilePictureStateDialog!!.show(supportFragmentManager, null)
    }

    fun activateDeactivateSave() {
        save.isEnabled = !(display_name.text.length > Constants.DISPLAY_NAME_MAX_LENGTH || about_me.text.length > Constants.ABOUT_ME_MAX_LENGTH)
    }

    fun save() {
        val displayName = display_name.text.toString().trim()
        val publicMessage = about_me.text.toString().trim()
        //TODO: profilePictureChanged?
        val avatarUrl = if (profilePictureChanged) {
            if (externalUrlSharedViewModel.externalUrl != null) {
                externalUrlSharedViewModel.externalUrl.toString()
            } else {
                editProfileViewModel.profilePictureUploadLiveData.value!!.data
            }
        } else {
            editProfileViewModel.dashPayProfile!!.avatarUrl
        }

        editProfileViewModel.broadcastUpdateProfile(displayName, publicMessage, avatarUrl ?: "")
        save.isEnabled = false
        finish()

    }

    private fun showProfileInfo(profile: DashPayProfile) {
        ProfilePictureDisplay.display(dashpayUserAvatar, profile)
        about_me.setText(profile.publicMessage)
        display_name.setText(profile.displayName)
    }

    private fun setEditingState(isEditing: Boolean) {
        this.isEditing = isEditing
    }

    private fun takePictureWithPermission() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            takePicture()
        } else {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), REQUEST_CODE_TAKE_PICTURE_PERMISSION)
        }
    }

    private fun choosePictureWithPermission() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED) {
            choosePicture()
        } else {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE), REQUEST_CODE_CHOOSE_PICTURE_PERMISSION)
        }
    }

    private fun selectImage(context: Context) {
        SelectProfilePictureDialog.createDialog()
                .show(supportFragmentManager, "selectPictureDialog")
    }

    private fun choosePicture() {
        if (editProfileViewModel.createTmpPictureFile()) {
            val pickPhoto = Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI)
            startActivityForResult(pickPhoto, REQUEST_CODE_URI)
        } else {
            Toast.makeText(this, "Unable to create temporary file", Toast.LENGTH_LONG).show()
        }
    }

    private fun takePicture() {
        if (editProfileViewModel.createTmpPictureFile()) {
            dispatchTakePictureIntent()
        } else {
            Toast.makeText(this, "Unable to create temporary file", Toast.LENGTH_LONG).show()
        }
    }

    private fun dispatchTakePictureIntent() {
        Intent(MediaStore.ACTION_IMAGE_CAPTURE).also { takePictureIntent ->
            // Ensure that there's a camera activity to handle the intent
            takePictureIntent.resolveActivity(packageManager)?.also {
                val tmpFileUri = getFileUri(editProfileViewModel.tmpPictureFile)
                takePictureIntent.putExtra(MediaStore.EXTRA_OUTPUT, tmpFileUri)
                if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.LOLLIPOP) {
                    // to avoid 'SecurityException: Permission Denial' on KitKat
                    takePictureIntent.clipData = ClipData.newRawUri("", tmpFileUri)
                    takePictureIntent.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                startActivityForResult(takePictureIntent, REQUEST_CODE_IMAGE)
            }
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
            when (requestCode) {
                REQUEST_CODE_IMAGE -> {
                    if (resultCode == RESULT_OK) {
                        // picture saved in editProfileViewModel.profilePictureTmpFile
                        editProfileViewModel.onTmpPictureReadyForEditEvent.call(editProfileViewModel.tmpPictureFile)
                    }
                }
                REQUEST_CODE_URI -> if (resultCode == RESULT_OK && data != null) {
                    val selectedImage: Uri? = data.data
                    if (selectedImage != null) {
                        val filePathColumn = arrayOf(MediaStore.Images.Media.DATA)
                        val cursor: Cursor? = contentResolver.query(selectedImage,
                                filePathColumn, null, null, null)
                        if (cursor != null) {
                            cursor.moveToFirst()
                            val columnIndex: Int = cursor.getColumnIndex(filePathColumn[0])
                            val picturePath: String? = cursor.getString(columnIndex)
                            if (picturePath != null) {
                                editProfileViewModel.saveAsProfilePictureTmp(picturePath)
                            } else {
                                saveImageWithAuthority(selectedImage)
                            }
                            cursor.close()
                        }
                    }
                }
                REQUEST_CODE_CROP_IMAGE -> {
                    if (resultCode == Activity.RESULT_OK) {
                        if (externalUrlSharedViewModel.externalUrl != null) {
                            saveUrl(CropImageActivity.extractZoomedRect(data!!))
                        } else {
                            showProfilePictureServiceDialog()
                        }
                    } else if (resultCode == Activity.RESULT_CANCELED) {
                        // if crop was canceled, then return the externalUrl to its original state
                        if (externalUrlSharedViewModel.externalUrl != null)
                            externalUrlSharedViewModel.externalUrl = if (editProfileViewModel.dashPayProfile!!.avatarUrl == "") {
                                null
                            } else {
                                Uri.parse(editProfileViewModel.dashPayProfile!!.avatarUrl)
                            }
                    }
                }
                REQUEST_CODE_GOOGLE_DRIVE_SIGN_IN -> {
                    handleGdriveSigninResult(data!!)
                }
            }
    }

    private fun saveImageWithAuthority(uri: Uri) {
        var inputStream: InputStream? = null
        if (uri.authority != null) {
            try {
                inputStream = contentResolver.openInputStream(uri)
                val bmp: Bitmap = BitmapFactory.decodeStream(inputStream)
                editProfileViewModel.saveExternalBitmap(bmp)
            } catch (e: FileNotFoundException) {
                e.printStackTrace()
                Toast.makeText(this, e.message, Toast.LENGTH_LONG).show()
            } finally {
                try {
                    inputStream?.close()
                } catch (e: IOException) {
                    // ignore
                }
            }
        }
    }

    private fun showProfilePictureServiceDialog(showDeleteDialog: Boolean = true) {
        if (walletApplication.configuration.imgurDeleteHash.isNotEmpty() && showDeleteDialog) {
            DeleteProfilePictureConfirmationDialog().show(supportFragmentManager, null)
            return
        }
        selectProfilePictureSharedViewModel.onChooseStorageService.observe(this, Observer {
            editProfileViewModel.storageService = it
            if (walletApplication.configuration.getAcceptedUploadPolicy(editProfileViewModel.storageService.name)) {
                startUploadProcess()
            } else {
                UploadPolicyDialog().show(supportFragmentManager, null)
            }
            /*when (it) {
                EditProfileViewModel.ProfilePictureStorageService.IMGUR -> {
                    UploadPolicyDialog().show(supportFragmentManager, null)
                }
                EditProfileViewModel.ProfilePictureStorageService.GOOGLE_DRIVE -> {
                    requestGDriveAccess()
                }
            }*/
        })
        ChooseStorageServiceDialog.newInstance().show(supportFragmentManager, null)
    }

    private fun saveUrl(zoomedRect: RectF) {
        if (externalUrlSharedViewModel.externalUrl != null) {
            val zoomedRectStr = "${zoomedRect.left},${zoomedRect.top},${zoomedRect.right},${zoomedRect.bottom}"
            if (externalUrlSharedViewModel.shouldCrop) {
                externalUrlSharedViewModel.externalUrl = setUriParameter(externalUrlSharedViewModel.externalUrl!!, "dashpay-profile-pic-zoom", zoomedRectStr)
            }

            val file = editProfileViewModel.tmpPictureFile
            val imgUri = getFileUri(file)
            Glide.with(dashpayUserAvatar).load(imgUri)
                    .signature(ObjectKey(file.lastModified()))
                    .placeholder(defaultAvatar)
                    .transform(ProfilePictureTransformation.create(zoomedRect))
                    .into(dashpayUserAvatar)
        }
    }

    private fun setUriParameter(uri: Uri, key: String, newValue: String): Uri {
        val newUriBuilder = uri.buildUpon()
        if (uri.getQueryParameter(key) == null) {
            newUriBuilder.appendQueryParameter(key, newValue)
        } else {
            newUriBuilder.clearQuery()
            for (param in uri.queryParameterNames) {
                newUriBuilder.appendQueryParameter(param,
                        if (param == key) newValue else uri.getQueryParameter(param))
            }
        }
        return newUriBuilder.build()
    }

    private fun cropProfilePicture() {
        if (externalUrlSharedViewModel.shouldCrop) {
            val tmpPictureUri = editProfileViewModel.tmpPictureFile.toUri()
            val profilePictureUri = editProfileViewModel.profilePictureFile!!.toUri()
            val initZoomedRect = ProfilePictureTransformation.extractZoomedRect(externalUrlSharedViewModel.externalUrl)
            val intent = CropImageActivity.createIntent(this, tmpPictureUri, profilePictureUri, initZoomedRect)
            startActivityForResult(intent, REQUEST_CODE_CROP_IMAGE)
        } else {
            saveUrl(RectF(0.0f,0.0f,1.0f,1.0f))
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        when (requestCode) {
            REQUEST_CODE_TAKE_PICTURE_PERMISSION -> {
                when {
                    grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED -> takePicture()
                    else -> takePictureWithPermission()
                }
            }
            REQUEST_CODE_CHOOSE_PICTURE_PERMISSION -> {
                when {
                    grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED -> choosePicture()
                    else -> choosePictureWithPermission()
                }
            }
            else -> super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        }
    }

    private fun getFileUri(file: File): Uri {
        return FileProvider.getUriForFile(walletApplication, "${walletApplication.packageName}.file_attachment", file)
    }

    //TODO: Not sure if we need this
    private fun checkGDriveAccess() {
        object : Thread() {
            override fun run() {
                val signInAccount: GoogleSignInAccount? = GoogleDriveService.getSigninAccount(applicationContext)
                if (signInAccount != null) {
                    runOnUiThread { applyGdriveAccessGranted(signInAccount) }
                } else {
                    runOnUiThread { applyGdriveAccessDenied() }
                }
            }
        }.start()
    }

    private fun requestGDriveAccess() {
        val signInAccount = GoogleDriveService.getSigninAccount(applicationContext)
        val googleSignInClient = GoogleSignIn.getClient(this, GoogleDriveService.getGoogleSigninOptions())
        if (signInAccount == null) {
            startActivityForResult(googleSignInClient.signInIntent, REQUEST_CODE_GOOGLE_DRIVE_SIGN_IN)
        } else {
            googleSignInClient.revokeAccess()
                    .addOnSuccessListener { aVoid: Void? -> startActivityForResult(googleSignInClient.signInIntent, REQUEST_CODE_GOOGLE_DRIVE_SIGN_IN) }
                    .addOnFailureListener { e: java.lang.Exception? ->
                        log.error("could not revoke access to drive: ", e)
                        applyGdriveAccessDenied()
                    }
        }
    }

    private fun applyGdriveAccessDenied() {
        //TODO: This is not part of the designs and thus far hasn't been tested
        //what we will do here is ask the user to use Imgur instead
        showUploadErrorDialog(UpdateProfileError.AUTHENTICATION)
        /*val builder = AlertDialog.Builder(this)
                .setTitle(R.string.edit_profile_google_drive)
                .setMessage(R.string.edit_profile_google_drive_failed_authorization)
                .setPositiveButton(R.string.edit_profile_imgur) { dialog, which -> uploadImage(EditProfileViewModel.ProfilePictureStorageService.IMGUR) }
                .setNegativeButton(R.string.button_cancel) { dialog, which -> }
        builder.create().show();*/
    }

    private fun handleGdriveSigninResult(data: Intent) {
        try {
            log.info("gdrive: attempting to sign in to a google account")
            val account = GoogleSignIn.getSignedInAccountFromIntent(data).getResult(ApiException::class.java)
                    ?: throw RuntimeException("empty account")
            applyGdriveAccessGranted(account)
        } catch (e: Exception) {
            log.error("Google Drive sign-in failed, could not get account: ", e)
            Toast.makeText(this, "Sign-in failed.", Toast.LENGTH_SHORT).show()
            applyGdriveAccessDenied()
        }
    }

    private fun applyGdriveAccessGranted(signInAccount: GoogleSignInAccount) {
        log.info("gdrive: access granted to ${signInAccount.email} with: ${signInAccount.grantedScopes}")
        mDrive = GoogleDriveService.getDriveServiceFromAccount(applicationContext, signInAccount!!)
        log.info("gdrive: drive $mDrive")
        editProfileViewModel.googleDrive = mDrive
        editProfileViewModel.uploadProfilePicture()
        //PictureUploadProgressDialog.newInstance(mDrive).show(supportFragmentManager, "uploadImage")
    }

    /*private fun uploadImage(uploadService: EditProfileViewModel.ProfilePictureStorageService) {
        editProfileViewModel.storageService = uploadService
        when (uploadService) {
            EditProfileViewModel.ProfilePictureStorageService.GOOGLE_DRIVE -> {
                requestGDriveAccess()
            }
            EditProfileViewModel.ProfilePictureStorageService.IMGUR -> {
                PictureUploadProgressDialog.newInstance().show(supportFragmentManager, "uploadImage")
            }
            else -> throw IllegalStateException()
        }
    }*/
}
