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
package org.dash.wallet.integrations.maya.ui.convert_currency.model

import org.dash.wallet.common.ui.payment_method_picker.PaymentMethod

sealed class PaymentMethodsUiState {
    data class Success(val paymentMethodsList: List<PaymentMethod>) : PaymentMethodsUiState()
    data class Error(val isError: Boolean) : PaymentMethodsUiState()
    data class LoadingState(val isLoading: Boolean) : PaymentMethodsUiState()
}
