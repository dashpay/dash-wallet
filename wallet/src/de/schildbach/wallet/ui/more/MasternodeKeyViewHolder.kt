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

import androidx.recyclerview.widget.RecyclerView
import de.schildbach.wallet.Constants
import de.schildbach.wallet_test.R
import de.schildbach.wallet_test.databinding.MasternodeKeyRowBinding
import org.bitcoinj.core.Address
import org.bitcoinj.core.Utils
import org.bitcoinj.crypto.IDeterministicKey
import org.bitcoinj.crypto.IKey
import org.bitcoinj.wallet.authentication.AuthenticationKeyStatus
import org.bitcoinj.wallet.authentication.AuthenticationKeyUsage

class MasternodeKeyViewHolder(val binding: MasternodeKeyRowBinding) : RecyclerView.ViewHolder(binding.root) {
    fun bind(masternodeKey: IKey, usage: AuthenticationKeyUsage?, clickListener: (String) -> Unit) {
        binding.apply {
            val index = if (masternodeKey is IDeterministicKey) {
                masternodeKey.path.last()?.num() ?: 0
            } else {
                // this is for keys that were added by the user -- there is no index for them
                masternodeKey.pubKeyHash[0].toInt()
            }
            keypairIndex.text = itemView.context.getString(R.string.masternode_key_pair_index, index)
            keypairUsage.setText(
                when (usage?.status) {
                    AuthenticationKeyStatus.CURRENT -> R.string.masternode_key_used
                    AuthenticationKeyStatus.REVOKED -> R.string.masternode_key_revoked
                    AuthenticationKeyStatus.PREVIOUS -> R.string.masternode_key_not_used
                    AuthenticationKeyStatus.UNKNOWN,
                    AuthenticationKeyStatus.NEVER,
                    null,
                    -> R.string.masternode_key_not_used
                },
            )
            // set all of the key serializations
            address.text = Address.fromKey(Constants.NETWORK_PARAMETERS, masternodeKey).toBase58()
            keyId.text = Utils.HEX.encode(masternodeKey.pubKeyHash)
            publicKey.text = Utils.HEX.encode(masternodeKey.pubKey)
            privateKeyHex.text = "encrypted" // Utils.HEX.encode(masternodeKey.privKeyBytes)
            privateKeyWif.text = "encrypted" // masternodeKey.getPrivateKeyAsWiF(Constants.NETWORK_PARAMETERS)

            // set onClickListeners
            addressContainer.setOnClickListener {
                clickListener.invoke(address.text.toString())
            }
            keyIdContainer.setOnClickListener {
                clickListener.invoke(keyId.text.toString())
            }
            publicKeyContainer.setOnClickListener {
                clickListener.invoke(publicKey.text.toString())
            }
            privateKeyHexContainer.setOnClickListener {
                clickListener.invoke(privateKeyHex.text.toString())
            }
            privateKeyWifContainer.setOnClickListener {
                clickListener.invoke(privateKeyWif.text.toString())
            }
        }
    }
}
