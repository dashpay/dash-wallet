package de.schildbach.wallet.ui

import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.Animation
import android.view.animation.AnimationUtils
import androidx.core.content.ContextCompat
import androidx.fragment.app.DialogFragment
import de.schildbach.wallet.data.TransactionResult
import de.schildbach.wallet.util.WalletUtils
import de.schildbach.wallet_test.R
import kotlinx.android.synthetic.main.activity_successful_transaction.*
import kotlinx.android.synthetic.main.transaction_details_dialog.*

/**
 * @author Samuel Barbosa
 */
class TransactionDetailsDialogFragment : DialogFragment() {

    private val transactionResult by lazy { arguments?.get(TRANSACTION_RESULT) as TransactionResult }

    companion object {

        const val TRANSACTION_RESULT = "transaction_result"
        private const val TRANSACTION_DIRECTION = "transaction_direction"

        @JvmStatic
        fun newInstance(transactionResult: TransactionResult,
                        direction: WalletTransactionsFragment.Direction): TransactionDetailsDialogFragment {
            val fragment = TransactionDetailsDialogFragment()
            val args = Bundle()
            args.putSerializable(TRANSACTION_RESULT, transactionResult)
            args.putSerializable(TRANSACTION_DIRECTION, direction)
            fragment.arguments = args
            return fragment
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val transactionResultViewBinder = TransactionResultViewBinder(transaction_result_container)
        transactionResultViewBinder.bind(transactionResult)

        view_on_explorer.setOnClickListener { viewOnBlockExplorer() }
        transaction_close_btn.setOnClickListener { dismissAnimation() }
        showAnimation()
        setTransactionDirection()

        if (transactionResult.feeAmount == null) {
            hideFeeRow()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setStyle(STYLE_NO_FRAME, R.style.FullScreenDialog)
    }

    override fun onStart() {
        super.onStart()
        val width = ViewGroup.LayoutParams.MATCH_PARENT
        val height = ViewGroup.LayoutParams.MATCH_PARENT
        dialog?.window?.setLayout(width, height)
        dialog?.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        return inflater.inflate(R.layout.transaction_details_dialog, container, false)
    }

    private fun showAnimation() {
        val containerAnimation = AnimationUtils.loadAnimation(activity, R.anim.fade_in)
        transaction_details_dialog_container.startAnimation(containerAnimation)
        val contentAnimation = AnimationUtils.loadAnimation(activity, R.anim.slide_in_bottom)
        transaction_details_dialog_content_container.postDelayed({
            transaction_details_dialog_content_container.visibility = View.VISIBLE
            transaction_details_dialog_content_container.startAnimation(contentAnimation)
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

    private fun setTransactionDirection() {
        var color: Int
        when (arguments?.get(TRANSACTION_DIRECTION)
                as WalletTransactionsFragment.Direction) {
            WalletTransactionsFragment.Direction.SENT -> {
                check_icon.setImageResource(R.drawable.ic_transaction_sent)
                transaction_title.text = getText(R.string.transaction_details_amount_sent)
                transaction_amount_signal.text = "-"
                color = ContextCompat.getColor(context!!, android.R.color.black)
            }
            WalletTransactionsFragment.Direction.RECEIVED -> {
                check_icon.setImageResource(R.drawable.ic_transaction_received)
                transaction_title.text = getText(R.string.transaction_details_amount_received)
                transaction_amount_signal.text = "+"
                color = ContextCompat.getColor(context!!, R.color.colorPrimary)
                hideFeeRow()
            }
        }

        transaction_amount_signal.setTextColor(color)
        dash_amount_symbol.setColorFilter(color)
        dash_amount.setTextColor(color)

        check_icon.visibility = View.VISIBLE
        transaction_amount_signal.visibility = View.VISIBLE
    }

    private fun hideFeeRow() {
        fee_dash_icon.visibility = View.GONE
        network_fee_label.visibility = View.GONE
        transaction_fee.visibility = View.GONE
        separator2.visibility = View.GONE
    }

    private fun viewOnBlockExplorer() {
        WalletUtils.viewOnBlockExplorer(activity, transactionResult.purpose,
                transactionResult.transactionHash)
    }

}