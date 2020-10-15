package de.schildbach.wallet.ui.dashpay

import android.graphics.drawable.Drawable
import android.os.Bundle
import android.os.Handler
import android.util.TimeUtils
import androidx.appcompat.widget.AppCompatImageView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.DataSource
import com.bumptech.glide.load.engine.GlideException
import com.bumptech.glide.request.RequestListener
import com.bumptech.glide.request.target.Target
import com.ortiz.touchview.TouchImageView
import de.schildbach.wallet.WalletApplication
import de.schildbach.wallet.ui.dashpay.widget.CircleCropTouchImageView
import de.schildbach.wallet_test.R
import kotlinx.android.synthetic.main.activity_crop_image.*
import org.dash.wallet.common.InteractionAwareActivity
import java.util.concurrent.TimeUnit

class CropImageActivity : InteractionAwareActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_crop_image)

        val imagePath = WalletApplication.getInstance().configuration.localProfilePictureUri
        Glide.with(this).load(imagePath).listener(object : RequestListener<Drawable> {
            override fun onLoadFailed(e: GlideException?, model: Any?, target: Target<Drawable>?, isFirstResource: Boolean): Boolean {
                //TODO: Handle error
                return false
            }

            override fun onResourceReady(resource: Drawable?, model: Any?, target: Target<Drawable>?,
                                         dataSource: DataSource?, isFirstResource: Boolean): Boolean {
                background.setImageDrawable(resource)
                background.minZoom = 0.7f
                return true
            }
        }).into(background as AppCompatImageView)
    }

}
