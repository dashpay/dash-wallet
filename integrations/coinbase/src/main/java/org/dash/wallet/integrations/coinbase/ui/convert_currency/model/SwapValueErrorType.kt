package org.dash.wallet.integrations.coinbase.ui.convert_currency.model

enum class SwapValueErrorType(var amount: String? = null) {
    LessThanMin,
    MoreThanMax,
    NotEnoughBalance,
    UnAuthorizedValue,
    SendingConditionsUnmet,
    ExchangeRateMissing,
    NOError
}
