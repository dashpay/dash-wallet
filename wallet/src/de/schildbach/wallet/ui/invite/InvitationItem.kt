package de.schildbach.wallet.ui.invite

import de.schildbach.wallet.data.Invitation
import de.schildbach.wallet.util.PlatformUtils
import de.schildbach.wallet.util.WalletUtils
import org.bitcoinj.core.Sha256Hash
import java.math.BigInteger

data class InvitationItem(val type: Int,
                          val invitation: Invitation? = null,
                          val data: Int = 0) {

    val id: Long by lazy {
        if (invitation != null) {
            PlatformUtils.longHashFromEncodedString(invitation.userId)
        } else {
            val bytes = Sha256Hash.ZERO_HASH.bytes
            bytes[0] = type.toByte()
            PlatformUtils.longHash(Sha256Hash.wrap(bytes))
        }
    }
}