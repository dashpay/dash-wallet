package de.schildbach.wallet.ui.dashpay

import android.os.Bundle
import com.bumptech.glide.Glide
import de.schildbach.wallet.WalletApplication
import de.schildbach.wallet_test.R
import kotlinx.android.synthetic.main.activity_crop_image.*
import org.dash.wallet.common.InteractionAwareActivity

class CropImageActivity : InteractionAwareActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_crop_image)

        val imagePath = WalletApplication.getInstance().configuration.localProfilePictureUri
        Glide.with(background).load(imagePath).into(background)
    }

}
