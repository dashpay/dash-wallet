/*
 * Copyright 2019 Dash Core Group
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
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.widget.Toolbar
import de.schildbach.wallet_test.R
import kotlinx.android.synthetic.main.verify_seed_write_down.*

/**
 * @author Samuel Barbosa
 */
class VerifySeedWriteDownFragment : VerifySeedBaseFragment() {

    private val recoverySeedTextView by lazy { recovery_seed }
    private val confirmButton by lazy { confirm_written_down_btn }
    private val explanationBtn by lazy { explanation_btn }

    companion object {
        fun newInstance(seed: Array<String>): VerifySeedWriteDownFragment {
            val fragment = VerifySeedWriteDownFragment()
            val args = Bundle()
            args.putStringArray("seed", seed)
            fragment.arguments = args
            return fragment
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        return inflater.inflate(R.layout.verify_seed_write_down, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        view.findViewById<Toolbar>(R.id.toolbar).title = getString(R.string.view_seed_title)

        arguments?.getStringArray("seed")?.let { seed ->
            val sb = StringBuilder(12)
            seed.forEach {
                sb.append("$it ")
            }
            recoverySeedTextView.text = sb.toString().trim()
        }
        confirmButton.setOnClickListener {
            if (context is VerifySeedActions) {
                (context as VerifySeedActions).onVerifyWriteDown()
            }
        }

        explanationBtn.setOnClickListener {
            VerifySeedWarningDialog().show(parentFragmentManager, "verify_seed_warning")
        }
    }
}