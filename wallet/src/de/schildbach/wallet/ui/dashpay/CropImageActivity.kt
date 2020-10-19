package de.schildbach.wallet.ui.dashpay

import android.graphics.drawable.Drawable
import android.os.Bundle
import androidx.appcompat.widget.AppCompatImageView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.DataSource
import com.bumptech.glide.load.engine.GlideException
import com.bumptech.glide.request.RequestListener
import com.bumptech.glide.request.target.Target
import de.schildbach.wallet.WalletApplication
import de.schildbach.wallet_test.R
import kotlinx.android.synthetic.main.activity_crop_image.*
import org.dash.wallet.common.InteractionAwareActivity

class CropImageActivity : InteractionAwareActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_crop_image)

        val imagePath = WalletApplication.getInstance().configuration.localProfilePictureUri

        Glide.with(this).load(imagePath).into(background)
        Glide.with(this).load(imagePath).listener(object : RequestListener<Drawable> {
            override fun onLoadFailed(e: GlideException?, model: Any?, target: Target<Drawable>?, isFirstResource: Boolean): Boolean {
                //TODO: Handle error
                return false
            }

            override fun onResourceReady(resource: Drawable?, model: Any?, target: Target<Drawable>?,
                                         dataSource: DataSource?, isFirstResource: Boolean): Boolean {
                circle_crop.setImageDrawable(resource)
                circle_crop.maxZoom = 5f
                return true
            }
        }).into(circle_crop as AppCompatImageView)
    }

}
