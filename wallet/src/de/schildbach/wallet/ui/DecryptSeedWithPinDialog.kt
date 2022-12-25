package de.schildbach.wallet.ui

import android.content.DialogInterface
import androidx.fragment.app.FragmentActivity
import androidx.fragment.app.viewModels
import dagger.hilt.android.AndroidEntryPoint
import de.schildbach.wallet.livedata.Status
import org.bitcoinj.wallet.DeterministicSeed

/**
 * @author:  Eric Britten
 *
 * DecryptSeedWithPinDialog is derived from CheckPinDialog
 * but uses its own onSuccessOrDismiss callback
 */
@AndroidEntryPoint
class DecryptSeedWithPinDialog(
    private var onSuccessOrDismiss: ((DeterministicSeed?) -> Unit)?
) : CheckPinDialog() {

    companion object {
        private val FRAGMENT_TAG = DecryptSeedWithPinDialog::class.java.simpleName

        @JvmStatic
        fun show(activity: FragmentActivity, onSuccessOrDismiss: (DeterministicSeed?) -> Unit) {
            val checkPinDialog = DecryptSeedWithPinDialog(onSuccessOrDismiss)
            checkPinDialog.show(activity.supportFragmentManager, FRAGMENT_TAG)
        }
    }

    override val viewModel by viewModels<DecryptSeedViewModel>()

    override fun initViewModel() {
        viewModel.decryptSeedLiveData.observe(viewLifecycleOwner) {
            when (it.status) {
                Status.ERROR -> {
                    if (viewModel.isLockedAfterAttempt(it.data!!.second!!)) {
                        restartService.performRestart(requireActivity(), true)
                        return@observe
                    }

                    if (viewModel.isWalletLocked) {
                        val message = viewModel.getLockedMessage(requireContext().resources)
                        showLockedAlert(requireActivity(), message)
                        dismiss()
                        return@observe
                    }
                    setState(State.INVALID_PIN)
                }
                Status.LOADING -> {
                    setState(State.DECRYPTING)
                }
                Status.SUCCESS -> {
                    dismiss(it.data!!.first!!)
                }
                else -> {
                    // ignore
                }
            }
        }
    }

    private fun dismiss(seed: DeterministicSeed) {
        if (viewModel.isWalletLocked) {
            return
        }

        onSuccessOrDismiss?.invoke(seed)
        onSuccessOrDismiss = null
        viewModel.resetFailedPinAttempts()
        dismiss()
    }

    override fun onDismiss(dialog: DialogInterface) {
        onSuccessOrDismiss?.invoke(null)
        onSuccessOrDismiss = null
        super.onDismiss(dialog)
    }
}
