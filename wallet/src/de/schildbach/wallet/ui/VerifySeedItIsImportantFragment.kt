package de.schildbach.wallet.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import androidx.appcompat.widget.Toolbar
import androidx.fragment.app.Fragment
import de.schildbach.wallet_test.R

/**
 * @author Samuel Barbosa
 */
class VerifySeedItIsImportantFragment : Fragment() {

    private val showRecoveryPhraseBtn: Button by lazy {
        view!!.findViewById<Button>(R.id.verify_show_recovery_phrase_button)
    }

    companion object {
        fun newInstance(): VerifySeedItIsImportantFragment {
            return VerifySeedItIsImportantFragment()
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        return inflater.inflate(R.layout.fragment_its_import_to_secure, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        view.findViewById<Toolbar>(R.id.toolbar).title = getString(R.string.verify_backup_wallet)

        showRecoveryPhraseBtn.setOnClickListener {
            (activity as VerifySeedActions).showRecoveryPhrase()
        }
    }

}