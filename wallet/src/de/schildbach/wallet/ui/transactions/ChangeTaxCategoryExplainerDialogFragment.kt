/*
 * Copyright (c) 2022. Dash Core Group.
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

package de.schildbach.wallet.ui.transactions

import android.os.Bundle
import android.view.View
import dagger.hilt.android.AndroidEntryPoint
import de.schildbach.wallet_test.R
import de.schildbach.wallet_test.databinding.DialogChangeTaxCategoryExplainerBinding
import de.schildbach.wallet_test.databinding.TransactionResultContentBinding
import org.bitcoinj.core.Sha256Hash
import org.dash.wallet.common.Configuration
import de.schildbach.wallet.data.WalletData
import org.dash.wallet.common.data.entity.TransactionMetadata
import org.dash.wallet.common.transactions.TransactionCategory
import de.schildbach.wallet.transactions.TransactionUtils.isEntirelySelf
import org.dash.wallet.common.ui.dialogs.OffsetDialogFragment
import org.dash.wallet.common.ui.viewBinding
import javax.inject.Inject
import de.schildbach.wallet.util.format
import de.schildbach.wallet.util.setAmount
import de.schildbach.wallet.util.setFiatAmount
import de.schildbach.wallet.util.toDashjFiat
import de.schildbach.wallet.util.toDashjCoin
import de.schildbach.wallet.util.toNeutralCoin
import de.schildbach.wallet.util.toNeutralFiat
import de.schildbach.wallet.util.toTxId
import de.schildbach.wallet.util.toSha256Hash
import de.schildbach.wallet.transactions.fromTransaction

@AndroidEntryPoint
class ChangeTaxCategoryExplainerDialogFragment : OffsetDialogFragment(R.layout.dialog_change_tax_category_explainer) {

    private val binding by viewBinding(DialogChangeTaxCategoryExplainerBinding::bind)
    private val exampleTxId by lazy { arguments?.get(TX_ID) as? Sha256Hash }

    @Inject
    lateinit var walletData: WalletData
    @Inject
    lateinit var config: Configuration

    companion object {

        const val TX_ID = "tx_id"

        @JvmStatic
        fun newInstance(exampleTxId: Sha256Hash?): ChangeTaxCategoryExplainerDialogFragment {
            val fragment = ChangeTaxCategoryExplainerDialogFragment()
            val args = Bundle()
            args.putSerializable(TX_ID, exampleTxId)
            fragment.arguments = args
            return fragment
        }
    }

    override val forceExpand = true

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.apply {
            collapseButton.setOnClickListener {
                dismissAllowingStateLoss()
            }

            walletData.wallet?.let { wallet ->
                val tx = walletData.wallet!!.getTransaction(exampleTxId)
                val contentBinding = TransactionResultContentBinding.bind(transactionDetails)
                val transactionResultViewBinder = TransactionResultViewBinder(
                    wallet,
                    config.format.noCode(),
                    contentBinding
                )
                tx?.apply {
                    transactionResultViewBinder.bind(this, null)
                    transactionResultViewBinder.setTransactionMetadata(
                        TransactionMetadata(
                            tx.txId.toTxId(),
                            tx.updateTime.time,
                            tx.getValue(wallet).toNeutralCoin(),
                            TransactionCategory.fromTransaction(
                                tx.type,
                                tx.getValue(wallet),
                                isEntirelySelf(wallet)
                            )
                        )
                    )
                }
            }
        }
    }
}
