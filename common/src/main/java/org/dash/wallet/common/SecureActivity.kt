package org.dash.wallet.common

import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity

public open class SecureActivity : AppCompatActivity() {
    private var isSecuredActivity: Boolean = false

    override fun onPause() {
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        super.onPause()
    }

    override fun onResume() {
        if (!isSecuredActivity) {
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }

        super.onResume()
    }

    protected fun setSecuredActivity(isSecured: Boolean) {
        isSecuredActivity = isSecured

        if (isSecured) {
            window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
    }
}