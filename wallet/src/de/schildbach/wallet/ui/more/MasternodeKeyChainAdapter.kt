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

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import de.schildbach.wallet_test.databinding.MasternodeKeyRowBinding
import org.bitcoinj.crypto.IKey
import org.bitcoinj.wallet.AuthenticationKeyChain
import org.bitcoinj.wallet.authentication.AuthenticationKeyUsage

class MasternodeKeyChainAdapter(
    val keyChain: AuthenticationKeyChain,
    val keyUsage: Map<IKey, AuthenticationKeyUsage>,
    private val clickListener: (String) -> Unit,
    private val getDecryptedKey: (IKey, Int) -> Unit,
) : RecyclerView.Adapter<MasternodeKeyViewHolder>() {

    val masternodeKeyInfo = arrayListOf<MasternodeKeyInfo>()

    init {
        for (i in 0..itemCount) {
            masternodeKeyInfo.add(MasternodeKeyInfo(keyChain.getKey(i, keyChain.hasHardenedKeysOnly()), null, null, null))
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MasternodeKeyViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        val binding = MasternodeKeyRowBinding.inflate(inflater, parent, false)
        return MasternodeKeyViewHolder(binding)
    }

    override fun onBindViewHolder(holder: MasternodeKeyViewHolder, position: Int) {
        val keyInfo = masternodeKeyInfo[position]
        holder.bind(keyInfo, keyUsage.get(keyInfo.masternodeKey), clickListener, getDecryptedKey, position)
    }

    override fun getItemCount(): Int {
        return keyChain.issuedKeyCount + 1
    }

    override fun getItemId(position: Int): Long {
        return keyChain.getKey(position).hashCode().toLong()
    }

    fun addKey(key: IKey) {
        notifyItemInserted(itemCount + 1)
    }
}
