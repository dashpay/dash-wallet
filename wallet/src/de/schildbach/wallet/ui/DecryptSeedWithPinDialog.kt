package de.schildbach.wallet.ui

import android.content.DialogInterface
import androidx.fragment.app.FragmentActivity
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory

/**
 * @author:  Eric Britten
 *
 * DecryptSeedWithPinDialog is derived from CheckPinDialog
 * but uses its own onSuccessOrDismiss callback
 */
@AndroidEntryPoint
class DecryptSeedWithPinDialog(
    private var onSuccessOrDismiss: ((Array<String>) -> Unit)?
) : CheckPinDialog() {

    companion object {
        private val log = LoggerFactory.getLogger(DecryptSeedWithPinDialog::class.java)
        private val FRAGMENT_TAG = DecryptSeedWithPinDialog::class.java.simpleName

        @JvmStatic
        fun show(activity: FragmentActivity, onSuccessOrDismiss: (Array<String>) -> Unit) {
            val checkPinDialog = DecryptSeedWithPinDialog(onSuccessOrDismiss)
            checkPinDialog.show(activity.supportFragmentManager, FRAGMENT_TAG)
        }
    }

    override val viewModel by viewModels<DecryptSeedViewModel>()

    override fun checkPin(pin: String) {
        lifecycleScope.launch {
            setState(State.DECRYPTING)

            try {
                val seed = viewModel.decryptSeed(pin)
                dismiss(seed)
            } catch (ex: Exception) {
                log.error("Failed to decrypt seed", ex)

                if (viewModel.isLockedAfterAttempt(pin)) {
                    restartService.performRestart(requireActivity(), true)
                    return@launch
                }

                if (viewModel.isWalletLocked) {
                    val message = viewModel.getLockedMessage(requireContext().resources)
                    showLockedAlert(requireActivity(), message)
                    dismiss()
                    return@launch
                }

                setState(State.INVALID_PIN)
            }
        }
    }

    private fun dismiss(seed: Array<String>) {
        if (viewModel.isWalletLocked) {
            return
        }

        onSuccessOrDismiss?.invoke(seed)
        onSuccessOrDismiss = null
        viewModel.resetFailedPinAttempts()
        dismiss()
    }

    override fun onDismiss(dialog: DialogInterface) {
        onSuccessOrDismiss?.invoke(arrayOf())
        onSuccessOrDismiss = null
        super.onDismiss(dialog)
    }
}
