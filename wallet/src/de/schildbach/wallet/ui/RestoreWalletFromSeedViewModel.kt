/*
 * Copyright 2019 Dash Core Group
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

package de.schildbach.wallet.ui

import android.content.Intent
import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import de.schildbach.wallet.Constants
import de.schildbach.wallet.WalletApplication
import de.schildbach.wallet.security.SecurityFunctions
import de.schildbach.wallet.security.SecurityGuard
import de.schildbach.wallet.ui.util.SingleLiveEvent
import de.schildbach.wallet.util.MnemonicCodeExt
import de.schildbach.wallet.util.WalletUtils
import de.schildbach.wallet_test.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.bitcoinj.crypto.MnemonicException
import org.dash.wallet.common.Configuration
import org.slf4j.LoggerFactory
import java.util.*
import javax.inject.Inject

@HiltViewModel
class RestoreWalletFromSeedViewModel @Inject constructor(
    private val walletApplication: WalletApplication,
    private val configuration: Configuration,
    private val securityFunctions: SecurityFunctions
) : ViewModel() {

    private val log = LoggerFactory.getLogger(RestoreWalletFromSeedViewModel::class.java)
    internal val startActivityAction = SingleLiveEvent<Intent>()
    private val securityGuard = SecurityGuard()

    suspend fun recover(words: List<String>): String? = withContext(Dispatchers.Default) {
        val wallet = walletApplication.wallet!!
        val encryptionKey = securityFunctions.deriveKey(
            walletApplication.wallet!!,
            securityGuard.retrievePassword()
        )

        try {
            val decryptedSeed =
                wallet.keyChainSeed.decrypt(wallet.keyCrypter, null, encryptionKey) // takes time
            val seed = decryptedSeed.mnemonicCode!!.toTypedArray()
            if (seed contentEquals words.toTypedArray()) {
                return@withContext securityGuard.retrievePin()
            }
        } catch (_: Exception) { }

        return@withContext null
    }

    /**
     * Normalize - converts all letter to lowercase and to words matching those of a BIP39 word list.
     * Examples:
     *   Satoshi -> satoshi (all letters become lowercase)
     *   TODO: also handle this: medaille -> médaille
     * @param words - the recovery phrase word list
     */
    private fun normalize(words: List<String>): List<String> {
        return words.map { it.lowercase(Locale.getDefault()) }
    }

    fun restoreWalletFromSeed(words: List<String>) {
        if (isSeedValid(words)) {
            val wallet = WalletUtils.restoreWalletFromSeed(normalize(words), Constants.NETWORK_PARAMETERS)
            walletApplication.setWallet(wallet)
            log.info("successfully restored wallet from seed")
            configuration.disarmBackupSeedReminder()
            configuration.isRestoringBackup = true
            walletApplication.resetBlockchainState()
            startActivityAction.call(SetPinActivity.createIntent(walletApplication, R.string.set_pin_restore_wallet))
        }
    }

    suspend fun recoverPin(words: List<String>): String? {
        return if (isSeedValid(words)) {
            recover(normalize(words))
        } else {
            null
        }
    }

    /**
     * Checks to see if this seed is valid.  The validation is not case sensitive, nor does it
     * depend on accent marks or other diacritics.
     *
     * @param words
     * @return
     */
    private fun isSeedValid(words: List<String>): Boolean {
        return try {
            MnemonicCodeExt.getInstance().check(walletApplication, words)
            true
        } catch (x: MnemonicException) {
            log.info("problem restoring wallet from seed: ", x)
            throw x
        }
    }
}
