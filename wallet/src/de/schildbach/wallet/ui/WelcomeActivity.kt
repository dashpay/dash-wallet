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

package de.schildbach.wallet.ui

import android.app.Activity
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import androidx.fragment.app.FragmentPagerAdapter
import androidx.viewpager.widget.ViewPager
import dagger.hilt.android.AndroidEntryPoint
import de.schildbach.wallet_test.R
import de.schildbach.wallet_test.databinding.ActivityWelcomeBinding
import org.dash.wallet.common.Configuration
import javax.inject.Inject

/**
 * @author Samuel Barbosa
 */
@AndroidEntryPoint
class WelcomeActivity : AppCompatActivity() {

    @Inject
    lateinit var configuration: Configuration
    private lateinit var binding: ActivityWelcomeBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityWelcomeBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.viewpager.adapter = WelcomePagerAdapter(supportFragmentManager, 0, configuration)
        binding.pageIndicator.setViewPager(binding.viewpager)

        binding.viewpager.addOnPageChangeListener(object : ViewPager.OnPageChangeListener {
            override fun onPageScrollStateChanged(state: Int) {
            }

            override fun onPageScrolled(position: Int, positionOffset: Float, positionOffsetPixels: Int) {
            }

            override fun onPageSelected(position: Int) {
                when (position) {
                    0, 1 -> {
                        binding.skipButton.visibility = View.VISIBLE
                        binding.getStartedButton.visibility = View.GONE
                    }
                    2 -> {
                        binding.skipButton.visibility = View.GONE
                        binding.getStartedButton.visibility = View.VISIBLE
                    }
                }
            }
        })

        binding.skipButton.setOnClickListener { finish() }
        binding.getStartedButton.setOnClickListener { finish() }
    }

    override fun finish() {
        configuration.setV7TutorialCompleted()
        setResult(Activity.RESULT_OK)
        super.finish()
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
    }

    private class WelcomePagerAdapter(
        fragmentManager: FragmentManager,
        behavior: Int,
        val configuration: Configuration
    ) : FragmentPagerAdapter(fragmentManager, behavior) {

        override fun getItem(position: Int): Fragment {
            return when (position) {
                0 -> {
                    val title = if (configuration.wasUpgraded()) {
                        R.string.welcome_screen_title_1_upgrade
                    } else {
                        R.string.welcome_screen_title_1_new_install
                    }
                    WelcomeScreenFragment.newInstance(
                        title,
                        R.string.welcome_screen_subtitle_1,
                        R.drawable.welcome_screenshot_1
                    )
                }
                1 -> WelcomeScreenFragment.newInstance(
                    R.string.welcome_screen_title_2,
                    R.string.welcome_screen_subtitle_2,
                    R.drawable.welcome_screenshot_2
                )
                2 -> WelcomeScreenFragment.newInstance(
                    R.string.welcome_screen_title_3,
                    R.string.welcome_screen_subtitle_3,
                    R.drawable.welcome_screenshot_3
                )
                else -> Fragment()
            }
        }

        override fun getCount(): Int {
            return 3
        }
    }
}
