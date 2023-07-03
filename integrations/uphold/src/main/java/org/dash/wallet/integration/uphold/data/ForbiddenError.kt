/*
 * Copyright 2022 Dash Core Group.
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

package org.dash.wallet.integration.uphold.data

import org.dash.wallet.integration.uphold.R

object ForbiddenError {
    val errorToMessageMap = mapOf(
        "user-must-submit-enhanced-due-diligence" to R.string.uphold_api_error_403_due_diligence,
        "user-must-submit-identity" to R.string.uphold_api_error_403_identity,
        "user-must-submit-proof-of-address" to R.string.uphold_api_error_403_proof_of_address
    )
}
