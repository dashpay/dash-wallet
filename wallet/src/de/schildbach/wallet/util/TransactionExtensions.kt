package de.schildbach.wallet.util

import de.schildbach.wallet.Constants
import de.schildbach.wallet.WalletApplication
import org.bitcoinj.core.Address
import org.bitcoinj.core.Coin
import org.bitcoinj.core.Transaction
import org.bitcoinj.script.ScriptException
import java.util.ArrayList

fun Transaction.isOutgoing(): Boolean {
    return getValue(WalletApplication.getInstance().wallet).signum() < 0
}

val Transaction.value: Coin?
    get() = getValue(WalletApplication.getInstance().wallet)

val Transaction.isEntirelySelf: Boolean
    get() = WalletUtils.isEntirelySelf(this, WalletApplication.getInstance().wallet)

val Transaction.allOutputAddresses: List<Address>
    get() {
        val result:MutableList<Address> = arrayListOf()
        outputs.forEach {
            try {
                val script = it.scriptPubKey
                result.add(script.getToAddress(Constants.NETWORK_PARAMETERS, true))
            } catch (x: ScriptException) {
                // swallow
            }
        }
       return result
    }