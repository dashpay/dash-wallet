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

package de.schildbach.wallet.ui.more.masternode_keys

import androidx.core.view.isVisible
import androidx.recyclerview.widget.RecyclerView
import de.schildbach.wallet.Constants
import de.schildbach.wallet_test.R
import de.schildbach.wallet_test.databinding.MasternodeKeyRowBinding
import org.bitcoinj.core.Address
import org.bitcoinj.core.Utils
import org.bitcoinj.crypto.BLSPublicKey
import org.bitcoinj.crypto.IDeterministicKey
import org.bitcoinj.crypto.KeyType
import org.bitcoinj.wallet.authentication.AuthenticationKeyStatus
import org.bitcoinj.wallet.authentication.AuthenticationKeyUsage

class MasternodeKeyViewHolder(val binding: MasternodeKeyRowBinding) : RecyclerView.ViewHolder(binding.root) {
    fun bind(masternodeKeyInfo: MasternodeKeyInfo, usage: AuthenticationKeyUsage?, clickListener: (String) -> Unit) {
        binding.apply {
            val index = if (masternodeKeyInfo.masternodeKey is IDeterministicKey) {
                masternodeKeyInfo.masternodeKey.path.last()?.num() ?: 0
            } else {
                // this is for keys that were added by the user -- there is no index for them
                masternodeKeyInfo.masternodeKey.pubKeyHash[0].toInt()
            }
            keypairIndex.text = itemView.context.getString(R.string.masternode_key_pair_index, index)
            keypairUsage.text = when (usage?.status) {
                AuthenticationKeyStatus.CURRENT -> {
                    val ipAddress = if (usage.address != null) {
                        usage.address?.addr.toString().removePrefix("/")
                    } else {
                        itemView.context.getString(R.string.masternode_key_ip_address_unknown)
                    }
                    itemView.context.getString(R.string.masternode_key_used, ipAddress)
                }
                AuthenticationKeyStatus.REVOKED -> itemView.context.getString(R.string.masternode_key_revoked)
                AuthenticationKeyStatus.PREVIOUS,
                AuthenticationKeyStatus.UNKNOWN,
                AuthenticationKeyStatus.NEVER,
                null,
                -> itemView.context.getString(R.string.masternode_key_not_used)
            }
            // set all of the key serializations
            address.text = Address.fromKey(Constants.NETWORK_PARAMETERS, masternodeKeyInfo.masternodeKey).toBase58()
            keyId.text = Utils.HEX.encode(masternodeKeyInfo.masternodeKey.pubKeyHash)
            publicKey.text = Utils.HEX.encode(masternodeKeyInfo.masternodeKey.pubKey)
            initPrivateKeys(this, masternodeKeyInfo)

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
            publicKeyLegacyContainer.setOnClickListener {
                clickListener.invoke(publicKeyLegacy.text.toString())
            }
            privateKeyHexContainer.setOnClickListener {
                clickListener.invoke(privateKeyHex.text.toString())
            }
            privateKeyWifContainer.setOnClickListener {
                clickListener.invoke(privateKeyWif.text.toString())
            }
            privatePublicKeyBase64.setOnClickListener {
                clickListener.invoke(privatePublicKeyBase64.text.toString())
            }
            // define visibility
            val keyType = masternodeKeyInfo.masternodeKey.keyFactory.keyType
            addressContainer.isVisible = keyType == KeyType.ECDSA
            keyIdContainer.isVisible = keyType == KeyType.EdDSA
            privateKeyHexContainer.isVisible = keyType != KeyType.EdDSA
            privateKeyWifContainer.isVisible = keyType == KeyType.ECDSA
            publicKeyContainer.isVisible = keyType == KeyType.BLS
            publicKeyLegacyContainer.isVisible = keyType == KeyType.BLS
            privatePublicKeyBase64Container.isVisible = keyType == KeyType.EdDSA
            if (keyType == KeyType.BLS) {
                val blsPublicKey = (masternodeKeyInfo.masternodeKey.pubKeyObject as BLSPublicKey)
                publicKeyLegacy.text = blsPublicKey.toStringHex(true)
                publicKey.text = blsPublicKey.toStringHex(false)
            }
        }
    }

    private fun initPrivateKeys(binding: MasternodeKeyRowBinding, masternodeKeyInfo: MasternodeKeyInfo) {
        if (masternodeKeyInfo.privateKeyHex == null) {
            binding.privateKeyHexLoadingIndicator.isVisible = true
            binding.privateKeyWifLoadingIndicator.isVisible = true
            binding.privatePublicKeyBase64LoadingIndicator.isVisible = true
            binding.privateKeyHex.isVisible = false
            binding.privateKeyWif.isVisible = false
            binding.privatePublicKeyBase64.isVisible = false
        } else {
            binding.privateKeyHexLoadingIndicator.isVisible = false
            binding.privateKeyWifLoadingIndicator.isVisible = false
            binding.privatePublicKeyBase64LoadingIndicator.isVisible = false
            binding.privateKeyHex.isVisible = true
            binding.privateKeyWif.isVisible = true
            binding.privatePublicKeyBase64.isVisible = true
            binding.privateKeyHex.text = masternodeKeyInfo.privateKeyHex
            binding.privateKeyWif.text = masternodeKeyInfo.privateKeyWif
            binding.privatePublicKeyBase64.text = masternodeKeyInfo.privatePublicKeyBase64
        }
    }
}
