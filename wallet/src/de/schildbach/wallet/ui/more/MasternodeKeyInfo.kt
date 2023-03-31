package de.schildbach.wallet.ui.more

import org.bitcoinj.crypto.IKey

data class MasternodeKeyInfo(
    val masternodeKey: IKey,
    val privateKeyHex: String? = null,
    val privateKeyWif: String? = null,
    val privatePublicKeyBase64: String? = null,
)
