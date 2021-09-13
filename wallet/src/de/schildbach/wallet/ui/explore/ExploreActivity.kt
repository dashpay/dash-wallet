package de.schildbach.wallet.ui.explore

import android.os.Bundle
import android.widget.Toast
import androidx.activity.viewModels
import de.schildbach.wallet.ui.BaseMenuActivity
import de.schildbach.wallet_test.R
import org.dash.wallet.features.exploredash.ExploreViewModel

class ExploreActivity : BaseMenuActivity() {
    private val viewModel: ExploreViewModel by viewModels()

    override fun getLayoutId(): Int {
        return R.layout.activity_explore
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        viewModel.event.observe(this) {
            Toast.makeText(this, it, Toast.LENGTH_SHORT).show()
        }
    }
}