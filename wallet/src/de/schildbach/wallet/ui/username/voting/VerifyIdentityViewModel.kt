package de.schildbach.wallet.ui.username.voting

import android.content.ClipData
import android.content.ClipboardManager
import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

@HiltViewModel
open class VerifyIdentityViewModel @Inject constructor(
    private val clipboardManager: ClipboardManager
) : ViewModel() {
    fun copyPost(text: String) {
        clipboardManager.setPrimaryClip(
            ClipData.newPlainText(
                "voting post",
                text
            )
        )
    }
}
