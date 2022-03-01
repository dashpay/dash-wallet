package org.dash.wallet.integration.coinbase_integration.service

import org.dash.wallet.common.data.SingleLiveEvent

class CloseCoinbasePortalBroadcaster {
    val closeCoinbasePortal = SingleLiveEvent<Unit>()
}