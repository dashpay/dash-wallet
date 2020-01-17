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

import android.os.Bundle
import android.view.View
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.fragment.app.Fragment
import de.schildbach.wallet_test.R
import kotlinx.android.synthetic.main.fragment_welcome.*

/**
 * @author Samuel Barbosa
 */
class WelcomeScreenFragment : Fragment(R.layout.fragment_welcome) {

    companion object {
        private const val ARG_TITLE = "title"
        private const val ARG_SUBTITLE = "subtitle"
        private const val ARG_IMAGE = "image"

        fun newInstance(@StringRes title: Int, @StringRes subTitle: Int,
                        @DrawableRes image: Int): WelcomeScreenFragment {
            val fragment = WelcomeScreenFragment()

            val args = Bundle()
            args.putInt(ARG_TITLE, title)
            args.putInt(ARG_SUBTITLE, subTitle)
            args.putInt(ARG_IMAGE, image)

            fragment.arguments = args

            return fragment
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        title.text = getString(arguments!!.getInt(ARG_TITLE))
        subtitle.text = getString(arguments!!.getInt(ARG_SUBTITLE))
        screenshot.setImageResource(arguments!!.getInt(ARG_IMAGE))
    }

}