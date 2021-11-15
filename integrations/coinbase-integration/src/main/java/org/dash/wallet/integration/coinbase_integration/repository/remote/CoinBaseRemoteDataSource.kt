package org.dash.wallet.integration.coinbase_integration.repository.remote

import org.dash.wallet.integration.coinbase_integration.service.CoinBaseApi
import javax.inject.Inject

class CoinBaseRemoteDataSource @Inject constructor(
    private val coinBaseApi: CoinBaseApi
)
