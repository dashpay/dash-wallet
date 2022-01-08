package org.dash.wallet.integrations.crowdnode.ui.entry_point

import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import dagger.hilt.android.AndroidEntryPoint
import org.dash.wallet.common.ui.viewBinding
import org.dash.wallet.integrations.crowdnode.R
import org.dash.wallet.integrations.crowdnode.databinding.FragmentEntryPointBinding
import org.dash.wallet.integrations.crowdnode.ui.CrowdNodeViewModel

@AndroidEntryPoint
class EntryPointFragment : Fragment(R.layout.fragment_entry_point) {
    private val binding by viewBinding(FragmentEntryPointBinding::bind)
    private val viewModel: CrowdNodeViewModel by activityViewModels()
}