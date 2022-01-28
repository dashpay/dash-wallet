package de.schildbach.wallet.ui.explore

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import dagger.hilt.android.AndroidEntryPoint
import de.schildbach.wallet.Constants
import de.schildbach.wallet.WalletApplication
import de.schildbach.wallet.ui.dashpay.BottomNavFragment
import de.schildbach.wallet_test.R
import de.schildbach.wallet_test.databinding.FragmentExploreTestnetBinding
import org.dash.wallet.common.ui.viewBinding

@AndroidEntryPoint
class ExploreTestNetFragment : BottomNavFragment(R.layout.fragment_explore_testnet) {

    companion object {
        @JvmStatic
        fun newInstance(): ExploreTestNetFragment {
            return ExploreTestNetFragment()
        }
    }

    override val navigationItemId = R.id.discover
    private val binding by viewBinding(FragmentExploreTestnetBinding::bind)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.getDashBtn.setOnClickListener {
            val receiveAddress = WalletApplication.getInstance().freshReceiveAddress()
            val clipboardManager =
                requireActivity().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager

            clipboardManager.setPrimaryClip(ClipData.newPlainText("Dash address", receiveAddress.toString()))

            val faucetIntent = Intent(
                Intent.ACTION_VIEW,
                Uri.parse(Constants.FAUCET_URL)
            )
            startActivity(faucetIntent)
        }
    }
}
