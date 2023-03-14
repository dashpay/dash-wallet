/*
 * Copyright 2022 Dash Core Group.
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

package org.dash.wallet.integrations.crowdnode.ui.dialogs

import android.os.Bundle
import android.view.View
import org.bitcoinj.core.Address
import org.bitcoinj.core.Coin
import org.bitcoinj.uri.BitcoinURI
import org.dash.wallet.common.ui.dialogs.OffsetDialogFragment
import org.dash.wallet.common.ui.viewBinding
import org.dash.wallet.common.util.Qr
import org.dash.wallet.integrations.crowdnode.R
import org.dash.wallet.integrations.crowdnode.databinding.DialogQrBinding

class QRDialog(
    private val address: Address,
    private val amount: Coin
) : OffsetDialogFragment(R.layout.dialog_qr) {
    private val binding by viewBinding(DialogQrBinding::bind)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.subtitle.text = getString(
            R.string.qr_contains_request,
            amount.toFriendlyString()
        )

        val paymentRequestUri = BitcoinURI.convertToBitcoinURI(address, amount, "", "")
        val qrCodeBitmap = Qr.themeAwareDrawable(paymentRequestUri, resources)
        binding.qrPreview.setImageDrawable(qrCodeBitmap)
    }
}
