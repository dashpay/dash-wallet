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
import androidx.core.content.res.ResourcesCompat
import androidx.core.view.isVisible
import androidx.core.widget.doOnTextChanged
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import dagger.hilt.android.AndroidEntryPoint
import de.schildbach.wallet.payments.parsers.PaymentIntentParser
import de.schildbach.wallet.ui.scan.ScanActivity
import de.schildbach.wallet_test.R
import de.schildbach.wallet_test.databinding.FragmentAddressInputBinding
import kotlinx.coroutines.launch
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
            input?.let { viewModel.setInput(input) }
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.toolbar.setNavigationOnClickListener {
            findNavController().popBackStack()
        }

        binding.addressInput.doOnTextChanged { text, _, _, _ ->
            binding.continueBtn.isEnabled = !text.isNullOrEmpty()
            binding.inputWrapper.isErrorEnabled = false
            binding.errorText.isVisible = false
            binding.inputWrapper.endIconDrawable = ResourcesCompat.getDrawable(
                resources,
                if (text.isNullOrEmpty()) R.drawable.ic_scan_qr else R.drawable.ic_clear_input,
                null
            )
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
            if (binding.addressInput.text.isNullOrEmpty()) {
                val intent = ScanActivity.getTransitionIntent(activity, binding.inputWrapper)
                scanLauncher.launch(intent)
            } else {
                binding.addressInput.text?.clear()
            }
        }

        binding.continueBtn.setOnClickListener {
            continueAction()
        }

        binding.showClipboardBtn.setOnClickListener {
            viewModel.showClipboardContent()
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            binding.addressInput.setAutofillHints("")
        }

        viewModel.uiState.observe(viewLifecycleOwner) {
            if (it.addressInput.isNotEmpty()) {
                binding.addressInput.setText(it.addressInput)
                binding.addressInput.setSelection(it.addressInput.length)
            }

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
                            viewModel.setInput(it.clipboardText.substring(range.first, range.last))
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

    private fun continueAction() {
        lifecycleScope.launch {
            val input = binding.addressInput.text.toString()

            try {
                val paymentIntent = PaymentIntentParser.parse(input, true)
                binding.inputWrapper.isErrorEnabled = false
                binding.errorText.isVisible = false
                SendCoinsActivity.start(requireContext(), paymentIntent)
            } catch (ex: Exception) {
                binding.inputWrapper.isErrorEnabled = true
                binding.errorText.isVisible = true
            }
        }
    }
}
