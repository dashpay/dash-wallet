/*
 * Copyright 2021 Dash Core Group.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package org.dash.wallet.common

import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity

open class SecureActivity : AppCompatActivity() {
    private var isSecuredActivity: Boolean = false

    override fun onPause() {
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        super.onPause()
    }

    override fun onResume() {
        if (!isSecuredActivity) {
            window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
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

    open fun turnOffAutoLogout() {
        (application as AutoLogoutTimerHandler).stopAutoLogoutTimer()
    }

    open fun turnOnAutoLogout() {
        (application as AutoLogoutTimerHandler).startAutoLogoutTimer()
    }
}
