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
import android.Manifest.permission.READ_EXTERNAL_STORAGE
import android.Manifest.permission.READ_MEDIA_IMAGES
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager.PERMISSION_GRANTED
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
import androidx.activity.result.ActivityResult
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
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
import de.schildbach.wallet.data.PaymentIntent
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
import org.bitcoinj.core.Coin
import org.bitcoinj.core.Sha256Hash
import org.bitcoinj.script.ScriptBuilder
import org.bitcoinj.wallet.AuthenticationKeyChain
import org.bitcoinj.wallet.AuthenticationKeyChainGroup
import org.bitcoinj.wallet.authentication.AuthenticationGroupExtension
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


@AndroidEntryPoint
class EditProfileActivity : LockScreenActivity() {

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
    private lateinit var choosePicturePermissionLauncher: ActivityResultLauncher<String>
    private lateinit var chooseImageLauncher: ActivityResultLauncher<String>
    private lateinit var cropImageLauncher: ActivityResultLauncher<Intent>
    private lateinit var googleDriveSignInLauncher: ActivityResultLauncher<Intent>

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
        val mediumGrayTextColor = ContextCompat.getColor(this, R.color.dash_medium_gray)
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
            choosePictureWithPermission()
        }

        editProfileViewModel.onTmpPictureReadyForEditEvent.observe(this) {
            imitateUserInteraction()
            cropProfilePicture()
        }

        takePicturePermissionLauncher =  registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted: Boolean ->
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

        choosePicturePermissionLauncher =  registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted: Boolean ->
            if (isGranted) {
                choosePicture()
            } else {
                // Handle the case where permission is denied
            }
        }
        // Initialize the launchers
        chooseImageLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { selectedImage: Uri? ->
            // Handle the picked image
            if (selectedImage != null) {
                // your code to handle the image
                turnOnAutoLogout()

                @Suppress("DEPRECATION")
                val filePathColumn = arrayOf(MediaStore.Images.Media.DATA)
                val cursor: Cursor? = contentResolver.query(
                    selectedImage,
                    filePathColumn, null, null, null
                )
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

        cropImageLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result: ActivityResult ->
            if (result.resultCode == Activity.RESULT_OK) {
                if (externalUrlSharedViewModel.externalUrl != null) {
                    saveUrl(CropImageActivity.extractZoomedRect(result.data!!))
                } else {
                    showProfilePictureServiceDialog()
                }
            } else if (result.resultCode == Activity.RESULT_CANCELED) {
                // if crop was canceled, then return the externalUrl to its original state
                if (externalUrlSharedViewModel.externalUrl != null) {
                    externalUrlSharedViewModel.externalUrl =
                        if (editProfileViewModel.dashPayProfile.value!!.avatarUrl == "") {
                            null
                        } else {
                            Uri.parse(editProfileViewModel.dashPayProfile.value!!.avatarUrl)
                        }
                }
            }
        }

        googleDriveSignInLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result: ActivityResult ->
            if (result.resultCode == Activity.RESULT_OK) {
                // Handle Google Drive Sign In Result
                handleGdriveSigninResult(result.data!!)
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

    private fun saveButton() {
        lifecycleScope.launch {
            val enough = editProfileViewModel.hasEnoughCredits()
            // TODO: before merging remove this
            val shouldWarn = true // enough.isBalanceWarning()
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
                    val authenticationGroupExtension = walletData.wallet!!.getKeyChainExtension(AuthenticationGroupExtension.EXTENSION_ID) as AuthenticationGroupExtension
                    val pubKeyHash = authenticationGroupExtension.freshKey(AuthenticationKeyChain.KeyChainType.BLOCKCHAIN_IDENTITY_TOPUP).pubKeyHash
                    SendCoinsActivity.startBuyCredits(this@EditProfileActivity, pubKeyHash)
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

    private fun choosePictureWithPermission() {
        // TODO: see https://android-developers.googleblog.com/2023/08/choosing-right-storage-experience.html
        // for android 14 changes
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            (ContextCompat.checkSelfPermission(this, READ_MEDIA_IMAGES) == PERMISSION_GRANTED)
        ) {
            // Full access on Android 13+
            choosePicture()
        } else if (ContextCompat.checkSelfPermission(this, READ_EXTERNAL_STORAGE) == PERMISSION_GRANTED) {
            // Full access up to Android 12
            choosePicture()
        } else {
            // Access denied, so ask for permission
            val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) READ_MEDIA_IMAGES else READ_EXTERNAL_STORAGE
            choosePicturePermissionLauncher.launch(permission)
        }
    }

    private fun selectImage() {
        SelectProfilePictureDialog.createDialog()
            .show(supportFragmentManager, "selectPictureDialog")
    }

    private fun choosePicture() {
        if (editProfileViewModel.createTmpPictureFile()) {
            turnOffAutoLogout()
            chooseImageLauncher.launch("image/*")
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

            // Use the launcher to start the activity
            cropImageLauncher.launch(intent)
        } else {
            saveUrl(RectF(0.0f, 0.0f, 1.0f, 1.0f))
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
            googleDriveSignInLauncher.launch(googleSignInClient.signInIntent)
        } else {
            googleSignInClient.revokeAccess()
                .addOnSuccessListener { googleDriveSignInLauncher.launch(googleSignInClient.signInIntent) }
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
