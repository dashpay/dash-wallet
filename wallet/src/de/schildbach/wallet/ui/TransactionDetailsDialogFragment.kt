package de.schildbach.wallet.ui

import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.Animation
import android.view.animation.AnimationUtils
import androidx.fragment.app.DialogFragment
import de.schildbach.wallet.WalletApplication
import de.schildbach.wallet.util.WalletUtils
import de.schildbach.wallet_test.R
import kotlinx.android.synthetic.main.transaction_details_dialog.*
import kotlinx.android.synthetic.main.transaction_result_content.*
import org.bitcoinj.core.Sha256Hash
import org.bitcoinj.core.Transaction
import org.slf4j.LoggerFactory

/**
 * @author Samuel Barbosa
 */
class TransactionDetailsDialogFragment : DialogFragment() {

    private val log = LoggerFactory.getLogger(javaClass.simpleName)
    private val txId by lazy { arguments?.get(TX_ID) as Sha256Hash }
    private var tx: Transaction? = null
    private val wallet by lazy { WalletApplication.getInstance().wallet }

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

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        tx = wallet.getTransaction(txId)
        val transactionResultViewBinder = TransactionResultViewBinder(transaction_result_container)
        if (tx != null) {
            transactionResultViewBinder.bind(tx!!)
        } else {
            log.error("Transaction not found. TxId:", txId)
            dismiss()
            return
        }
        view_on_explorer.setOnClickListener { viewOnBlockExplorer() }
        transaction_close_btn.setOnClickListener { dismissAnimation() }
        showAnimation()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setStyle(STYLE_NORMAL, android.R.style.Theme_Translucent_NoTitleBar)
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

    private fun viewOnBlockExplorer() {
        if (tx != null) {
            WalletUtils.viewOnBlockExplorer(activity, tx!!.purpose, tx!!.txId.toString())
        }
    }

}