/*
 * Copyright 2023 Dash Core Group.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package de.schildbach.wallet.ui.more

import android.content.ClipData
import android.content.ClipboardManager
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import de.schildbach.wallet.Constants
import de.schildbach.wallet.security.SecurityFunctions
import de.schildbach.wallet.security.SecurityGuard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.bitcoinj.crypto.IKey
import org.bitcoinj.wallet.AuthenticationKeyChain
import org.bitcoinj.wallet.authentication.AuthenticationGroupExtension
import org.bitcoinj.wallet.authentication.AuthenticationKeyUsage
import org.dash.wallet.common.WalletDataProvider
import java.util.*
import javax.inject.Inject

@HiltViewModel
class MasternodeKeysViewModel @Inject constructor(
    private val walletData: WalletDataProvider,
    private val clipboardManager: ClipboardManager,
    private val securityFunctions: SecurityFunctions
) : ViewModel() {

    val authenticationGroup: AuthenticationGroupExtension = walletData.wallet!!.addOrGetExistingExtension(AuthenticationGroupExtension(walletData.wallet)) as AuthenticationGroupExtension

    fun hasMasternodeKeys(): Boolean {
        return authenticationGroup.hasKeyChains()
    }

    fun getKeyChain(type: MasternodeKeyType): AuthenticationKeyChain {
        return when (type) {
            MasternodeKeyType.OWNER -> authenticationGroup.getKeyChain(AuthenticationKeyChain.KeyChainType.MASTERNODE_OWNER)
            MasternodeKeyType.VOTING -> authenticationGroup.getKeyChain(AuthenticationKeyChain.KeyChainType.MASTERNODE_VOTING)
            MasternodeKeyType.OPERATOR -> authenticationGroup.getKeyChain(AuthenticationKeyChain.KeyChainType.MASTERNODE_OPERATOR)
            MasternodeKeyType.PLATFORM -> authenticationGroup.getKeyChain(AuthenticationKeyChain.KeyChainType.MASTERNODE_PLATFORM_OPERATOR)
        }
    }

    fun getKey(type: MasternodeKeyType, index: Int): IKey {
        val keyChain = getKeyChain(type)
        // this may crash on Platform keys
        val key = keyChain.getKey(index, keyChain.hasHardenedKeysOnly())

        val securityGuard = SecurityGuard()
        val password = securityGuard.retrievePassword()
        val encryptionKey = securityFunctions.deriveKey(walletData.wallet!!, password)

        return key.decrypt(walletData.wallet!!.keyCrypter, encryptionKey)
    }

    fun addKeyChains(password: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val securityGuard = SecurityGuard()
            val password = securityGuard.retrievePassword()
            val encryptionKey = securityFunctions.deriveKey(walletData.wallet!!, password)
            // val seed = walletData.wallet!!.keyChainSeed.decrypt(walletData.wallet!!.keyCrypter, "", encryptionKey)
            authenticationGroup.addEncryptedKeyChains(
                Constants.NETWORK_PARAMETERS,
                walletData.wallet!!.keyChainSeed,
                encryptionKey,
                EnumSet.of(
                    AuthenticationKeyChain.KeyChainType.MASTERNODE_OWNER,
                    AuthenticationKeyChain.KeyChainType.MASTERNODE_VOTING,
                    AuthenticationKeyChain.KeyChainType.MASTERNODE_OPERATOR,
                    AuthenticationKeyChain.KeyChainType.MASTERNODE_PLATFORM_OPERATOR,
                ),
            )
            //
            authenticationGroup.freshKey(AuthenticationKeyChain.KeyChainType.MASTERNODE_OWNER)
            authenticationGroup.freshKey(AuthenticationKeyChain.KeyChainType.MASTERNODE_VOTING)
            authenticationGroup.freshKey(AuthenticationKeyChain.KeyChainType.MASTERNODE_OPERATOR)
            authenticationGroup.freshKey(AuthenticationKeyChain.KeyChainType.MASTERNODE_PLATFORM_OPERATOR)

            // need to save wallet
        }
    }

    fun copyToClipboard(text: String) {
        clipboardManager.setPrimaryClip(
            ClipData.newPlainText(
                "Dash Wallet masternode key",
                text,
            ),
        )
    }

    fun getKeyUsage(): Map<IKey, AuthenticationKeyUsage> {
        return authenticationGroup.keyUsage
    }
}
