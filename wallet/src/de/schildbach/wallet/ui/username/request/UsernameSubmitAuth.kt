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

package de.schildbach.wallet.ui.username.request

import androidx.fragment.app.Fragment
import org.dash.wallet.common.services.AuthenticationManager

/**
 * Authenticate, then submit the username request.
 *
 * EVERY submit path spends funds (an L1 asset lock, shielded notes, or
 * both), and [RequestUserNameViewModel.submit] hands the spend to
 * CreateIdentityService / the shielded pipeline, which sign WITHOUT any
 * further user interaction — so the PIN/biometric prompt here is the only
 * authentication the spend ever gets (observed live: a non-private
 * username registered with no auth at all). A cancelled prompt (null)
 * submits nothing.
 *
 * [approvedAmountDuffs] defaults to [RequestUserNameViewModel.approvedFundingAmountDuffs] —
 * the last confirm sheet's amount — rather than unbounded, so the callers
 * with no fresh confirm sheet to read from (the shared error dialog's
 * retry, the cancelled-verification shortcuts) still cap the spend at what
 * the user actually approved instead of silently funding a higher
 * fee resolved at spend time (MO-1069 review 5431682794). Nullable, not
 * defaulted to `Long.MAX_VALUE`: a missing approval must never be read as
 * unlimited — [RequestUserNameViewModel.submit] refuses to fund when it
 * actually needs one and none survived (MO-1069 review 5462459067).
 */
suspend fun authenticateThenSubmit(
    fragment: Fragment,
    authManager: AuthenticationManager,
    viewModel: RequestUserNameViewModel,
    approvedAmountDuffs: Long? = viewModel.approvedFundingAmountDuffs
) {
    val pin = authManager.authenticate(fragment.requireActivity())
    if (pin != null) {
        viewModel.submit(approvedAmountDuffs)
    }
}
