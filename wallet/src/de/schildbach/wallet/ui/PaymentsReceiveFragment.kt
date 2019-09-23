package de.schildbach.wallet.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import de.schildbach.wallet_test.R

class PaymentsReceiveFragment : Fragment() {

    companion object {
        @JvmStatic
        fun newInstance() = PaymentsReceiveFragment()
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        return inflater.inflate(R.layout.fragment_payments_receive, container, false)
    }
}
