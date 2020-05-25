package de.schildbach.wallet.ui

import android.os.Build
import android.os.Bundle
import android.view.Window
import com.bumptech.glide.Glide
import de.schildbach.wallet_test.R
import kotlinx.android.synthetic.main.activity_dashpay_profile.*
import org.dash.wallet.common.InteractionAwareActivity

class DashPayProfileActivity : InteractionAwareActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            with(window) {
                requestFeature(Window.FEATURE_CONTENT_TRANSITIONS)
            }
        }
        setContentView(R.layout.activity_dashpay_profile)
        val avatarUrl = intent.getStringExtra("avatarUrl")
        Glide.with(this).load(avatarUrl).circleCrop().placeholder(R.drawable.user5).into(avatar)
    }

}