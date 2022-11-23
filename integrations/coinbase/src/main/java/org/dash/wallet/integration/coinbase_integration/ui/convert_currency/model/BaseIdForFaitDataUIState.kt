package org.dash.wallet.integration.coinbase_integration.ui.convert_currency.model

import org.dash.wallet.integration.coinbase_integration.model.BaseIdForUSDData

sealed class BaseIdForFaitDataUIState {
    data class Success(val baseIdForFaitDataList: List<BaseIdForUSDData>): BaseIdForFaitDataUIState()
    data class LoadingState(val isLoading:Boolean): BaseIdForFaitDataUIState()
    data class Error(val isError:Boolean): BaseIdForFaitDataUIState()
}