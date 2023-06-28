/*
 * Copyright 2023 Dash Core Group.
 *
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

package de.schildbach.wallet.ui.send

import android.app.Activity
import android.os.Build
import android.os.Bundle
import android.text.SpannableString
import android.text.Spanned
import android.text.TextPaint
import android.text.method.LinkMovementMethod
import android.text.style.BackgroundColorSpan
import android.text.style.ClickableSpan
import android.text.style.ForegroundColorSpan
import android.view.View
import android.view.inputmethod.EditorInfo
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.isVisible
import androidx.core.widget.doOnTextChanged
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.navigation.fragment.findNavController
import dagger.hilt.android.AndroidEntryPoint
import de.schildbach.wallet.data.PaymentIntent
import de.schildbach.wallet.ui.payments.SweepWalletActivity
import de.schildbach.wallet.ui.scan.ScanActivity
import de.schildbach.wallet.ui.util.InputParser
import de.schildbach.wallet_test.R
import de.schildbach.wallet_test.databinding.FragmentAddressInputBinding
import org.bitcoinj.core.PrefixedChecksummedBytes
import org.bitcoinj.core.Transaction
import org.bitcoinj.core.VerificationException
import org.dash.wallet.common.ui.dialogs.AdaptiveDialog
import org.dash.wallet.common.ui.viewBinding
import org.dash.wallet.common.util.KeyboardUtil
import org.dash.wallet.common.util.observe

@AndroidEntryPoint
class AddressInputFragment : Fragment(R.layout.fragment_address_input) {
    private val binding by viewBinding(FragmentAddressInputBinding::bind)
    private val viewModel by viewModels<AddressInputViewModel>()

    private val scanLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val intent = result.data

        if (result.resultCode == Activity.RESULT_OK && intent != null) {
            val input = intent.getStringExtra(ScanActivity.INTENT_EXTRA_RESULT)
            input?.let { handleString(input, R.string.button_scan, R.string.input_parser_cannot_classify) }
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.toolbar.setNavigationOnClickListener {
            findNavController().popBackStack()
        }

        binding.addressInput.doOnTextChanged { text, _, _, _ ->
            binding.inputWrapper.isErrorEnabled = false
            binding.continueBtn.isEnabled = isAddress(text)
        }

        val continueAction = {
            val input = binding.addressInput.text.toString()

            if (isAddress(input)) {
//                continueCreating(input)
            } else {
                binding.inputWrapper.isErrorEnabled = true
            }
        }

        binding.addressInput.setOnEditorActionListener { _, actionId, _ ->
            when (actionId) {
                EditorInfo.IME_ACTION_NEXT -> {
                    continueAction()
                    true
                }
                else -> false
            }
        }

        binding.inputWrapper.setEndIconOnClickListener {
            val intent = ScanActivity.getTransitionIntent(activity, binding.inputWrapper)
            scanLauncher.launch(intent)
        }

        binding.continueBtn.setOnClickListener {
            continueAction()
        }

        binding.showClipboardBtn.setOnClickListener {
            binding.screenshot.isVisible = true
            viewModel.showClipboardContent()
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            binding.addressInput.setAutofillHints("")
        }

        viewModel.uiState.observe(viewLifecycleOwner) {
            binding.addressInput.setText(it.addressInput)
            binding.addressInput.setSelection(it.addressInput.length)
            binding.showClipboardBtn.isVisible = it.hasClipboardText && it.clipboardText.isEmpty()
            binding.clipboardContentContainer.isVisible = it.clipboardText.isNotEmpty()

            if (it.clipboardText.isNotEmpty()) {
                val spannable = SpannableString(it.clipboardText)

                for (range in it.addressRanges) {
                    val backgroundSpan = BackgroundColorSpan(resources.getColor(R.color.text_highlight_blue_bg, null))
                    val foregroundSpan = ForegroundColorSpan(resources.getColor(R.color.dash_blue, null))
                    val clickableSpan = object : ClickableSpan() {
                        override fun updateDrawState(ds: TextPaint) {
                            super.updateDrawState(ds)
                            ds.isUnderlineText = false
                        }
                        override fun onClick(view: View) {
                            viewModel.selectAddress(it.clipboardText.substring(range.first, range.last))
                        }
                    }
                    spannable.setSpan(clickableSpan, range.first, range.last, Spanned.SPAN_INCLUSIVE_INCLUSIVE)
                    spannable.setSpan(backgroundSpan, range.first, range.last, Spanned.SPAN_INCLUSIVE_INCLUSIVE)
                    spannable.setSpan(foregroundSpan, range.first, range.last, Spanned.SPAN_INCLUSIVE_INCLUSIVE)
                }

                binding.clipboardContent.text = spannable
                binding.clipboardContent.movementMethod = LinkMovementMethod.getInstance()
            }
        }

        KeyboardUtil.showSoftKeyboard(requireContext(), binding.addressInput)
    }

    fun handlePaste(input: String) {
        if (input.isNotEmpty()) {
//            handleString( StringInputParser.parse
//                input,
//                R.string.scan_to_pay_error_dialog_title,
//                R.string.scan_to_pay_error_dialog_message
//            )
        } else {
            AdaptiveDialog.create(
                R.drawable.ic_info_red,
                getString(R.string.shortcut_pay_to_address),
                getString(R.string.scan_to_pay_error_dialog_message_no_data),
                getString(R.string.button_close),
                null
            ).show(requireActivity())
        }
    }

    private fun isAddress(text: CharSequence?): Boolean {
        return true
    }

    // TODO: handle
    private fun handleString(input: String, errorDialogTitleResId: Int, cannotClassifyCustomMessageResId: Int) {
        object : InputParser.StringInputParser(input, true) {
            override fun handlePaymentIntent(paymentIntent: PaymentIntent) {
                SendCoinsActivity.start(requireActivity(), paymentIntent)
            }

            override fun handlePrivateKey(key: PrefixedChecksummedBytes) {
                SweepWalletActivity.start(requireContext(), key, true)
            }

            @Throws(VerificationException::class)
            override fun handleDirectTransaction(tx: Transaction) {
//                viewModel.processDirectTransaction(tx)
            }

            override fun error(x: Exception?, messageResId: Int, vararg messageArgs: Any) {
                val dialog = AdaptiveDialog.create(
                    R.drawable.ic_info_red,
                    getString(errorDialogTitleResId),
                    if (messageArgs.isNotEmpty()) {
                        getString(messageResId, *messageArgs)
                    } else {
                        getString(messageResId)
                    },
                    getString(R.string.button_close),
                    null
                )
                dialog.isMessageSelectable = true
                dialog.show(requireActivity())
            }

            override fun cannotClassify(input: String) {
//                WalletFragment.log.info("cannot classify: '{}'", input)
                error(null, cannotClassifyCustomMessageResId, input)
            }
        }.parse()
    }
}
