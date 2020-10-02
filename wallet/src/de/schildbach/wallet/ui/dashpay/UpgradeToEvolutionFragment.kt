/*
 * Copyright 2020 Dash Core Group.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package de.schildbach.wallet.ui.dashpay

import android.os.Bundle
import android.view.View
import androidx.fragment.app.Fragment
import androidx.lifecycle.Observer
import androidx.lifecycle.ViewModelProvider
import de.schildbach.wallet.ui.MainActivityViewModel
import de.schildbach.wallet_test.R
import kotlinx.android.synthetic.main.fragment_upgrade_to_evolution.*

class UpgradeToEvolutionFragment : Fragment(R.layout.fragment_upgrade_to_evolution) {

    companion object {
        @JvmStatic
        fun newInstance(): UpgradeToEvolutionFragment {
            return UpgradeToEvolutionFragment()
        }
    }

    private lateinit var mainActivityViewModel: MainActivityViewModel

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        upgradeBtn.setOnClickListener {
            if (requireActivity() is OnUpgradeBtnClicked) {
                (requireActivity() as OnUpgradeBtnClicked).onUpgradeBtnClicked()
            }
        }
    }

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        super.onActivityCreated(savedInstanceState)
        initViewModel()
    }

    override fun onResume() {
        super.onResume()
        upgradeBtn.isEnabled = mainActivityViewModel.isAbleToCreateIdentity
    }

    private fun initViewModel() {
        mainActivityViewModel = ViewModelProvider(requireActivity())[MainActivityViewModel::class.java]
        mainActivityViewModel.isAbleToCreateIdentityData.observe(viewLifecycleOwner, Observer {
            upgradeBtn.isEnabled = it
        })
    }

    interface OnUpgradeBtnClicked {
        fun onUpgradeBtnClicked()
    }

}
