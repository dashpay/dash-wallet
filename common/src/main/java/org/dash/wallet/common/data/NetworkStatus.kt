package org.dash.wallet.common.data

/**
 * Network status
 *
 * Describes the network connection status with the Dash Network
 */
enum class NetworkStatus {
    CONNECTING,
    CONNECTED,
    DISCONNECTING,
    DISCONNECTED,
    STOPPED,
    NOT_AVAILABLE
}