/*
 * Copyright 2023 Dash Core Group
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

package de.schildbach.wallet.service

import android.net.Uri
import android.text.format.DateUtils
import com.google.common.base.Charsets
import com.google.common.base.Preconditions
import de.schildbach.wallet.Constants
import de.schildbach.wallet.WalletApplication
import de.schildbach.wallet.util.Crypto
import de.schildbach.wallet.util.Io
import de.schildbach.wallet.util.Iso8601Format
import org.bitcoinj.core.AddressFormatException
import org.bitcoinj.core.DumpedPrivateKey
import org.bitcoinj.core.ECKey
import org.bitcoinj.core.NetworkParameters
import org.bitcoinj.crypto.LinuxSecureRandom
import org.bitcoinj.script.Script
import org.bitcoinj.wallet.DeterministicKeyChain
import org.bitcoinj.wallet.DeterministicSeed
import org.bitcoinj.wallet.KeyChainGroup
import org.bitcoinj.wallet.UnreadableWalletException
import org.bitcoinj.wallet.Wallet
import org.bitcoinj.wallet.WalletEx
import org.bitcoinj.wallet.WalletExtension
import org.bitcoinj.wallet.WalletProtobufSerializer
import org.bitcoinj.wallet.authentication.AuthenticationGroupExtension
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.io.BufferedReader
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.security.SecureRandom
import java.text.ParseException
import java.util.LinkedList
import javax.inject.Inject

interface WalletFactory {
    // Onboarding
    fun create(params: NetworkParameters, seedWordCount: Int): Wallet
    fun restoreFromSeed(params: NetworkParameters, recoveryPhrase: List<String>): Wallet
    @Throws(IOException::class)
    fun restoreFromFile(params: NetworkParameters, backupUri: Uri, password: String): Pair<Wallet, Boolean>

    // loading from persistent storage
    fun getExtensions(params: NetworkParameters): Array<WalletExtension>
    fun load(params: NetworkParameters, walletFile: File): Wallet
    fun restoreFromBackup(params: NetworkParameters, backupFile: String): Wallet
}

class DashWalletFactory @Inject constructor(
    private val walletApplication: WalletApplication
) : WalletFactory {

    companion object {
        val log: Logger = LoggerFactory.getLogger(DashWalletFactory::class.java)
        fun isUnencryptedStream(inStream: InputStream): Boolean {
            return try {
                inStream.mark(Constants.BACKUP_MAX_CHARS.toInt())
                WalletProtobufSerializer.isWallet(inStream)
            } finally {
                try {
                    inStream.reset()
                } catch (x: IOException) {
                    // swallow
                }
            }
        }
    }

    val contentResolver = walletApplication.contentResolver

    // always create new extension objects to prevent using a previously created object
    override fun getExtensions(params: NetworkParameters): Array<WalletExtension> {
        return arrayOf(AuthenticationGroupExtension(params))
    }

    private fun addMissingExtensions(wallet: Wallet) {
        getExtensions(wallet.params).forEach {
            wallet.addOrGetExistingExtension(it)
        }
    }

    override fun create(params: NetworkParameters, seedWordCount: Int): Wallet {
        //val wallet = WalletEx.createDeterministic(params, Script.ScriptType.P2PKH)
        val bits = when (seedWordCount) {
            12 -> DeterministicSeed.DEFAULT_SEED_ENTROPY_BITS
            24 -> DeterministicSeed.DEFAULT_SEED_ENTROPY_BITS * 2
            else -> error("only 12 or 24 words are supported when creating a wallet")
        }
        val wallet = WalletEx.fromSeed(
            params,
            DeterministicSeed(SecureRandom(), bits, ""),
            Script.ScriptType.P2PKH
        )
        addMissingExtensions(wallet)
        checkWalletValid(wallet, params)
        return wallet
    }

    override fun restoreFromSeed(params: NetworkParameters, recoveryPhrase: List<String>): Wallet {
        return restoreWalletFromSeed(recoveryPhrase, params)
    }

    @Throws(IOException::class)
    override fun restoreFromFile(params: NetworkParameters, backupUri: Uri, password: String): Pair<Wallet, Boolean> {
        val inputStream: InputStream = contentResolver.openInputStream(backupUri) ?: throw IOException(
            "Cannot open $backupUri"
        )
        inputStream.mark(Constants.BACKUP_MAX_CHARS.toInt())
        var fromKeys = false
        var walletType = ""
        val wallet = if (isUnencryptedStream(inputStream)) {
            walletType = "unencrypted"
            restoreWalletFromProtobuf(contentResolver.openInputStream(backupUri), params, getExtensions(params))
        } else if (isKeysStream(params, contentResolver.openInputStream(backupUri))) {
            walletType = "key file"
            fromKeys = true
            restorePrivateKeysFromBase58(contentResolver.openInputStream(backupUri), params)
        } else if (Crypto.isEncryptedStream(contentResolver.openInputStream(backupUri))) {
            walletType = "encrypted"
            restoreWalletFromEncrypted(params, backupUri, contentResolver.openInputStream(backupUri), password)
        } else {
            throw IOException("unknown wallet type: this should not happen")
        }
        log.info("successfully restored {} wallet from external source", walletType)
        return Pair(wallet, fromKeys)
    }

    override fun load(params: NetworkParameters, walletFile: File): Wallet {
        var walletStream: FileInputStream? = null
        try {
            walletStream = FileInputStream(walletFile)
            val wallet = WalletProtobufSerializer().readWallet(walletStream, false, getExtensions(params))

            if (wallet.params != params) {
                throw UnreadableWalletException(
                    "bad wallet network parameters: ${wallet.params.id} but expecting ${params.id}"
                )
            }
            return wallet
        } finally {
            try {
                walletStream?.close()
            } catch (x: IOException) {
                // swallow
            }
        }
    }

    override fun restoreFromBackup(params: NetworkParameters, backupFile: String): Wallet {
        var inputStream: InputStream? = null

        try {
            inputStream = walletApplication.openFileInput(backupFile)

            val wallet = WalletProtobufSerializer().readWallet(inputStream, true, getExtensions(params))

            if (!wallet.isConsistent) throw Error("inconsistent backup")

            // does this work on encrypted backups?
            wallet.addKeyChain(Constants.BIP44_PATH)
            wallet as WalletEx
            wallet.initializeCoinJoin(0)
            return wallet
        } finally {
            try {
                inputStream?.close()
            } catch (x: IOException) {
                // swallow
            }
        }
    }

    @Throws(IOException::class)
    private fun restoreWalletFromProtobufOrBase58(
        inputStream: InputStream,
        expectedNetworkParameters: NetworkParameters
    ): Wallet {
        return restoreWalletFromProtobufOrBase58(
            inputStream,
            expectedNetworkParameters,
            getExtensions(expectedNetworkParameters)
        )
    }

    @Throws(IOException::class)
    fun restoreWalletFromProtobufOrBase58(
        inputStream: InputStream,
        expectedNetworkParameters: NetworkParameters,
        walletExtensions: Array<WalletExtension>
    ): Wallet {
        return try {
            restoreWalletFromProtobuf(inputStream, expectedNetworkParameters, walletExtensions)
        } catch (x: IOException) {
            try {
                inputStream.reset()
                return restorePrivateKeysFromBase58(inputStream, expectedNetworkParameters)
            } catch (x2: IOException) {
                throw IOException(
                    "cannot read protobuf (" + x.message + ") or base58 (" + x2.message + ")",
                    x
                )
            }
        }
    }

    @Throws(IOException::class)
    fun restoreWalletFromProtobuf(
        inputStream: InputStream?,
        expectedNetworkParameters: NetworkParameters,
        walletExtensions: Array<WalletExtension>
    ): Wallet {
        return try {
            val wallet = WalletProtobufSerializer().readWallet(inputStream, true, walletExtensions)
            if (!wallet.isEncrypted) {
                addMissingExtensions(wallet)
            }
            checkWalletValid(wallet, expectedNetworkParameters, !wallet.isEncrypted)
            wallet
        } catch (x: UnreadableWalletException) {
            throw IOException("unreadable wallet", x)
        } finally {
            try {
                inputStream?.close()
            } catch (e: IOException) {
                // swallow
            }
        }
    }

    private fun checkWalletValid(
        wallet: Wallet,
        expectedNetworkParameters: NetworkParameters,
        checkAllExtensions: Boolean = true
    ) {
        if (wallet.params != expectedNetworkParameters) {
            throw IOException("bad wallet backup network parameters: " + wallet.params.id)
        }
        if (!wallet.isConsistent) {
            throw IOException("inconsistent wallet backup")
        }
        if (checkAllExtensions) {
            getExtensions(expectedNetworkParameters).forEach {
                Preconditions.checkState(
                    wallet.keyChainExtensions.containsKey(it.walletExtensionID) ||
                        wallet.extensions.containsKey(it.walletExtensionID)
                )
            }
        }
    }

    private fun restoreWalletFromSeed(
        words: List<String>,
        params: NetworkParameters
    ): Wallet {
        return try {
            val seed = DeterministicSeed(words, null, "", Constants.EARLIEST_HD_SEED_CREATION_TIME)
            val group = KeyChainGroup.builder(params)
                .fromSeed(seed, Script.ScriptType.P2PKH)
                .addChain(
                    DeterministicKeyChain.builder()
                        .seed(seed)
                        .accountPath(Constants.BIP44_PATH)
                        .build()
                )
                .build()
            val wallet = WalletEx(params, group)
            // add extensions
            addMissingExtensions(wallet)

            checkWalletValid(wallet, params)
            wallet
        } finally {
        }
    }

    @Throws(IOException::class)
    private fun restorePrivateKeysFromBase58(
        inputStream: InputStream?,
        expectedNetworkParameters: NetworkParameters
    ): Wallet {
        try {
            val keyReader = BufferedReader(InputStreamReader(inputStream, Charsets.UTF_8))

            // create non-HD wallet
            val group = KeyChainGroup.builder(expectedNetworkParameters).build()
            group.importKeys(readKeys(keyReader, expectedNetworkParameters))
            val wallet = WalletEx(expectedNetworkParameters, group)
            // this will result in a different HD seed each time
            wallet.upgradeToDeterministic(Script.ScriptType.P2PKH, null)
            // add the extensions
            addMissingExtensions(wallet)
            checkWalletValid(wallet, expectedNetworkParameters)
            return wallet
        } finally {
            try {
                inputStream?.close()
            } catch (e: IOException) {
                // swallow
            }
        }
    }

    @Throws(IOException::class)
    private fun restoreWalletFromEncrypted(
        params: NetworkParameters,
        walletUri: Uri,
        inFileStream: InputStream?,
        password: String
    ): Wallet {
        try {
            val cipherIn = BufferedReader(
                InputStreamReader(inFileStream, Charsets.UTF_8)
            )
            val cipherText = StringBuilder()
            Io.copy(cipherIn, cipherText, Constants.BACKUP_MAX_CHARS)
            cipherIn.close()
            val plainText = Crypto.decryptBytes(cipherText.toString(), password.toCharArray())
            val inputStream: InputStream = ByteArrayInputStream(plainText)
            log.info("successfully restored encrypted wallet: {}", walletUri)
            return restoreWalletFromProtobufOrBase58(inputStream, params)
        } finally {
            try {
                inFileStream?.close()
            } catch (e: IOException) {
                // swallow
            }
        }
    }

    @Throws(IOException::class)
    private fun readKeys(inputStream: BufferedReader, expectedNetworkParameters: NetworkParameters?): List<ECKey>? {
        return try {
            val format = Iso8601Format.newDateTimeFormatT()
            val keys: MutableList<ECKey> = LinkedList()
            var charCount: Long = 0
            while (true) {
                val line = inputStream.readLine() ?: break
                // eof
                charCount += line.length.toLong()
                if (charCount > Constants.BACKUP_MAX_CHARS) {
                    throw IOException("read more than the limit of " + Constants.BACKUP_MAX_CHARS + " characters")
                }
                if (line.trim { it <= ' ' }.isEmpty() || line[0] == '#') {
                    continue
                } // skip comment
                val parts = line.split(" ".toRegex()).dropLastWhile { it.isEmpty() }.toTypedArray()
                val key = DumpedPrivateKey.fromBase58(expectedNetworkParameters, parts[0]).key
                key.creationTimeSeconds =
                    if (parts.size >= 2) format.parse(parts[1]).time / DateUtils.SECOND_IN_MILLIS else 0
                keys.add(key)
            }
            keys
        } catch (x: AddressFormatException) {
            throw IOException("cannot read keys", x)
        } catch (x: ParseException) {
            throw IOException("cannot read keys", x)
        }
    }

    private fun isKeysStream(params: NetworkParameters, inputStream: InputStream?): Boolean {
        var reader: BufferedReader? = null
        return try {
            reader = BufferedReader(InputStreamReader(inputStream, Charsets.UTF_8))
            readKeys(reader, params)
            true
        } catch (x: IOException) {
            false
        } finally {
            if (reader != null) {
                try {
                    reader.close()
                } catch (x: IOException) {
                    // swallow
                }
            }
            try {
                inputStream?.reset()
            } catch (x: IOException) {
                // swallow
            }
        }
    }
}
