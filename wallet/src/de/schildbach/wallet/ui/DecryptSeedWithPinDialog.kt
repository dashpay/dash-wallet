package de.schildbach.wallet.ui

import android.content.DialogInterface
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.FragmentActivity
import androidx.fragment.app.viewModels
import dagger.hilt.android.AndroidEntryPoint
import de.schildbach.wallet.livedata.Status
import org.bitcoinj.wallet.DeterministicSeed

/**
 * @author:  Eric Britten
 *
 * DecryptSeedWithPinDialog uses DecryptSeedSharedModel which is derived
 * from CheckPinShared model but does not call the onCorrectPinCallback
 * event
 */
@AndroidEntryPoint
class DecryptSeedWithPinDialog(
    private var onSuccessOrDismiss: ((DeterministicSeed?) -> Unit)?
) : CheckPinDialog() {

    companion object {
        private const val ARG_PIN_ONLY = "arg_pin_only"

        @JvmStatic
        fun show(activity: FragmentActivity, pinOnly: Boolean = false, onSuccessOrDismiss: (DeterministicSeed?) -> Unit) {
            val checkPinDialog = DecryptSeedWithPinDialog(onSuccessOrDismiss)
            val args = Bundle()
            args.putBoolean(ARG_PIN_ONLY, pinOnly)
            checkPinDialog.arguments = args
            checkPinDialog.show(activity.supportFragmentManager, "dss") // TODO
        }

        @JvmStatic
        fun show(activity: AppCompatActivity, onSuccessOrDismiss: (DeterministicSeed?) -> Unit) {
            show(activity, false, onSuccessOrDismiss)
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

    override fun onFingerprintSuccess(savedPass : String) {
        viewModel.checkPin(savedPass)
    }
}
