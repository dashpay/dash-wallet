package de.schildbach.wallet.ui.username.request

import android.os.Bundle
import android.view.View
import dagger.hilt.android.AndroidEntryPoint
import de.schildbach.wallet.ui.username.UsernameType

@AndroidEntryPoint
class RequestUsernameSecondaryFragment : RequestUsernameFragment() {
    
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        // Set the username type to Secondary before calling super
        arguments = (arguments ?: Bundle()).apply {
            putSerializable("username_type", UsernameType.Secondary)
        }
        
        super.onViewCreated(view, savedInstanceState)
    }
}