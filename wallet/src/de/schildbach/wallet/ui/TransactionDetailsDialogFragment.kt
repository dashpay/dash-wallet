package de.schildbach.wallet.ui

import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.util.DisplayMetrics
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.animation.Animation
import android.view.animation.AnimationUtils
import android.widget.RelativeLayout
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.viewModels
import dagger.hilt.android.AndroidEntryPoint
import de.schildbach.wallet.WalletApplication
import de.schildbach.wallet.util.WalletUtils
import de.schildbach.wallet_test.R
import kotlinx.android.synthetic.main.transaction_details_dialog.*
import kotlinx.android.synthetic.main.transaction_result_content.*
import kotlinx.coroutines.FlowPreview
import org.bitcoinj.core.Sha256Hash
import org.bitcoinj.core.Transaction
import org.slf4j.LoggerFactory

/**
 * @author Samuel Barbosa
 */
@AndroidEntryPoint
class TransactionDetailsDialogFragment : DialogFragment() {

    private val log = LoggerFactory.getLogger(javaClass.simpleName)
    private val txId by lazy { arguments?.get(TX_ID) as Sha256Hash }
    private var tx: Transaction? = null
    private val wallet by lazy { WalletApplication.getInstance().wallet }
    @OptIn(FlowPreview::class)
    private val viewModel: TransactionResultViewModel by viewModels()

    companion object {

        const val TX_ID = "tx_id"

        @JvmStatic
        fun newInstance(txId: Sha256Hash): TransactionDetailsDialogFragment {
            val fragment = TransactionDetailsDialogFragment()
            val args = Bundle()
            args.putSerializable(TX_ID, txId)
            fragment.arguments = args
            return fragment
        }
    }

    @OptIn(FlowPreview::class)
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        tx = wallet!!.getTransaction(txId)
        val transactionResultViewBinder = TransactionResultViewBinder(transaction_result_container)
        if (tx != null) {
            tx?.let {
                transaction_details_dialog_content_container.background = ContextCompat.getDrawable(requireContext(), R.drawable.rounded_corners_bgd_light_gray)
                transaction_details_dialog_content_container.updateLayoutParams<RelativeLayout.LayoutParams> {
                    topMargin = 30
                }
                transaction_close_btn.isVisible = false
                transactionResultViewBinder.bind(it)
                viewModel.setTransaction(it)
                viewModel.transactionMetadata.observe(this) { metadata ->
                    if(metadata != null && tx?.txId == metadata.txid) {
                        transactionResultViewBinder.setTransactionMetadata(metadata)
                    }
                }
            }

        } else {
            log.error("Transaction not found. TxId:", txId)
            dismiss()
            return
        }
        open_explorer_card.setOnClickListener { viewOnBlockExplorer() }
        transaction_close_btn.setOnClickListener { dismissAnimation() }
        close_btn.setOnClickListener { dismissAnimation() }
        report_issue_card.setOnClickListener {
            showReportIssue()
        }
        tax_category_layout.setOnClickListener {
            viewOnTaxCategory()
        }

        showAnimation()
    }

    private fun showReportIssue() {
        ReportIssueDialogBuilder.createReportIssueDialog(requireActivity(), WalletApplication.getInstance())
            .buildAlertDialog().show()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setStyle(STYLE_NO_FRAME, org.dash.wallet.common.R.style.FullScreenDialog)
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        dialog?.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            requestFeature(Window.FEATURE_NO_TITLE)
        }
        return inflater.inflate(R.layout.transaction_details_dialog, container, false)
    }

    private fun showAnimation() {
        val contentAnimation = AnimationUtils.loadAnimation(activity, R.anim.slide_in_bottom)
        transaction_details_dialog_container.postDelayed({
            val container = transaction_details_dialog_content_container
            container.translationY = container.measuredHeight.toFloat()
            container.visibility = View.VISIBLE
            container.startAnimation(contentAnimation)
        }, 150)
    }

    private fun dismissAnimation() {
        val contentAnimation = AnimationUtils.loadAnimation(activity, R.anim.slide_out_bottom)
        transaction_details_dialog_content_container.startAnimation(contentAnimation)
        val containerAnimation = AnimationUtils.loadAnimation(activity, R.anim.fade_out)
        transaction_details_dialog_content_container.postDelayed({
            transaction_details_dialog_container.startAnimation(containerAnimation)
        }, 150)
        containerAnimation.setAnimationListener(object : Animation.AnimationListener {
            override fun onAnimationEnd(animation: Animation?) {
                dismiss()
            }

            override fun onAnimationRepeat(animation: Animation?) {}

            override fun onAnimationStart(animation: Animation?) {}
        })
    }

    private fun viewOnBlockExplorer() {
        if (tx != null) {
            WalletUtils.viewOnBlockExplorer(activity, tx!!.purpose, tx!!.txId.toString())
        }
    }

    @OptIn(FlowPreview::class)
    private fun viewOnTaxCategory() {
        // this should eventually trigger the observer to update the view
        viewModel.toggleTaxCategory()
    }

}