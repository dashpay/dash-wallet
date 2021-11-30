package de.schildbach.wallet.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.os.bundleOf
import androidx.fragment.app.Fragment
import dagger.hilt.android.AndroidEntryPoint
import de.schildbach.wallet_test.R
import org.dash.wallet.common.services.analytics.AnalyticsConstants
import org.dash.wallet.common.services.analytics.AnalyticsService
import javax.inject.Inject

@AndroidEntryPoint
class PaymentsReceiveFragment : Fragment() {

    companion object {
        @JvmStatic
        fun newInstance() = PaymentsReceiveFragment()
    }

    @Inject
    lateinit var analytics: AnalyticsService

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        analytics.logEvent(AnalyticsConstants.SendReceive.SHOW_QR_CODE, bundleOf())
        return inflater.inflate(R.layout.fragment_payments_receive, container, false)
    }
}
