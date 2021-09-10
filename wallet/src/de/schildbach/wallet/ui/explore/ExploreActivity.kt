package de.schildbach.wallet.ui.explore

import android.os.Bundle
import androidx.fragment.app.Fragment
import de.schildbach.wallet.ui.BaseMenuActivity
import de.schildbach.wallet_test.R

class ExploreActivity : BaseMenuActivity() {
    override fun getLayoutId(): Int {
        return R.layout.activity_explore
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        replaceFragment(ExploreFragment.newInstance())
    }

    private fun replaceFragment(fragment: Fragment) {
        val enter = R.anim.slide_in_right
        val exit =  R.anim.slide_out_left
        supportFragmentManager.beginTransaction().setCustomAnimations(enter,
            exit).replace(R.id.container, fragment).commit()
    }
}