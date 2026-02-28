/*
 * Copyright (c) 2024. Dash Core Group.
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
package de.schildbach.wallet.ui.more

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.core.os.bundleOf
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import dagger.hilt.android.AndroidEntryPoint
import de.schildbach.wallet.Constants
import de.schildbach.wallet_test.R
import de.schildbach.wallet_test.databinding.DialogContactSupportBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.dash.wallet.common.SecureActivity
import org.dash.wallet.common.ui.dialogs.OffsetDialogFragment
import org.dash.wallet.common.ui.viewBinding
import org.dash.wallet.common.util.observe
import org.slf4j.LoggerFactory

/**
 * @author Samuel Barbosa
 */
@AndroidEntryPoint
class ContactSupportDialogFragment : OffsetDialogFragment(R.layout.dialog_contact_support) {
    private val binding by viewBinding(DialogContactSupportBinding::bind)
    private val viewModel: ContactSupportViewModel by viewModels()

    override val forceExpand = false

    companion object {
        const val TITLE = "title"
        const val MESSAGE = "message"
        const val STACK_TRACE = "stack"
        const val CONTEXTUAL_DATA = "data"
        const val IS_CRASH = "is_crash"
        private val log = LoggerFactory.getLogger(ContactSupportDialogFragment::class.java)

        @JvmStatic
        fun newInstance(
            title: String,
            message: String,
            contextualData: String? = null,
            stackTrace: String? = null,
            isCrash: Boolean = false
        ): ContactSupportDialogFragment {
            val fragment = ContactSupportDialogFragment()
            fragment.arguments = bundleOf(
                TITLE to title,
                MESSAGE to message,
                STACK_TRACE to stackTrace,
                CONTEXTUAL_DATA to contextualData,
                IS_CRASH to isCrash
            )
            return fragment
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        isCancelable = false
        arguments?.getString(TITLE)?.let {
            binding.title.text = it
        }
        arguments?.getString(MESSAGE)?.let {
            binding.reportIssueDialogMessage.text = it
        }
        binding.reportIssueDialogMessage.doAfterTextChanged {
            imitateUserInteraction()
        }

        binding.submitReport.setOnClickListener {
            sendReport()
        }
        binding.dismissButton.setOnClickListener {
            dismiss()
        }
        arguments?.getString(CONTEXTUAL_DATA)?.let {
            viewModel.contextualData = it
        }
        arguments?.getString(STACK_TRACE)?.let {
            viewModel.stackTrace = it
        }
        arguments?.getBoolean(IS_CRASH)?.let {
            viewModel.isCrash = it
        }
        viewModel.status.observe(viewLifecycleOwner) {
            val newStatus = when (it) {
                ReportGenerationStatus.Logs -> getString(R.string.report_issue_dialog_status_application_log)
                ReportGenerationStatus.Packages -> getString(R.string.report_issue_dialog_status_installed_packages)
                ReportGenerationStatus.ApplicationInfo -> getString(R.string.report_issue_dialog_status_application_info)
                ReportGenerationStatus.WalletDump -> getString(R.string.report_issue_dialog_status_wallet_dump)
                ReportGenerationStatus.StackTrace -> getString(R.string.report_issue_dialog_status_stack_trace)
                ReportGenerationStatus.DeviceInfo -> getString(R.string.report_issue_dialog_status_device_info)
                ReportGenerationStatus.BackgroundTraces -> getString(R.string.report_issue_dialog_status_background_traces)
                ReportGenerationStatus.ContextualInfo -> getString(R.string.report_issue_dialog_status_contextual_info)
                ReportGenerationStatus.Finishing -> getString(R.string.report_issue_dialog_status_finishing)
                else -> ""
            }
            binding.status.isVisible = newStatus.isNotEmpty()
            binding.status.text = newStatus
        }
    }

    private fun imitateUserInteraction() {
        requireActivity().onUserInteraction()
    }

    private fun sendReport() {
        lifecycleScope.launch {
            binding.reportGenerationProgressContainer.isVisible = true
            (requireActivity() as? SecureActivity)?.turnOffAutoLogout()
            val (reportText, attachments) = withContext(Dispatchers.IO) {
                log.info("createReport({})", binding.reportIssueDialogCollectWalletDump.isChecked)
                viewModel.createReport(
                    binding.reportIssueDialogDescription.text.toString(),
                    binding.reportIssueDialogCollectDeviceInfo.isChecked,
                    binding.reportIssueDialogCollectInstalledPackages.isChecked,
                    binding.reportIssueDialogCollectApplicationLog.isChecked,
                    binding.reportIssueDialogCollectWalletDump.isChecked
                )
            }
            startSend(viewModel.subject(), reportText, attachments)
            binding.reportGenerationProgressContainer.isVisible = false
            dismiss()
        }
    }

    // must call this function when the dialog is dismissed
    override fun dismiss() {
        (requireActivity() as? SecureActivity)?.turnOnAutoLogout()
        super.dismiss()
    }

    private fun startSend(subject: CharSequence?, text: CharSequence, attachments: ArrayList<Uri>) {
        val intent: Intent
        if (attachments.isEmpty()) {
            intent = Intent(Intent.ACTION_SEND)
            intent.setType("message/rfc822")
        } else if (attachments.size == 1) {
            intent = Intent(Intent.ACTION_SEND)
            intent.setType("text/plain")
            intent.putExtra(Intent.EXTRA_STREAM, attachments[0])
        } else {
            intent = Intent(Intent.ACTION_SEND_MULTIPLE)
            intent.setType("text/plain")
            intent.putParcelableArrayListExtra(Intent.EXTRA_STREAM, attachments)
        }
        intent.putExtra(Intent.EXTRA_EMAIL, arrayOf(Constants.REPORT_EMAIL))
        if (subject != null) intent.putExtra(Intent.EXTRA_SUBJECT, subject)
        intent.putExtra(Intent.EXTRA_TEXT, text)
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        try {
            requireContext().startActivity(
                Intent.createChooser(
                    intent,
                    requireContext().getString(R.string.report_issue_dialog_mail_intent_chooser)
                )
            )
            log.info("invoked chooser for sending issue report")
        } catch (x: Exception) {
            Toast.makeText(context, R.string.report_issue_dialog_mail_intent_failed, Toast.LENGTH_LONG).show()
            log.error("report issue failed", x)
        }
    }
}
