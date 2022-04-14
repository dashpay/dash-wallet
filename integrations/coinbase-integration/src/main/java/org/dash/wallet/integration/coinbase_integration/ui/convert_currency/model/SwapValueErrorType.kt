package org.dash.wallet.integration.coinbase_integration.ui.convert_currency.model

enum class SwapValueErrorType {
    LessThanMin,
    MoreThanMax,
    UnAuthorizedValue,
    NOError
}