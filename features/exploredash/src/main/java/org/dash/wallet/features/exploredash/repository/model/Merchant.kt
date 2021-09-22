package org.dash.wallet.features.exploredash.repository.model

data class Merchant(
    val id: Long? = -1,
    val active: Boolean? = true,
    val name: String? = "",
    val pluscode: String? = "",
    val adddate: String? = "",
    val updatedate: String? = "",
    val address1: String? = "",
    val address2: String? = "",
    val address3: String? = "",
    val address4: String? = "",
    var latitude: Double? = 0.0,
    var longitude: Double? = 0.0,
    val website: String? = ""
)