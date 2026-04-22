/*
 * Copyright 2026 Dash Core Group.
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
package org.dash.wallet.common.ui.dialogs

import android.os.Bundle
import android.view.View
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import org.dash.wallet.common.R
import org.dash.wallet.common.databinding.DialogComposeContainerBinding
import org.dash.wallet.common.ui.viewBinding

open class ComposeBottomSheet(
    override val backgroundStyle: Int = R.style.SecondaryBackground,
    override val forceExpand: Boolean = false
) : OffsetDialogFragment(R.layout.dialog_compose_container) {
    private val binding by viewBinding(DialogComposeContainerBinding::bind)

    @Composable
    open fun Content() {}

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.composeContainer.setContent {
            Box(modifier = Modifier.navigationBarsPadding()) {
                Content()
            }
        }
    }
}