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
import android.widget.Button
import de.schildbach.wallet_test.R

/**
 * @author Samuel Barbosa
 */
class VerifySeedSecureNowFragment : VerifySeedBaseFragment() {

    private val secureNowBtn: Button by lazy { view!!.findViewById<Button>(R.id.verify_secure_now_button) }
    private val skipBtn: Button by lazy { view!!.findViewById<Button>(R.id.verify_skip_button) }

    companion object {
        fun newInstance(): VerifySeedSecureNowFragment {
            return VerifySeedSecureNowFragment()
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        return inflater.inflate(R.layout.fragment_secure_wallet, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        secureNowBtn.setOnClickListener { (activity as VerifySeedActions).startSeedVerification() }
        skipBtn.setOnClickListener { (activity as VerifySeedActions).skipSeedVerification() }
    }

}