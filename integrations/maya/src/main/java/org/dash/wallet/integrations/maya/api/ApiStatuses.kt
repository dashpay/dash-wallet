package org.dash.wallet.integrations.maya.api

open class MayaException(message: String): Exception(message) {
    companion object {
        const val SWAP_ERROR = "deposit_error"
    }
}