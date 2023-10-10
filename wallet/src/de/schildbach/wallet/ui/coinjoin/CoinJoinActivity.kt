/*
 * Copyright 2023 Dash Core Group.
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

package de.schildbach.wallet.ui.coinjoin

import android.os.Bundle
import dagger.hilt.android.AndroidEntryPoint
import de.schildbach.wallet.ui.LockScreenActivity
import de.schildbach.wallet_test.databinding.ActivityCoinjoinBinding

// TODO: temporary CoinJoin host. Remove when SettingsActivity is refactored.
// Should not contain any crucial logic.
@AndroidEntryPoint
class CoinJoinActivity : LockScreenActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val binding = ActivityCoinjoinBinding.inflate(layoutInflater)
        setContentView(binding.root)
    }
}
