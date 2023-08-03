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
import androidx.activity.viewModels
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import androidx.core.view.isVisible
import com.amulyakhare.textdrawable.TextDrawable
import com.bumptech.glide.Glide
import com.bumptech.glide.signature.ObjectKey
import com.google.android.gms.auth.GoogleAuthException
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.android.gms.common.api.ApiException
import com.google.api.client.googleapis.extensions.android.gms.auth.GoogleAuthIOException
import com.google.api.services.drive.Drive
import dagger.hilt.android.AndroidEntryPoint
import de.schildbach.wallet.Constants
import de.schildbach.wallet.database.entity.DashPayProfile
import de.schildbach.wallet.livedata.Status
import de.schildbach.wallet.ui.dashpay.*
import de.schildbach.wallet.ui.dashpay.utils.GoogleDriveService
import de.schildbach.wallet.ui.dashpay.utils.display
import org.dash.wallet.common.ui.avatar.ProfilePictureHelper
import de.schildbach.wallet.ui.dashpay.work.UpdateProfileError
import de.schildbach.wallet_test.R
import de.schildbach.wallet_test.databinding.ActivityEditProfileBinding
import org.bitcoinj.core.Sha256Hash
import org.dash.wallet.common.ui.avatar.ProfilePictureDisplay
import org.dash.wallet.common.ui.avatar.ProfilePictureTransformation
import org.dash.wallet.common.ui.dialogs.AdaptiveDialog
import org.slf4j.LoggerFactory
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
import java.math.BigInteger

@AndroidEntryPoint
class EditProfileActivity : LockScreenActivity() {

    companion object {
        const val REQUEST_CODE_URI = 0
        const val REQUEST_CODE_IMAGE = 1
        const val REQUEST_CODE_CHOOSE_PICTURE_PERMISSION = 2
        const val REQUEST_CODE_TAKE_PICTURE_PERMISSION = 3
        const val REQUEST_CODE_CROP_IMAGE = 4
        const val REQUEST_CODE_GOOGLE_DRIVE_SIGN_IN = 5

        protected val log = LoggerFactory.getLogger(EditProfileActivity::class.java)

    }

    private lateinit var binding: ActivityEditProfileBinding
    private val editProfileViewModel: EditProfileViewModel by viewModels()
    private val selectProfilePictureSharedViewModel: SelectProfilePictureSharedViewModel by viewModels()
    private val externalUrlSharedViewModel: ExternalUrlProfilePictureViewModel by viewModels()

    private var isEditing: Boolean = false
    private var defaultAvatar: TextDrawable? = null

    private var profilePictureChanged = false
    private var uploadProfilePictureStateDialog: UploadProfilePictureStateDialog? = null

    private var mDrive: Drive? = null
    private var showSaveReminderDialog = false
    private var initialDisplayName = ""
    private var initialAboutMe = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityEditProfileBinding.inflate(layoutInflater)
        setContentView(binding.root)
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_HIDDEN)
        binding.appbarGeneral.toolbar.setTitle(R.string.edit_profile)
        binding.appbarGeneral.toolbar.setNavigationOnClickListener { finish() }

        initViewModel()

        binding.displayName.onFocusChangeListener = View.OnFocusChangeListener { _, hasFocus ->
            binding.displayNameCharCount.isVisible = hasFocus
        }
        val redTextColor = ContextCompat.getColor(this, R.color.dash_red)
        val mediumGrayTextColor = ContextCompat.getColor(this, R.color.medium_gray)
        binding.displayName.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                showSaveReminderDialog = initialDisplayName != s?.toString()
                setEditingState(true)
                imitateUserInteraction()
                val charCount = s?.trim()?.length ?: 0
                binding.displayNameCharCount.text = getString(R.string.char_count, charCount,
                    Constants.DISPLAY_NAME_MAX_LENGTH)
                if (charCount > Constants.DISPLAY_NAME_MAX_LENGTH) {
                    binding.displayNameCharCount.setTextColor(redTextColor)
                } else {
                    binding.displayNameCharCount.setTextColor(mediumGrayTextColor)
                }
                updateSaveBtnState()
            }

            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {

            }

            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {

            }
        })

        binding.aboutMe.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                showSaveReminderDialog = initialAboutMe != s?.toString()
                setEditingState(true)
                imitateUserInteraction()
                binding.aboutMeCharCount.visibility = View.VISIBLE
                val charCount = s?.trim()?.length ?: 0
                binding.aboutMeCharCount.text = getString(R.string.char_count, charCount,
                    Constants.ABOUT_ME_MAX_LENGTH)
                if (charCount > Constants.ABOUT_ME_MAX_LENGTH) {
                    binding.aboutMeCharCount.setTextColor(redTextColor)
                } else {
                    binding.aboutMeCharCount.setTextColor(mediumGrayTextColor)
                }
                updateSaveBtnState()
            }

            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {

            }

            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {

            }

        })
        binding.save.setOnClickListener {
            save()
        }

        binding.profileEditIcon.setOnClickListener {
            selectImage()
        }

        selectProfilePictureSharedViewModel.onFromGravatarCallback.observe(this) {
            imitateUserInteraction()
            externalUrlSharedViewModel.shouldCrop = false
            editProfileViewModel.pictureSource = "gravatar"
            pictureFromGravatar()
        }

        selectProfilePictureSharedViewModel.onFromUrlCallback.observe(this) {
            imitateUserInteraction()
            externalUrlSharedViewModel.shouldCrop = true
            editProfileViewModel.pictureSource = "public_url"
            pictureFromUrl()
        }

        selectProfilePictureSharedViewModel.onTakePictureCallback.observe(this) {
            imitateUserInteraction()
            editProfileViewModel.pictureSource = "camera"
            takePictureWithPermission()
        }

        selectProfilePictureSharedViewModel.onChoosePictureCallback.observe(this) {
            imitateUserInteraction()
            editProfileViewModel.pictureSource = "gallery"
            choosePictureWithPermission()
        }

        editProfileViewModel.onTmpPictureReadyForEditEvent.observe(this) {
            imitateUserInteraction()
            cropProfilePicture()
        }
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
                ?: editProfileViewModel.dashPayProfile.value?.avatarUrl
            ExternalUrlProfilePictureDialog.newInstance(initialUrl).show(supportFragmentManager, "")
        } else {
            Toast.makeText(this, "Unable to create temporary file", Toast.LENGTH_LONG).show()
        }
    }

    private fun initViewModel() {
        // first ensure that we have a registered username
        editProfileViewModel.dashPayProfile.observe(this) { dashPayProfile ->
            if (dashPayProfile != null) {
                showProfileInfo(dashPayProfile)
            } else {
                finish()
            }
        }

        externalUrlSharedViewModel.validUrlChosenEvent.observe(this) {
            if (it != null) {
                editProfileViewModel.avatarHash = externalUrlSharedViewModel.avatarHash
                editProfileViewModel.avatarFingerprint = externalUrlSharedViewModel.avatarFingerprint
                editProfileViewModel.saveExternalBitmap(it)
                setEditingState(true)
            } else {
                val username = editProfileViewModel.dashPayProfile.value!!.username
                ProfilePictureDisplay.displayDefault(binding.dashpayUserAvatar, username)
            }
            profilePictureChanged = true
        }

        editProfileViewModel.profilePictureUploadLiveData.observe(this) {
            imitateUserInteraction()
            when (it.status) {
                Status.LOADING -> {
                    setEditingState(true)
                    showUploadingDialog()
                }
                Status.SUCCESS -> {
                    ProfilePictureHelper.avatarHashAndFingerprint(this, it.data!!.toUri(), null,
                        object: ProfilePictureHelper.OnResourceReadyListener {
                            override fun onResourceReady(avatarHash: Sha256Hash?, avatarFingerprint: BigInteger?) {
                                editProfileViewModel.avatarHash = avatarHash
                                editProfileViewModel.avatarFingerprint = avatarFingerprint
                            }
                        }
                    )
                    showUploadedProfilePicture(it.data)
                }
                Status.ERROR -> {
                    val error = if (it.exception is GoogleAuthException || it.exception is GoogleAuthIOException) {
                        UpdateProfileError.AUTHENTICATION
                    } else {
                        UpdateProfileError.UPLOAD
                    }
                    showUploadErrorDialog(error)
                }
                else -> {
                    // ignore
                }
            }
        }
        editProfileViewModel.uploadDialogAcceptLiveData.observe(this) { accepted ->
            imitateUserInteraction()
            if (accepted) {
                walletApplication.configuration.setAcceptedUploadPolicy(editProfileViewModel.storageService.name, true)
                startUploadProcess()
            }
        }
        editProfileViewModel.deleteProfilePictureConfirmationLiveData.observe(this) { accepted ->
            imitateUserInteraction()
            if (accepted) {
                showProfilePictureServiceDialog(false)
            }
        }
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
        Glide.with(this).load(url).circleCrop().into(binding.dashpayUserAvatar)
        showSaveReminderDialog = true
    }

    private fun showUploadErrorDialog(error: UpdateProfileError) {
        if (uploadProfilePictureStateDialog != null && uploadProfilePictureStateDialog!!.dialog != null &&
            uploadProfilePictureStateDialog!!.dialog!!.isShowing) {
            uploadProfilePictureStateDialog!!.showError(error)
            return
        } else if (uploadProfilePictureStateDialog != null) {
            uploadProfilePictureStateDialog!!.dialog?.dismiss()
        }
        uploadProfilePictureStateDialog = UploadProfilePictureStateDialog.newInstance(error)
        uploadProfilePictureStateDialog!!.show(supportFragmentManager, null)
    }

    fun updateSaveBtnState() {
        binding.save.isEnabled = !(binding.displayName.text.length > Constants.DISPLAY_NAME_MAX_LENGTH || binding.aboutMe.text.length > Constants.ABOUT_ME_MAX_LENGTH)
    }

    fun save() {
        showSaveReminderDialog = false
        val displayName = binding.displayName.text.toString().trim()
        val publicMessage = binding.aboutMe.text.toString().trim()

        val avatarUrl = if (profilePictureChanged) {
            if (externalUrlSharedViewModel.externalUrl != null) {
                externalUrlSharedViewModel.externalUrl.toString()
            } else {
                editProfileViewModel.profilePictureUploadLiveData.value!!.data
            }
        } else {
            editProfileViewModel.dashPayProfile.value!!.avatarUrl
        }

        editProfileViewModel.broadcastUpdateProfile(displayName, publicMessage, avatarUrl ?: "")
        binding.save.isEnabled = false
        finish()
    }

    private fun showProfileInfo(profile: DashPayProfile) {
        if (!isEditing) {
            ProfilePictureDisplay.display(binding.dashpayUserAvatar, profile)
            initialAboutMe = profile.publicMessage
            initialDisplayName = profile.displayName
            binding.aboutMe.setText(profile.publicMessage)
            binding.displayName.setText(profile.displayName)
            editProfileViewModel.avatarHash = profile.avatarHash?.let { Sha256Hash.wrap(it) }
            editProfileViewModel.avatarFingerprint = profile.avatarFingerprint?.let { BigInteger(it) }
        }
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

    private fun selectImage() {
        SelectProfilePictureDialog.createDialog()
            .show(supportFragmentManager, "selectPictureDialog")
    }

    private fun choosePicture() {
        if (editProfileViewModel.createTmpPictureFile()) {
            val pickPhoto = Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI)
            turnOffAutoLogout()
            startActivityForResult(pickPhoto, REQUEST_CODE_URI)
        } else {
            Toast.makeText(this, "Unable to create temporary file", Toast.LENGTH_LONG).show()
        }
    }

    private fun takePicture() {
        if (editProfileViewModel.createTmpPictureFile()) {
            turnOffAutoLogout()
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
                turnOnAutoLogout()
                if (resultCode == RESULT_OK) {
                    // picture saved in editProfileViewModel.profilePictureTmpFile
                    editProfileViewModel.onTmpPictureReadyForEditEvent.postValue(editProfileViewModel.tmpPictureFile)
                }
            }
            REQUEST_CODE_URI -> {
                turnOnAutoLogout()
                if (resultCode == RESULT_OK && data != null) {
                    val selectedImage: Uri? = data.data
                    if (selectedImage != null) {
                        @Suppress("DEPRECATION")
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
                        }
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
                        externalUrlSharedViewModel.externalUrl = if (editProfileViewModel.dashPayProfile.value!!.avatarUrl == "") {
                            null
                        } else {
                            Uri.parse(editProfileViewModel.dashPayProfile.value!!.avatarUrl)
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
        selectProfilePictureSharedViewModel.onChooseStorageService.observe(this) {
            editProfileViewModel.storageService = it
            if (walletApplication.configuration.getAcceptedUploadPolicy(editProfileViewModel.storageService.name)) {
                startUploadProcess()
            } else {
                UploadPolicyDialog().show(supportFragmentManager, null)
            }
        }
        ChooseStorageServiceDialog.newInstance().show(supportFragmentManager, null)
    }

    private fun saveUrl(zoomedRect: RectF) {
        if (externalUrlSharedViewModel.externalUrl != null) {
            val zoomedRectStr = "${zoomedRect.left},${zoomedRect.top},${zoomedRect.right},${zoomedRect.bottom}"
            if (externalUrlSharedViewModel.shouldCrop) {
                externalUrlSharedViewModel.externalUrl = ProfilePictureHelper.setPicZoomParameter(externalUrlSharedViewModel.externalUrl!!, zoomedRectStr)
            }

            val file = editProfileViewModel.tmpPictureFile
            val imgUri = getFileUri(file)
            Glide.with(binding.dashpayUserAvatar).load(imgUri)
                .signature(ObjectKey(file.lastModified()))
                .placeholder(defaultAvatar)
                .transform(ProfilePictureTransformation.create(zoomedRect))
                .into(binding.dashpayUserAvatar)
            showSaveReminderDialog = true
        }
    }

    private fun cropProfilePicture() {
        if (externalUrlSharedViewModel.shouldCrop) {
            val tmpPictureUri = editProfileViewModel.tmpPictureFile.toUri()
            val profilePictureUri = editProfileViewModel.profilePictureFile!!.toUri()
            val initZoomedRect = ProfilePictureHelper.extractZoomedRect(externalUrlSharedViewModel.externalUrl)
            val intent = CropImageActivity.createIntent(this, tmpPictureUri, profilePictureUri, initZoomedRect)
            startActivityForResult(intent, REQUEST_CODE_CROP_IMAGE)
        } else {
            saveUrl(RectF(0.0f, 0.0f, 1.0f, 1.0f))
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

    //TODO: leave this for now, we might need it later
//if the signed in do we need to do it again?
    // TODO (ashikhmin): might be better to move GDrive related code to a separate service
    // and inject it here or into the viewModel
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
                .addOnSuccessListener { startActivityForResult(googleSignInClient.signInIntent, REQUEST_CODE_GOOGLE_DRIVE_SIGN_IN) }
                .addOnFailureListener { e: java.lang.Exception? ->
                    log.error("could not revoke access to drive: ", e)
                    applyGdriveAccessDenied()
                }
        }
    }

    private fun applyGdriveAccessDenied() {
        showUploadErrorDialog(UpdateProfileError.AUTHENTICATION)
    }

    private fun handleGdriveSigninResult(data: Intent) {
        try {
            log.info("gdrive: attempting to sign in to a google account")
            val account = GoogleSignIn.getSignedInAccountFromIntent(data).getResult(ApiException::class.java)
                ?: throw RuntimeException("empty account")
            applyGdriveAccessGranted(account)
        } catch (e: Exception) {
            log.error("Google Drive sign-in failed, could not get account: ", e)
            applyGdriveAccessDenied()
        }
    }

    private fun applyGdriveAccessGranted(signInAccount: GoogleSignInAccount) {
        log.info("gdrive: access granted to ${signInAccount.email} with: ${signInAccount.grantedScopes}")
        mDrive = GoogleDriveService.getDriveServiceFromAccount(applicationContext, signInAccount)
        log.info("gdrive: drive $mDrive")

        editProfileViewModel.googleDrive = mDrive
        editProfileViewModel.uploadProfilePicture()
    }

    override fun finish() {
        if (showSaveReminderDialog) {
            AdaptiveDialog.create(
                R.drawable.ic_info_blue,
                getString(R.string.save_changes),
                getString(R.string.save_profile_reminder_text),
                getString(R.string.no),
                getString(R.string.yes)
            ).show(this) {
                if (it == true) {
                    save()
                } else {
                    finish()
                }
            }
            return
        }

        super.finish()
    }
}
