package de.schildbach.wallet.ui.compose_views

import android.os.Bundle
import android.view.View
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.fragment.app.DialogFragment
import org.dash.wallet.common.ui.dialogs.OffsetDialogFragment
import de.schildbach.wallet_test.R
import de.schildbach.wallet_test.databinding.DialogComposeContainerBinding
import org.dash.wallet.common.ui.viewBinding

class ComposeBottomSheet(
    override val backgroundStyle: Int = R.style.SecondaryBackground,
    override val forceExpand: Boolean = false,
    private val content: @Composable (DialogFragment) -> Unit
) : OffsetDialogFragment(R.layout.dialog_compose_container) {
    private val binding by viewBinding(DialogComposeContainerBinding::bind)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.composeContainer.setContent {
            Box(modifier = Modifier.navigationBarsPadding()) {
                content(this@ComposeBottomSheet)
            }
        }
    }
}