package org.dash.wallet.common.data

object ServiceName {
    const val CrowdNode = "crowdnode"
    const val Uphold = "uphold"
    const val Coinbase = "coinbase"
    const val CTXSpend = "ctxspend"
    const val PiggyCards = "piggycards"
    const val Unknown = "unknown"

    fun isDashSpend(serviceName: String?) = serviceName == CTXSpend || serviceName == PiggyCards
}
