/*
 * Copyright 2021 Dash Core Group
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

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