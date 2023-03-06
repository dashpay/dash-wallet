package org.dash.wallet.features.exploredash.ui.dialogs

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.annotation.DrawableRes
import androidx.annotation.StyleRes
import androidx.fragment.app.FragmentActivity
import org.dash.wallet.common.ui.dialogs.OffsetDialogFragment
import org.dash.wallet.common.ui.viewBinding
import org.dash.wallet.features.exploredash.R
import org.dash.wallet.features.exploredash.databinding.DialogDashDirectLoginInfoBinding

class DashDirectLoginInfoDialog : OffsetDialogFragment() {
    companion object {
        private const val ICON_RES_ARG = "icon_res"
        private const val TITLE_ARG = "title"
        private const val MESSAGE_ARG = "message"
        private const val POS_BUTTON_ARG = "positive_text"
        private const val NEG_BUTTON_ARG = "negative_text"
        private const val EXTRA_MESSAGE_BUTTON_ARG = "extra_message_text"

        @JvmStatic
        fun custom(
            @DrawableRes icon: Int?,
            title: String?,
            message: String,
            negativeButtonText: String,
            positiveButtonText: String? = null,
            extraMessage: String? = null
        ): DashDirectLoginInfoDialog {
            val args =
                Bundle().apply {
                    icon?.let { putInt(ICON_RES_ARG, icon) }
                    putString(TITLE_ARG, title)
                    putString(MESSAGE_ARG, message)
                    putString(NEG_BUTTON_ARG, negativeButtonText)
                    putString(POS_BUTTON_ARG, positiveButtonText)
                    putString(EXTRA_MESSAGE_BUTTON_ARG, extraMessage)
                }
            return DashDirectLoginInfoDialog().apply { arguments = args }
        }
    }

    private var onExtraMessageListener: (() -> Unit)? = null
    private var onResultListener: ((Boolean?) -> Unit)? = null

    @StyleRes override val backgroundStyle = R.style.PrimaryBackground

    private val binding by viewBinding(DialogDashDirectLoginInfoBinding::bind)

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        return inflater.inflate(R.layout.dialog_dash_direct_login_info, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val args = requireArguments()
        binding.dialogIcon.setImageResource(args.getInt(ICON_RES_ARG))
        binding.dialogTitle.text = args.getString(TITLE_ARG)
        binding.dialogMessage.text = args.getString(MESSAGE_ARG)
        binding.dialogExtraMessage.text = args.getString(EXTRA_MESSAGE_BUTTON_ARG)
        binding.dialogNegativeButton.text = args.getString(NEG_BUTTON_ARG)
        binding.dialogPositiveButton.text = args.getString(POS_BUTTON_ARG)
        binding.dialogPositiveButton.setOnClickListener { onPositiveAction() }

        binding.dialogNegativeButton.setOnClickListener { onNegativeAction() }

        binding.dialogExtraMessage.setOnClickListener { onExtraMessageAction() }
    }

    private fun onPositiveAction() {
        onResultListener?.invoke(true)
        onResultListener = null
        dismiss()
    }

    private fun onNegativeAction() {
        onResultListener?.invoke(false)
        onResultListener = null
        dismiss()
    }

    fun show(activity: FragmentActivity, onResult: (Boolean?) -> Unit, onExtraMessageAction: () -> Unit) {
        onResultListener = onResult
        onExtraMessageListener = onExtraMessageAction
        show(activity.supportFragmentManager, "bottom_sheet_extra_action_dialog")
    }

    private fun onExtraMessageAction() {
        onExtraMessageListener?.invoke()
        onExtraMessageListener = null
        dismiss()
    }
}
