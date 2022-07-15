package de.schildbach.wallet.ui

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.activityViewModels
import androidx.fragment.app.viewModels
import dagger.hilt.android.AndroidEntryPoint
import de.schildbach.wallet.livedata.Status
import de.schildbach.wallet.ui.preference.PinRetryController
import org.bitcoinj.wallet.DeterministicSeed

/**
 * @author:  Eric Britten
 *
 * DecryptSeedWithPinDialog uses DecryptSeedSharedModel which is derived
 * from CheckPinShared model but does not call the onCorrectPinCallback
 * event
 */
@AndroidEntryPoint
class DecryptSeedWithPinDialog : CheckPinDialog() {

    companion object {

        private val FRAGMENT_TAG = DecryptSeedWithPinDialog::class.java.simpleName

        private const val ARG_REQUEST_CODE = "arg_request_code"
        private const val ARG_PIN_ONLY = "arg_pin_only"

        @JvmStatic
        fun show(activity: AppCompatActivity, requestCode: Int = 0, pinOnly: Boolean = false) {
            val checkPinDialog = DecryptSeedWithPinDialog()
            val controller = PinRetryController.getInstance()

            if (controller.isLocked) {
                val message = controller.getWalletTemporaryLockedMessage(activity.resources)
                checkPinDialog.showLockedAlert(activity, message)
            } else {
                val args = Bundle()
                args.putInt(ARG_REQUEST_CODE, requestCode)
                args.putBoolean(ARG_PIN_ONLY, pinOnly)
                checkPinDialog.arguments = args
                checkPinDialog.show(activity.supportFragmentManager, FRAGMENT_TAG)
            }
        }

        @JvmStatic
        fun show(activity: AppCompatActivity, requestCode: Int = 0) {
            show(activity, requestCode, false)
        }

    }

    override val viewModel by viewModels<DecryptSeedViewModel>()
    override val sharedModel by activityViewModels<DecryptSeedSharedModel>()

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
        val requestCode = requireArguments().getInt(ARG_REQUEST_CODE)
        sharedModel.onDecryptSeedCallback.value = Pair(requestCode, seed)
        viewModel.resetFailedPinAttempts()
        dismiss()
    }

    override fun onFingerprintSuccess(savedPass : String) {
        viewModel.checkPin(savedPass)
    }
}
