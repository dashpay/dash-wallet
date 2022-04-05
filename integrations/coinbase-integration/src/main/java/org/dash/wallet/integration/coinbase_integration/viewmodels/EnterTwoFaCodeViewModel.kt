package org.dash.wallet.integration.coinbase_integration.viewmodels

import androidx.lifecycle.ViewModel

class EnterTwoFaCodeViewModel : ViewModel() {
    // TODO: Implement the ViewModel

    fun isTwoFaCodeInputEmpty(inputFieldValue: String) = inputFieldValue.isEmpty()
}