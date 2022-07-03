package de.schildbach.wallet.ui

import android.content.DialogInterface
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.Animation
import android.view.animation.AnimationUtils
import androidx.fragment.app.DialogFragment
import de.schildbach.wallet.AppDatabase
import de.schildbach.wallet.WalletApplication
import de.schildbach.wallet.data.DashPayProfile
import de.schildbach.wallet.ui.dashpay.PlatformRepo
import org.dash.wallet.common.UserInteractionAwareCallback
import de.schildbach.wallet.util.WalletUtils
import de.schildbach.wallet_test.R
import kotlinx.android.synthetic.main.activity_successful_transaction.*
import kotlinx.android.synthetic.main.transaction_details_dialog.*
import kotlinx.android.synthetic.main.transaction_result_content.*
import org.bitcoinj.core.Sha256Hash
import org.bitcoinj.core.Transaction
import org.dashj.platform.dashpay.BlockchainIdentity
import org.slf4j.LoggerFactory

/**
 * @author Samuel Barbosa
 */
class TransactionDetailsDialogFragment : DialogFragment() {

    private val log = LoggerFactory.getLogger(javaClass.simpleName)
    private val txId by lazy { arguments?.get(TX_ID) as Sha256Hash }
    private var transaction: Transaction? = null
    private val wallet by lazy { WalletApplication.getInstance().wallet }
    private lateinit var transactionResultViewBinder: TransactionResultViewBinder

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

        val tx = wallet!!.getTransaction(txId)

        if (tx != null) {
            val blockchainIdentity: BlockchainIdentity? = PlatformRepo.getInstance().getBlockchainIdentity()
            val userId = initializeIdentity(tx, blockchainIdentity)

            if (blockchainIdentity == null || userId == null) {
                finishInitialization(tx, null)
            }
        } else {
            log.error("Transaction not found. TxId:", txId)
            dismiss()
            return
        }
    }

    private fun finishInitialization(tx: Transaction, dashPayProfile: DashPayProfile?) {
        this.transaction = tx
        initiateTransactionBinder(tx, dashPayProfile)
        tx.confidence.addEventListener(transactionResultViewBinder)
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

    private fun initiateTransactionBinder(tx: Transaction, dashPayProfile: DashPayProfile?) {
        transactionResultViewBinder = TransactionResultViewBinder(transaction_result_container, dashPayProfile, true)
        transactionResultViewBinder.bind(tx)
        view_on_explorer.setOnClickListener { viewOnBlockExplorer() }
        transaction_close_btn.setOnClickListener { dismissAnimation() }
        dialog?.window!!.callback = UserInteractionAwareCallback(dialog?.window!!.callback, requireActivity())
        showAnimation()
    }

    private fun initializeIdentity(tx: Transaction, blockchainIdentity: BlockchainIdentity?): String? {
        var profile: DashPayProfile?
        var userId: String? = null

        if (blockchainIdentity != null) {
            userId = blockchainIdentity.getContactForTransaction(tx)
            if (userId != null) {
                AppDatabase.getAppDatabase().dashPayProfileDaoAsync().loadByUserIdDistinct(userId).observe(this) {
                    if (it != null) {
                        profile = it
                        finishInitialization(tx, profile)
                    }
                }
            }
        }

        return userId
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
                dismissAllowingStateLoss()
            }

            override fun onAnimationRepeat(animation: Animation?) {}

            override fun onAnimationStart(animation: Animation?) {}
        })
    }

    private fun viewOnBlockExplorer() {
        imitateUserInteraction()
        transaction?.let {
            WalletUtils.viewOnBlockExplorer(activity, it.purpose, it.txId.toString())
        }
    }


    override fun onDismiss(dialog: DialogInterface) {
        super.onDismiss(dialog)
        transaction?.confidence?.removeEventListener(transactionResultViewBinder)
    }

    private fun imitateUserInteraction() {
        requireActivity().onUserInteraction()
    }
}