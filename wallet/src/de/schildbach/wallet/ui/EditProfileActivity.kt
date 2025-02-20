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
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager.PERMISSION_GRANTED
import android.database.Cursor
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.RectF
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.result.ActivityResult
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.lifecycleScope
import com.amulyakhare.textdrawable.TextDrawable
import com.bumptech.glide.Glide
import com.bumptech.glide.signature.ObjectKey
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import com.google.api.client.googleapis.auth.oauth2.GoogleCredential
import com.google.api.client.googleapis.extensions.android.gms.auth.GoogleAuthIOException
import com.google.api.services.drive.DriveScopes
import dagger.hilt.android.AndroidEntryPoint
import de.schildbach.wallet.Constants
import de.schildbach.wallet.database.entity.DashPayProfile
import de.schildbach.wallet.livedata.Status
import de.schildbach.wallet.ui.dashpay.*
import de.schildbach.wallet.ui.dashpay.utils.GoogleDriveService
import de.schildbach.wallet.ui.dashpay.utils.display
import de.schildbach.wallet.ui.dashpay.work.UpdateProfileError
import de.schildbach.wallet.ui.send.SendCoinsActivity
import de.schildbach.wallet_test.R
import de.schildbach.wallet_test.databinding.ActivityEditProfileBinding
import kotlinx.coroutines.launch
import org.bitcoinj.core.Sha256Hash
import org.dash.wallet.common.services.analytics.AnalyticsConstants
import org.dash.wallet.common.ui.avatar.ProfilePictureDisplay
import org.dash.wallet.common.ui.avatar.ProfilePictureHelper
import org.dash.wallet.common.ui.avatar.ProfilePictureTransformation
import org.dash.wallet.common.ui.dialogs.AdaptiveDialog
import org.slf4j.LoggerFactory
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
import java.math.BigInteger
import javax.inject.Inject


@AndroidEntryPoint
class EditProfileActivity : LockScreenActivity() {

    @Inject
    lateinit var googleDriveService: GoogleDriveService

    companion object {
        private val log = LoggerFactory.getLogger(EditProfileActivity::class.java)
    }

    private lateinit var binding: ActivityEditProfileBinding
    private val editProfileViewModel: EditProfileViewModel by viewModels()
    private val selectProfilePictureSharedViewModel: SelectProfilePictureSharedViewModel by viewModels()
    private val externalUrlSharedViewModel: ExternalUrlProfilePictureViewModel by viewModels()

    private var isEditing: Boolean = false
    private var defaultAvatar: TextDrawable? = null

    private var profilePictureChanged = false
    private var uploadProfilePictureStateDialog: UploadProfilePictureStateDialog? = null
    private lateinit var takePicturePermissionLauncher: ActivityResultLauncher<String>
    private lateinit var takePictureLauncher: ActivityResultLauncher<Uri>
    private lateinit var chooseImageLauncher: ActivityResultLauncher<PickVisualMediaRequest>
    private lateinit var cropImageLauncher: ActivityResultLauncher<Intent>
    private lateinit var googleDriveAuthLauncher: ActivityResultLauncher<IntentSenderRequest>

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
        val mediumGrayTextColor = ContextCompat.getColor(this, R.color.dash_medium_gray)
        binding.displayName.doAfterTextChanged { s ->
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

        binding.aboutMe.doAfterTextChanged { s ->
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
        binding.save.setOnClickListener {
            saveButton()
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
            choosePicture()
        }

        editProfileViewModel.onTmpPictureReadyForEditEvent.observe(this) {
            imitateUserInteraction()
            cropProfilePicture()
        }

        takePicturePermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted: Boolean ->
            if (isGranted) {
                takePicture()
            } else {
                // Handle the case where permission is denied
            }
        }

        takePictureLauncher = registerForActivityResult(ActivityResultContracts.TakePicture()) { isSuccess: Boolean ->
            if (isSuccess) {
                editProfileViewModel.onTmpPictureReadyForEditEvent.postValue(editProfileViewModel.tmpPictureFile)
            }
            turnOnAutoLogout()
        }

        chooseImageLauncher = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { selectedImage: Uri? ->
            if (selectedImage != null) {
                turnOnAutoLogout()
                handleSelectedImage(selectedImage)
            }
        }

        cropImageLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result: ActivityResult ->
            if (result.resultCode == Activity.RESULT_OK) {
                if (externalUrlSharedViewModel.externalUrl != null) {
                    saveUrl(CropImageActivity.extractZoomedRect(result.data!!))
                } else {
                    showProfilePictureServiceDialog()
                }
            } else if (result.resultCode == Activity.RESULT_CANCELED) {
                if (externalUrlSharedViewModel.externalUrl != null) {
                    val avatarUrl = editProfileViewModel.dashPayProfile.value!!.avatarUrl
                    externalUrlSharedViewModel.externalUrl =
                        if (avatarUrl.isEmpty()) null else Uri.parse(avatarUrl)
                }
            }
        }

        googleDriveAuthLauncher = registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result: ActivityResult ->
            try {
                val authorizationResult = Identity.getAuthorizationClient(this@EditProfileActivity).getAuthorizationResultFromIntent(result.data)
                processGoogleAuthorizationResult(authorizationResult)
            } catch (ex: Exception) {
                log.error("Failed to get auth result for Google Drive", ex)
            }
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
                    val error = if (it.exception is GoogleAuthIOException) {
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
                configuration.setAcceptedUploadPolicy(editProfileViewModel.storageService.name, true)
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
            EditProfileViewModel.ProfilePictureStorageService.GOOGLE_DRIVE -> {
                authorizeGoogleDrive()
            }
            EditProfileViewModel.ProfilePictureStorageService.IMGUR -> {
                editProfileViewModel.uploadProfilePicture()
            }
        }
    }

    private fun authorizeGoogleDrive() {
        val authorizationRequest = AuthorizationRequest
            .builder()
            .setRequestedScopes(
                listOf(Scope(DriveScopes.DRIVE))
            ).build()

        Identity.getAuthorizationClient(this@EditProfileActivity)
            .authorize(authorizationRequest)
            .addOnSuccessListener { authorizationResult ->
                if (authorizationResult.hasResolution()) {
                    try {
                        val intentSenderRequest = IntentSenderRequest.Builder(authorizationResult.pendingIntent!!.intentSender).build()
                        googleDriveAuthLauncher.launch(intentSenderRequest)
                    } catch (e: Exception) {
                        log.error("Failed to start Google Drive auth", e)
                        showUploadErrorDialog(UpdateProfileError.AUTHENTICATION)
                    }
                } else {
                    // Access already granted, continue with user action
                    processGoogleAuthorizationResult(authorizationResult)
                }
            }
            .addOnFailureListener { e ->
                log.error("Failed to authorize Google Drive", e)
                showUploadErrorDialog(UpdateProfileError.AUTHENTICATION)
            }
    }

    private fun processGoogleAuthorizationResult(authorizationResult: AuthorizationResult) {
        try {
            val credential = authorizationResult.accessToken?.let { GoogleCredential().setAccessToken(it) }

            if (credential != null) {
                editProfileViewModel.uploadProfilePicture(credential)
            } else {
                showUploadErrorDialog(UpdateProfileError.AUTHENTICATION)
            }
        } catch (e: Exception) {
            log.error("Failed to get Google credentials", e)
            showUploadErrorDialog(UpdateProfileError.AUTHENTICATION)
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
        Glide.with(this).load(url).circleCrop().timeout(20000).into(binding.dashpayUserAvatar)
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

    private fun saveButton() {
        lifecycleScope.launch {
            val enough = editProfileViewModel.hasEnoughCredits()
            val shouldWarn = enough.isBalanceWarning()
            val isEmpty = enough.isBalanceWarning()

            if (shouldWarn || isEmpty) {
                val answer = AdaptiveDialog.create(
                    R.drawable.ic_warning_yellow_circle,
                    if (isEmpty) getString(R.string.credit_balance_empty_warning_title) else getString(R.string.credit_balance_low_warning_title),
                    if (isEmpty) getString(R.string.credit_balance_empty_warning_message) else getString(R.string.credit_balance_low_warning_message),
                    getString(R.string.credit_balance_button_maybe_later),
                    getString(R.string.credit_balance_button_buy)
                ).showAsync(this@EditProfileActivity)

                if (answer == true) {
                    SendCoinsActivity.startBuyCredits(this@EditProfileActivity)
                } else {
                    if (shouldWarn)
                        save()
                }
            } else {
                save()
            }
        }
    }

    private fun save() {
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

        editProfileViewModel.logEvent(AnalyticsConstants.MoreMenu.UPDATE_PROFILE)
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
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PERMISSION_GRANTED) {
            takePicture()
        } else {
            takePicturePermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    private fun selectImage() {
        SelectProfilePictureDialog.createDialog()
            .show(supportFragmentManager, "selectPictureDialog")
    }

    private fun choosePicture() {
        if (editProfileViewModel.createTmpPictureFile()) {
            turnOffAutoLogout()
            chooseImageLauncher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
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
        val tmpFileUri = getFileUri(editProfileViewModel.tmpPictureFile)
        takePictureLauncher.launch(tmpFileUri)
    }

    private fun handleSelectedImage(selectedImage: Uri) {
        val filePathColumn = arrayOf(MediaStore.Images.Media.DATA)
        val cursor: Cursor? = contentResolver.query(
            selectedImage,
            filePathColumn, null, null, null
        )
        if (cursor != null) {
            cursor.moveToFirst()
            val columnIndex: Int = cursor.getColumnIndex(filePathColumn[0])
            val picturePath: String? = cursor.getString(columnIndex)
            cursor.close()
            if (picturePath != null) {
                editProfileViewModel.saveAsProfilePictureTmp(picturePath)
            } else {
                saveImageWithAuthority(selectedImage)
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
        if (configuration.imgurDeleteHash.isNotEmpty() && showDeleteDialog) {
            DeleteProfilePictureConfirmationDialog().show(supportFragmentManager, null)
            return
        }
        selectProfilePictureSharedViewModel.onChooseStorageService.observe(this) {
            editProfileViewModel.storageService = it
            if (configuration.getAcceptedUploadPolicy(editProfileViewModel.storageService.name)) {
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
                .timeout(20000)
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

            // Use the launcher to start the activity
            cropImageLauncher.launch(intent)
        } else {
            saveUrl(RectF(0.0f, 0.0f, 1.0f, 1.0f))
        }
    }

    private fun getFileUri(file: File): Uri {
        return FileProvider.getUriForFile(walletApplication, "${walletApplication.packageName}.file_attachment", file)
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
                    super.finish()
                }
            }
            return
        }

        super.finish()
    }
}

