package org.dash.wallet.integrations.maya.ui.convert_currency.model

enum class SwapValueErrorType(var amount: String? = null) {
    LessThanMin,
    MoreThanMax,
    NotEnoughBalance,
    UnAuthorizedValue,
    SendingConditionsUnmet,
    ExchangeRateMissing,
    NOError
}
