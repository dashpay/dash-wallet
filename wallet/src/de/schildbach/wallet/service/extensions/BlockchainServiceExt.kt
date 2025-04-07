package de.schildbach.wallet.service.extensions

import android.content.Intent
import de.schildbach.wallet.Constants
import de.schildbach.wallet.service.BlockchainServiceImpl
import de.schildbach.wallet.ui.staking.StakingActivity
import org.bitcoinj.core.Address
import org.dash.wallet.integrations.crowdnode.api.CrowdNodeAPIConfirmationHandler

fun BlockchainServiceImpl.registerCrowdNodeConfirmedAddressFilter(): CrowdNodeAPIConfirmationHandler? {
    val apiAddressStr = config.crowdNodeAccountAddress
    val primaryAddressStr = config.crowdNodePrimaryAddress

    return if (apiAddressStr.isNotEmpty() && primaryAddressStr.isNotEmpty()) {
        val apiAddress = Address.fromBase58(
            Constants.NETWORK_PARAMETERS,
            apiAddressStr
        )
        val primaryAddress = Address.fromBase58(
            Constants.NETWORK_PARAMETERS,
            primaryAddressStr
        )
        CrowdNodeAPIConfirmationHandler(
            apiAddress,
            primaryAddress,
            crowdNodeBlockchainApi,
            notificationService,
            crowdNodeConfig,
            resources,
            Intent(this, StakingActivity::class.java)
        )
    } else {
        null
    }
}