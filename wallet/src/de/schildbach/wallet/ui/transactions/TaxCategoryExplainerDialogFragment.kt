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
import androidx.fragment.app.FragmentActivity
import dagger.hilt.android.AndroidEntryPoint
import de.schildbach.wallet_test.R
import de.schildbach.wallet_test.databinding.DialogTaxCategoryExplainerBinding
import org.bitcoinj.core.Sha256Hash
import org.dash.wallet.common.ui.dialogs.OffsetDialogFragment
import org.dash.wallet.common.ui.viewBinding

@AndroidEntryPoint
class TaxCategoryExplainerDialogFragment : OffsetDialogFragment(R.layout.dialog_tax_category_explainer) {

    override val forceExpand = true
    private val binding by viewBinding(DialogTaxCategoryExplainerBinding::bind)

    var onClickListener: (() -> Unit)? = null
    private val exampleTxId by lazy { arguments?.get(TX_ID) as? Sha256Hash }

    companion object {

        const val TX_ID = "tx_id"

        @JvmStatic
        fun newInstance(exampleTxId: Sha256Hash): TaxCategoryExplainerDialogFragment {
            val fragment = TaxCategoryExplainerDialogFragment()
            val args = Bundle()
            args.putSerializable(TX_ID, exampleTxId)
            fragment.arguments = args
            return fragment
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.apply {
            incomeText.text = resources.getString(R.string.tax_category_income)
            expenseText.text = resources.getString(R.string.tax_category_expense)
            transferInText.text = resources.getString(R.string.tax_category_transfer_in)
            transferOutText.text = resources.getString(R.string.tax_category_transfer_out)

            collapseButton.setOnClickListener {
                dismissAllowingStateLoss()
            }
            secondCloseBtn.setOnClickListener {
                onClickListener?.invoke()
                dismissAllowingStateLoss()
            }
            whereButton.setOnClickListener {
                val dialog = ChangeTaxCategoryExplainerDialogFragment.newInstance(exampleTxId)
                dialog.show(requireActivity())
            }
        }
    }

    fun show(activity: FragmentActivity, onClickListener: () -> Unit) {
        this.onClickListener = onClickListener
        super.show(activity)
    }
}