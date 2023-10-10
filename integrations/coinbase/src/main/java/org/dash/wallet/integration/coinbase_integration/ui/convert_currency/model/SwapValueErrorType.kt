package org.dash.wallet.integration.coinbase_integration.ui.convert_currency.model

enum class SwapValueErrorType(var amount: String? = null) {
    LessThanMin,
    MoreThanMax,
    NotEnoughBalance,
    UnAuthorizedValue,
    SendingConditionsUnmet,
    ExchangeRateMissing,
    NOError
}
