/*
 * Copyright 2021 Dash Core Group
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

package de.schildbach.wallet.ui.invite

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.fragment.app.Fragment
import de.schildbach.wallet_test.databinding.FragmentAcceptInviteBinding

class AcceptInviteFragment : Fragment() {

    companion object {
        private const val ARG_TITLE = "title"
        private const val ARG_SUBTITLE = "subtitle"
        private const val ARG_IMAGE = "image"

        fun newInstance(@StringRes title: Int, @StringRes subTitle: Int,
                        @DrawableRes image: Int): AcceptInviteFragment {
            val fragment = AcceptInviteFragment()

            val args = Bundle()
            args.putInt(ARG_TITLE, title)
            args.putInt(ARG_SUBTITLE, subTitle)
            args.putInt(ARG_IMAGE, image)

            fragment.arguments = args

            return fragment
        }
    }

    private var _binding: FragmentAcceptInviteBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentAcceptInviteBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.title.text = getString(requireArguments().getInt(ARG_TITLE))
        binding.message.text = getString(requireArguments().getInt(ARG_SUBTITLE))
        binding.image.setImageResource(requireArguments().getInt(ARG_IMAGE))
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}