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
import androidx.appcompat.widget.Toolbar
import de.schildbach.wallet_test.R

/**
 * @author Samuel Barbosa
 */
class VerifySeedItIsImportantFragment : VerifySeedBaseFragment() {

    private val showRecoveryPhraseBtn: Button by lazy {
        view!!.findViewById<Button>(R.id.verify_show_recovery_phrase_button)
    }

    companion object {
        fun newInstance(): VerifySeedItIsImportantFragment {
            return VerifySeedItIsImportantFragment()
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        return inflater.inflate(R.layout.fragment_its_import_to_secure, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        view.findViewById<Toolbar>(R.id.toolbar).title = getString(R.string.verify_backup_wallet)

        showRecoveryPhraseBtn.setOnClickListener {
            (activity as VerifySeedActions).showRecoveryPhrase()
        }
    }

}