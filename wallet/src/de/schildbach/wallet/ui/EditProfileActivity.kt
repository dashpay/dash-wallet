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
import com.bumptech.glide.request.target.Target
import com.bumptech.glide.signature.ObjectKey
import de.schildbach.wallet.Constants
import de.schildbach.wallet.data.DashPayProfile
import de.schildbach.wallet.livedata.Status
import de.schildbach.wallet.ui.dashpay.*
import de.schildbach.wallet_test.R
import kotlinx.android.synthetic.main.activity_edit_profile.*
import kotlinx.android.synthetic.main.activity_more.dashpayUserAvatar
import java.io.File

class EditProfileActivity : BaseMenuActivity() {

    companion object {
        const val REQUEST_CODE_URI = 0
        const val REQUEST_CODE_IMAGE = 1
        const val REQUEST_CODE_CHOOSE_PICTURE_PERMISSION = 2
        const val REQUEST_CODE_TAKE_PICTURE_PERMISSION = 3
        const val REQUEST_CODE_CROP_IMAGE = 4
    }

    private lateinit var editProfileViewModel: EditProfileViewModel
    private lateinit var selectProfilePictureSharedViewModel: SelectProfilePictureSharedViewModel
    private lateinit var externalUrlSharedViewModel: ExternalUrlProfilePictureViewModel

    private var isEditing: Boolean = false
    private var defaultAvatar: TextDrawable? = null

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

        selectProfilePictureSharedViewModel.onFromUrlCallback.observe(this, Observer<Void> {
            pictureFromUrl()
        })

        selectProfilePictureSharedViewModel.onTakePictureCallback.observe(this, Observer<Void> {
            takePictureWithPermission()
        })

        selectProfilePictureSharedViewModel.onChoosePictureCallback.observe(this, Observer<Void> {
            choosePictureWithPermission()
        })

        editProfileViewModel.onTmpPictureReadyForEditEvent.observe(this, Observer {
            cropProfilePicture()
        })
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
        externalUrlSharedViewModel.validUrlChosenEvent.observe(this, {
            editProfileViewModel.saveExternalBitmap(it)
        })
    }

    fun activateDeactivateSave() {
        save.isEnabled = !(display_name.text.length > Constants.DISPLAY_NAME_MAX_LENGTH || about_me.text.length > Constants.ABOUT_ME_MAX_LENGTH)
    }

    fun save() {
        val displayName = display_name.text.toString().trim()
        val publicMessage = about_me.text.toString().trim()
        val avatarUrl = if (externalUrlSharedViewModel.externalUrl != null) {
            externalUrlSharedViewModel.externalUrl.toString()
        } else ""
        editProfileViewModel.broadcastUpdateProfile(displayName, publicMessage, avatarUrl)
        save.isEnabled = false
        finish()
    }

    private fun showProfileInfo(profile: DashPayProfile) {
        val defaultAvatar = UserAvatarPlaceholderDrawable.getDrawable(this,
                profile.username.toCharArray()[0])
        if (profile.avatarUrl.isNotEmpty()) {
            Glide.with(dashpayUserAvatar)
                    .load(profile.avatarUrl)
                    .override(Target.SIZE_ORIGINAL, Target.SIZE_ORIGINAL)
                    .transform(ProfilePictureTransformation.create(profile.avatarUrl))
                    .placeholder(defaultAvatar).into(dashpayUserAvatar)
        } else {
            if (editProfileViewModel.profilePictureFile != null && editProfileViewModel.profilePictureFile!!.exists()) {
                setAvatarFromFile(editProfileViewModel.profilePictureFile!!)
            } else {
                dashpayUserAvatar.setImageDrawable(defaultAvatar)
            }
        }

        about_me.setText(profile.publicMessage)
        display_name.setText(profile.displayName)
    }

    private fun setAvatarFromFile(file: File) {
        val imgUri = getFileUri(file)
        Glide.with(dashpayUserAvatar).load(imgUri).signature(ObjectKey(file.lastModified()))
                .placeholder(defaultAvatar).circleCrop().into(dashpayUserAvatar)
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
        if (resultCode != RESULT_CANCELED) {
            when (requestCode) {
                REQUEST_CODE_IMAGE -> {
                    if (resultCode == RESULT_OK) {
                        // picture saved in editProfileViewModel.profilePictureTmpFile
                        editProfileViewModel.onTmpPictureReadyForEditEvent.call(editProfileViewModel.tmpPictureFile)
                    }
                }
                REQUEST_CODE_URI -> if (resultCode == RESULT_OK && data != null) {
                    val selectedImage: Uri? = data.data
                    val filePathColumn = arrayOf(MediaStore.Images.Media.DATA)
                    if (selectedImage != null) {
                        val cursor: Cursor? = contentResolver.query(selectedImage,
                                filePathColumn, null, null, null)
                        if (cursor != null) {
                            cursor.moveToFirst()
                            val columnIndex: Int = cursor.getColumnIndex(filePathColumn[0])
                            val picturePath: String = cursor.getString(columnIndex)
                            editProfileViewModel.saveAsProfilePictureTmp(picturePath)
                            cursor.close()
                        }
                    }
                }
                REQUEST_CODE_CROP_IMAGE -> {
                    if (resultCode == Activity.RESULT_OK) {
                        if (externalUrlSharedViewModel.externalUrl != null) {
                            saveUrl(CropImageActivity.extractZoomedRect(data!!))
                        } else {
                            setAvatarFromFile(editProfileViewModel.profilePictureFile!!)
                        }
                    }
                }
            }
        }
    }

    private fun saveUrl(zoomedRect: RectF) {
        if (externalUrlSharedViewModel.externalUrl != null) {
            val zoomedRectStr = "${zoomedRect.left},${zoomedRect.top},${zoomedRect.right},${zoomedRect.bottom}"
            externalUrlSharedViewModel.externalUrl = setUriParameter(externalUrlSharedViewModel.externalUrl!!, "dashpay-profile-pic-zoom", zoomedRectStr)

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
        val tmpPictureUri = editProfileViewModel.tmpPictureFile.toUri()
        val profilePictureUri = editProfileViewModel.profilePictureFile!!.toUri()
        val initZoomedRect = ProfilePictureTransformation.extractZoomedRect(externalUrlSharedViewModel.externalUrl)
        val intent = CropImageActivity.createIntent(this, tmpPictureUri, profilePictureUri, initZoomedRect)
        startActivityForResult(intent, REQUEST_CODE_CROP_IMAGE)
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
}
