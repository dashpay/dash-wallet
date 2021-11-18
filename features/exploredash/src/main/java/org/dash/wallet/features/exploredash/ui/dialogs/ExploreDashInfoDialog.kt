/*
 * Copyright 2021 Dash Core Group.
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

package org.dash.wallet.features.exploredash.ui.dialogs

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.constraintlayout.widget.ConstraintLayout
import org.dash.wallet.common.ui.OffsetDialogFragment
import org.dash.wallet.common.ui.viewBinding
import org.dash.wallet.common.util.dialogSafeNavigate
import org.dash.wallet.features.exploredash.R
import org.dash.wallet.features.exploredash.databinding.ExploreDashMainInfoBinding

class ExploreDashInfoDialog : OffsetDialogFragment<ConstraintLayout>(){

    private val binding by viewBinding(ExploreDashMainInfoBinding::bind)

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.explore_dash_main_info, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.learnMoreLabel.setOnClickListener {
            dialogSafeNavigate(ExploreDashInfoDialogDirections.infoToGiftCardDetail())
        }
        binding.exploreDashInfoContinueBtn.setOnClickListener {
            dismissAllowingStateLoss()
        }
    }
}