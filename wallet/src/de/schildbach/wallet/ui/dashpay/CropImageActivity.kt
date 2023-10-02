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

package de.schildbach.wallet.ui.dashpay

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.RectF
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.widget.AppCompatImageView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.DataSource
import com.bumptech.glide.load.engine.GlideException
import com.bumptech.glide.request.RequestListener
import com.bumptech.glide.request.target.Target
import de.schildbach.wallet.ui.LockScreenActivity
import de.schildbach.wallet_test.R
import de.schildbach.wallet_test.databinding.ActivityCropImageBinding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.dash.wallet.common.InteractionAwareActivity
import org.slf4j.LoggerFactory

class CropImageActivity : LockScreenActivity() {

    companion object {

        private val log = LoggerFactory.getLogger(CropImageActivity::class.java)

        private const val TEMP_FILE = "temp_file"
        private const val DESTINATION_FILE = "destination_file"
        private const val ZOOMED_RECT = "zoomed_rect"

        fun createIntent(context: Context, tempFile: Uri, destinationFile: Uri, zoomedRect: RectF? = null): Intent {
            val intent = Intent(context, CropImageActivity::class.java)
            intent.putExtra(TEMP_FILE, tempFile)
            intent.putExtra(DESTINATION_FILE, destinationFile)
            intent.putExtra(ZOOMED_RECT, zoomedRect)
            return intent
        }

        fun extractZoomedRect(data: Intent): RectF {
            return data.getParcelableExtra(ZOOMED_RECT)!!
        }
    }
    
    private lateinit var binding: ActivityCropImageBinding

    private val initZoomedRect by lazy {
        intent.getParcelableExtra<RectF>(ZOOMED_RECT)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCropImageBinding.inflate(layoutInflater)

        val tempFile = intent.getParcelableExtra<Uri>(TEMP_FILE)
        val destinationFile = intent.getParcelableExtra<Uri>(DESTINATION_FILE)
        Glide.with(this)
                .load(tempFile)
                .listener(object : RequestListener<Drawable> {
                    override fun onLoadFailed(e: GlideException?, model: Any?, target: Target<Drawable>?, isFirstResource: Boolean): Boolean {
                        Toast.makeText(this@CropImageActivity,
                                R.string.unable_to_load_image, Toast.LENGTH_SHORT).show()
                        finish()
                        return false
                    }

                    override fun onResourceReady(resource: Drawable?, model: Any?, target: Target<Drawable>?,
                                                 dataSource: DataSource?, isFirstResource: Boolean): Boolean {
                        binding.background.setImageDrawable(resource)
                        binding.circleCrop.setImageDrawable(resource)
                        binding.circleCrop.maxZoom = 5f

                        initZoomedRect?.let { rect ->
                            binding.circleCrop.post {
                                val focusX = (rect.left + rect.right) / 2
                                val focusY = (rect.top + rect.bottom) / 2
                                val zoomX = 1f / (rect.right - rect.left)
                                val zoomY = 1f / (rect.bottom - rect.top)
                                val bitmap = (resource as BitmapDrawable).bitmap
                                log.info("bitmap: ${bitmap.width}x${bitmap.height}, zoomRect:${rect}, zoomX = $zoomX, zoomY = $zoomY")
                                binding.circleCrop.setZoom(zoomX, focusX, focusY)
                            }
                        }
                        return true
                    }
                })
                .into(binding.circleCrop as AppCompatImageView)

        binding.selectBtn.setOnClickListener {
            CoroutineScope(Dispatchers.IO).launch {
                binding.circleCrop.saveToFile(destinationFile!!)
                withContext(Dispatchers.Main) {
                    finishWithSuccess()
                }
            }
        }
        binding.cancelBtn.setOnClickListener {
            setResult(Activity.RESULT_CANCELED)
            finish()
        }
    }

    private fun finishWithSuccess() {
        val data = Intent().apply {
            putExtra(ZOOMED_RECT, binding.circleCrop.zoomedRect)
        }
        setResult(Activity.RESULT_OK, data)
        finish()
    }
}
