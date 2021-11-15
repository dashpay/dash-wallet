package org.dash.wallet.integration.coinbase_integration.repository

import org.dash.wallet.integration.coinbase_integration.repository.remote.CoinBaseRemoteDataSource

import javax.inject.Inject

class CoinBaseRepository @Inject constructor(
    private val coinBaseRemoteDataSource: CoinBaseRemoteDataSource
)
