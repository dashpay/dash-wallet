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
import android.view.View
import androidx.appcompat.widget.Toolbar
import androidx.fragment.app.Fragment
import de.schildbach.wallet_test.R
import de.schildbach.wallet_test.databinding.FragmentItsImportToSecureBinding
import org.dash.wallet.common.ui.viewBinding

/**
 * @author Samuel Barbosa
 */
class VerifySeedItIsImportantFragment : Fragment(R.layout.fragment_its_import_to_secure) {
    private val binding by viewBinding(FragmentItsImportToSecureBinding::bind)

    companion object {
        fun newInstance(): VerifySeedItIsImportantFragment {
            return VerifySeedItIsImportantFragment()
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        view.findViewById<Toolbar>(R.id.toolbar).title = getString(R.string.verify_backup_wallet)

        binding.showRecoveryPhraseButton.setOnClickListener {
            (activity as VerifySeedActions).showRecoveryPhrase()
        }
    }
}
