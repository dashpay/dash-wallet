/*
 * Copyright 2020 Dash Core Group
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package de.schildbach.wallet.ui

import android.app.Activity
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import androidx.fragment.app.Fragment
import androidx.lifecycle.Observer
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import de.schildbach.wallet.AppDatabase
import de.schildbach.wallet.ui.dashpay.OnContactItemClickListener
import de.schildbach.wallet.data.PaymentIntent
import de.schildbach.wallet.data.UsernameSearchResult
import de.schildbach.wallet.livedata.Status
import de.schildbach.wallet.ui.dashpay.DashPayViewModel
import de.schildbach.wallet.ui.dashpay.FrequentContactsAdapter
import de.schildbach.wallet.ui.scan.ScanActivity
import de.schildbach.wallet.ui.send.SendCoinsInternalActivity
import de.schildbach.wallet_test.R
import kotlinx.android.synthetic.main.fragment_payments_pay.*
import org.bitcoinj.core.PrefixedChecksummedBytes
import org.bitcoinj.core.Transaction
import org.bitcoinj.core.VerificationException

class PaymentsPayFragment : Fragment(),
        OnContactItemClickListener {

    companion object {

        private const val REQUEST_CODE_SCAN = 0

        @JvmStatic
        fun newInstance() = PaymentsPayFragment()
    }

    private var frequentContactsAdapter: FrequentContactsAdapter = FrequentContactsAdapter()
    private lateinit var dashPayViewModel: DashPayViewModel

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        return inflater.inflate(R.layout.fragment_payments_pay, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        //Make the whole row clickable
        pay_by_contact_select.setOnClickListener { handleSelectContact() }
        pay_by_qr_button.setOnClickListener { handleScan(it) }
        pay_to_address.setOnClickListener { handlePaste(true) }
        handlePaste(false)

        frequent_contacts_rv.layoutManager = LinearLayoutManager(requireContext(), RecyclerView.HORIZONTAL, false)
        frequent_contacts_rv.adapter = this.frequentContactsAdapter
        this.frequentContactsAdapter.itemClickListener = this

        initViewModel(view)
        dashPayViewModel.getFrequentContacts()
    }

    private fun initViewModel(view: View) {
        AppDatabase.getAppDatabase().blockchainIdentityDataDao().load().observe(viewLifecycleOwner, Observer {
            val visibility = if (it == null) View.GONE else View.VISIBLE
            pay_by_contact_select.visibility = visibility
        })

        dashPayViewModel = ViewModelProvider(this).get(DashPayViewModel::class.java)
        dashPayViewModel.frequentContactsLiveData.observe(viewLifecycleOwner, Observer {
            if (Status.SUCCESS == it.status) {
                if (it.data == null || it.data.isEmpty()) {
                    frequent_contacts_rv.visibility = View.GONE
                } else {
                    frequent_contacts_rv.visibility = View.VISIBLE
                }

                if (it.data != null) {
                    val results = arrayListOf<UsernameSearchResult>()
                    results.addAll(it.data)
                    frequentContactsAdapter.results = results
                }
            } else if (it.status == Status.ERROR) {
                frequent_contacts_rv.visibility = View.GONE
            }
        })
    }

    override fun onResume() {
        super.onResume()
        requireView().viewTreeObserver?.addOnWindowFocusChangeListener(onWindowFocusChangeListener)
        getClipboardManager().addPrimaryClipChangedListener(onPrimaryClipChangedListener)
    }

    override fun onPause() {
        super.onPause()
        requireView().viewTreeObserver?.removeOnWindowFocusChangeListener(onWindowFocusChangeListener)
        getClipboardManager().removePrimaryClipChangedListener(onPrimaryClipChangedListener)
    }

    private fun getClipboardManager(): ClipboardManager {
        return context?.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager;
    }

    private val onWindowFocusChangeListener = ViewTreeObserver.OnWindowFocusChangeListener { hasFocus ->
        if (hasFocus) {
            handlePaste(false)
        }
    }

    private val onPrimaryClipChangedListener = ClipboardManager.OnPrimaryClipChangedListener {
        handlePaste(false)
    }

    private fun handleSelectContact() {
        if (requireActivity() is OnSelectContactToPayListener) {
            (requireActivity() as OnSelectContactToPayListener).selectContactToPay()
        }
    }

    private fun handleScan(clickView: View) {
        ScanActivity.startForResult(this, activity, REQUEST_CODE_SCAN)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, intent: Intent?) {
        if (requestCode == REQUEST_CODE_SCAN && resultCode == Activity.RESULT_OK) {
            val input = intent!!.getStringExtra(ScanActivity.INTENT_EXTRA_RESULT)
            handleString(input, true, R.string.button_scan)
        } else {
            super.onActivityResult(requestCode, resultCode, intent)
        }
    }

    private fun handlePaste(fireAction: Boolean) {
        val input = clipboardData()
        if (input != null) {
            handleString(input, fireAction, R.string.payments_pay_to_clipboard_title)
        }
    }

    private fun clipboardData(): String? {
        val clipboardManager = getClipboardManager()
        if (clipboardManager.hasPrimaryClip()) {
            clipboardManager.primaryClip?.run {
                return when {
                    description.hasMimeType(ClipDescription.MIMETYPE_TEXT_URILIST) -> getItemAt(0).uri?.toString()
                    description.hasMimeType(ClipDescription.MIMETYPE_TEXT_PLAIN) -> getItemAt(0).text?.toString()
                    description.hasMimeType(ClipDescription.MIMETYPE_TEXT_HTML) -> getItemAt(0).text?.toString()
                    else -> null
                }
            }
        }
        return null
    }

    private fun handleString(input: String, fireAction: Boolean, errorDialogTitleResId: Int) {
        object : InputParser.StringInputParser(input, true) {

            override fun handlePaymentIntent(paymentIntent: PaymentIntent) {
                if (fireAction) {
                    SendCoinsInternalActivity.start(context, paymentIntent, true)
                } else {
                    manageStateOfPayToAddressButton(paymentIntent)
                }
            }

            override fun error(ex: Exception?, messageResId: Int, vararg messageArgs: Any) {
                if (fireAction) {
                    dialog(context, null, errorDialogTitleResId, messageResId, *messageArgs)
                } else {
                    manageStateOfPayToAddressButton(null)
                }
            }

            override fun handlePrivateKey(key: PrefixedChecksummedBytes) {
                // ignore
            }

            @Throws(VerificationException::class)
            override fun handleDirectTransaction(tx: Transaction) {
                // ignore
            }
        }.parse()
    }

    private fun manageStateOfPayToAddressButton(paymentIntent: PaymentIntent?) {
        if (paymentIntent != null) {
            when {
                paymentIntent.hasAddress() -> {
                    pay_to_address.setActive(true)
                    pay_to_address.setSubTitle(paymentIntent.address.toBase58())
                    return
                }
                paymentIntent.hasPaymentRequestUrl() -> {
                    val host = Uri.parse(paymentIntent.paymentRequestUrl).host
                    if (host != null) {
                        pay_to_address.setActive(true)
                        pay_to_address.setSubTitle(host)
                        return
                    }
                }
            }
        }
        pay_to_address.setActive(false)
        pay_to_address.setSubTitle(R.string.payments_pay_to_clipboard_sub_title)
    }

    interface OnSelectContactToPayListener {
        fun selectContactToPay()
    }

    override fun onItemClicked(view: View, usernameSearchResult: UsernameSearchResult) {
        handleString(usernameSearchResult.fromContactRequest!!.userId, true, R.string.scan_to_pay_username_dialog_message)
    }

}
