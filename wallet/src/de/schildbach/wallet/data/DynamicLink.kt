package de.schildbach.wallet.data

/**
 * @param shortLink the link handed to the user (shared, copied, put in the QR code). For AppsFlyer
 *   this is the long OneLink URL carrying `af_dp`, because the iOS wallet can only open those.
 * @param link the raw link returned by the link service (a short OneLink when AppsFlyer accepted
 *   the request); kept for reference and analytics only.
 * @param appLink the `dashpay://invite?...` payload itself.
 */
data class DynamicLink(
    val shortLink: String,
    val link: String,
    val appLink: String,
    val service: String
) {
    companion object {
        const val AppsFlyer = "AppsFlyer"
    }
}
