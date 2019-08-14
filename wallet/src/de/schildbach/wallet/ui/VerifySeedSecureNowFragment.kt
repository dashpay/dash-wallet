package de.schildbach.wallet.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import androidx.fragment.app.Fragment
import de.schildbach.wallet_test.R

/**
 * @author Samuel Barbosa
 */
class VerifySeedSecureNowFragment : Fragment() {

    private val secureNowBtn: Button by lazy { view!!.findViewById<Button>(R.id.verify_secure_now_button) }
    private val skipBtn: Button by lazy { view!!.findViewById<Button>(R.id.verify_skip_button) }

    companion object {
        fun newInstance(): VerifySeedSecureNowFragment {
            return VerifySeedSecureNowFragment()
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        return inflater.inflate(R.layout.fragment_secure_wallet, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        secureNowBtn.setOnClickListener { (activity as VerifySeedActions).startSeedVerification() }
        skipBtn.setOnClickListener { (activity as VerifySeedActions).skipSeedVerification() }
    }

}