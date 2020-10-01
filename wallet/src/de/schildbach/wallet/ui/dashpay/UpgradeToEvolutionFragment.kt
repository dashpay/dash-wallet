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

import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.fragment.app.Fragment
import de.schildbach.wallet.ui.CreateUsernameActivity
import de.schildbach.wallet_test.R
import kotlinx.android.synthetic.main.fragment_upgrade_to_evolution.*

class UpgradeToEvolutionFragment : Fragment(R.layout.fragment_upgrade_to_evolution) {

    companion object {
        private const val EXTRA_IS_READY_TO_UPGRADE = "is_ready_to_upgrade"

        @JvmStatic
        fun newInstance(readyToUpgrade: Boolean = false): UpgradeToEvolutionFragment {
            val args = Bundle()
            args.putBoolean(EXTRA_IS_READY_TO_UPGRADE, readyToUpgrade)

            val instance = UpgradeToEvolutionFragment()
            instance.arguments = args

            return instance
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        upgradeBtn.isEnabled = requireArguments().getBoolean(EXTRA_IS_READY_TO_UPGRADE)
        upgradeBtn.setOnClickListener {
            requireActivity().supportFragmentManager.popBackStack()
            startActivity(Intent(requireActivity(), CreateUsernameActivity::class.java))
        }
    }

}
