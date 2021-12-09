package de.schildbach.wallet.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.constraintlayout.widget.ConstraintLayout
import de.schildbach.wallet_test.R
import org.dash.wallet.common.ui.OffsetDialogFragment

class VerifySeedWarningDialog: OffsetDialogFragment<ConstraintLayout>() {
    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        return inflater.inflate(R.layout.dialog_verify_seed_warning, container, false)
    }
}