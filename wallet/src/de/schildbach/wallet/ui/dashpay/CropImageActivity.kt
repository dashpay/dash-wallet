package de.schildbach.wallet.ui.dashpay

import android.os.Bundle
import android.os.PersistableBundle
import de.schildbach.wallet_test.R
import org.dash.wallet.common.InteractionAwareActivity

class CropImageActivity : InteractionAwareActivity() {

    override fun onCreate(savedInstanceState: Bundle?, persistentState: PersistableBundle?) {
        super.onCreate(savedInstanceState, persistentState)
        setContentView(R.layout.activity_crop_image)
    }

}
