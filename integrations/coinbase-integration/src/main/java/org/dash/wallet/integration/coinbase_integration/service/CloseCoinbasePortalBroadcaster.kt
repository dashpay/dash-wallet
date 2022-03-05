package org.dash.wallet.integration.coinbase_integration.service

import org.dash.wallet.common.data.SingleLiveEvent

class CloseCoinbasePortalBroadcaster {
    private val _closeCoinbasePortal = SingleLiveEvent<Unit>()
    val closeCoinbasePortal
        get() = _closeCoinbasePortal

    fun dispatchCall(){
        closeCoinbasePortal.postCall()
    }
}