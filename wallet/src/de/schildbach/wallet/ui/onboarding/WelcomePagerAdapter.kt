package de.schildbach.wallet.ui.onboarding

import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.fragment.app.FragmentManager
import androidx.viewpager2.adapter.FragmentStateAdapter
import de.schildbach.wallet_test.R
import org.dash.wallet.common.Configuration

class WelcomePagerAdapter(
    fragmentActivity: FragmentActivity,
    //behavior: Int,
    //val configuration: Configuration
) : FragmentStateAdapter(fragmentActivity) {

    override fun createFragment(position: Int): Fragment {
        return when (position) {
            0 -> {
//                val title = if (configuration.wasUpgraded()) {
//                    R.string.welcome_screen_title_1_upgrade
//                } else {
//                    R.string.welcome_screen_title_1_new_install
//                }
                WelcomeScreenFragment.newInstance(
                    R.string.welcome_screen_title_1,
                    R.drawable.welcome_image_1
                )
            }
            1 -> WelcomeScreenFragment.newInstance(
                R.string.welcome_screen_title_2,
                R.drawable.welcome_merchants
            )
            2 -> WelcomeScreenFragment.newInstance(
                R.string.welcome_screen_title_3,
                R.drawable.welcome_secure
            )
            else -> Fragment()
        }
    }

    override fun getItemCount(): Int {
        return 3
    }
}