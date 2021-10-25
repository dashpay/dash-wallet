package de.schildbach.wallet.ui.explore

import android.os.Bundle
import androidx.activity.viewModels
import dagger.hilt.android.AndroidEntryPoint
import de.schildbach.wallet.ui.BaseMenuActivity
import de.schildbach.wallet.ui.PaymentsActivity
import de.schildbach.wallet_test.R
import org.dash.wallet.features.exploredash.ui.ExploreViewModel
import org.dash.wallet.features.exploredash.ui.NavigationRequest

@AndroidEntryPoint
class ExploreActivity : BaseMenuActivity() {
    private val viewModel: ExploreViewModel by viewModels()

    override fun getLayoutId(): Int {
        return R.layout.activity_explore
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        viewModel.navigationCallback.observe(this) { request ->
            when (request) {
                NavigationRequest.SendDash -> {
                    val sendCoinsIntent = PaymentsActivity.createIntent(this, 0)
                    startActivity(sendCoinsIntent)
                }
                NavigationRequest.ReceiveDash -> {
                    val sendCoinsIntent = PaymentsActivity.createIntent(this, 1)
                    startActivity(sendCoinsIntent)
                }
                else -> {}
            }
        }
    }
}