package de.schildbach.wallet.data

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
