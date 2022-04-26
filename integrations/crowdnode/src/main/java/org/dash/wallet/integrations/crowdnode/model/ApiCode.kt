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

package org.dash.wallet.integrations.crowdnode.model

enum class ApiCode(val code: Long, val isRequest: Boolean = false) {
    PleaseAcceptTerms(2, false),
    WelcomeToApi(4, false),
    DepositReceived(8, false),
    WithdrawalQueue(16, false),
    WithdrawAll(1000, true),
    SignUp(131072, true),
    AcceptTerms(65536, true),
    MaxCode(SignUp.code, SignUp.isRequest)
}