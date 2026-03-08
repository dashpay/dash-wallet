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

package de.schildbach.wallet.ui.more.masternode_keys

import android.content.ClipData
import android.content.ClipboardManager
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import de.schildbach.wallet.Constants
import de.schildbach.wallet.security.SecurityFunctions
import de.schildbach.wallet.security.SecurityGuard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import org.bitcoinj.core.Address
import org.bitcoinj.core.Context
import org.bitcoinj.core.Utils
import org.bitcoinj.crypto.BLSPublicKey
import org.bitcoinj.crypto.ChildNumber
import org.bitcoinj.crypto.IDeterministicKey
import org.bitcoinj.crypto.IKey
import org.bitcoinj.crypto.KeyType
import org.bitcoinj.crypto.ed25519.Ed25519DeterministicKey
import org.bitcoinj.wallet.AuthenticationKeyChain
import org.bitcoinj.wallet.DerivationPathFactory
import org.bitcoinj.wallet.authentication.AuthenticationGroupExtension
import org.bitcoinj.wallet.authentication.AuthenticationKeyStatus
import org.bitcoinj.wallet.authentication.AuthenticationKeyUsage
import org.bouncycastle.util.encoders.Base64
import org.dash.wallet.common.WalletDataProvider
import org.slf4j.LoggerFactory
import java.lang.Integer.max
import java.util.*
import javax.inject.Inject

@HiltViewModel
class MasternodeKeysViewModel @Inject constructor(
    private val walletData: WalletDataProvider,
    private val clipboardManager: ClipboardManager,
    private val securityFunctions: SecurityFunctions
) : ViewModel() {
    companion object {
        private val log = LoggerFactory.getLogger(MasternodeKeysViewModel::class.java)
    }

    // this is the only place in the app that should addOrGetExistingExtension besides WalletFactory
    private val authenticationGroup: AuthenticationGroupExtension = walletData.wallet!!.addOrGetExistingExtension(
        AuthenticationGroupExtension(walletData.wallet)
    ) as AuthenticationGroupExtension
    private val masternodeKeyChainTypes = EnumSet.of(
        AuthenticationKeyChain.KeyChainType.MASTERNODE_OWNER,
        AuthenticationKeyChain.KeyChainType.MASTERNODE_VOTING,
        AuthenticationKeyChain.KeyChainType.MASTERNODE_OPERATOR,
        AuthenticationKeyChain.KeyChainType.MASTERNODE_PLATFORM_OPERATOR
    )

    val keyChainMap = hashMapOf<MasternodeKeyType, MasternodeKeyTypeInfo>()

    private val masternodeKeyChainInfoMap = hashMapOf<MasternodeKeyType, MasternodeKeyChainInfo>()

    private val _uiState = MutableStateFlow(MasternodeKeysUIState())
    val uiState: StateFlow<MasternodeKeysUIState> = _uiState.asStateFlow()

    init {
        if (authenticationGroup.hasKeyChains()) {
            initKeyChainInfo()
        }

        walletData.observeAuthenticationKeyUsage()
            .onEach { usage -> refreshKeyChains(usage) }
            .launchIn(viewModelScope)
    }

    private fun refreshKeyChains(usage: List<AuthenticationKeyUsage>) {
        walletData.wallet?.let { wallet ->
            val ownerPath = DerivationPathFactory.get(wallet.params).masternodeOwnerDerivationPath()
            val rootPath = ownerPath.subList(0, ownerPath.size - 1)

            // determine the number of new keys that were used
            val masternodeKeysUsed = usage.count { keyUsage ->
                if (keyUsage.key is IDeterministicKey) {
                    val key = keyUsage.key as IDeterministicKey
                    key.path.containsAll(rootPath)
                } else {
                    false
                }
            }
            if (masternodeKeysUsed > 0) {
                initKeyChainInfo()
                _uiState.update { it.copy(newKeysFound = true) }
            } else {
                _uiState.update { it.copy(newKeysFound = false) }
            }
        }
    }

    private fun initKeyChainInfo() {
        for (keyChainType in MasternodeKeyType.entries) {
            keyChainMap[keyChainType] = getKeyChainData(keyChainType)
        }
        _uiState.update { it.copy(
            keyTypes = MasternodeKeyType.entries.mapNotNull { keyChainMap[it] }
        ) }
    }

    fun hasMasternodeKeys(): Boolean {
        return !authenticationGroup.missingAnyKeyChainTypes(masternodeKeyChainTypes)
    }

    private fun getAuthenticationKeyChainType(type: MasternodeKeyType): AuthenticationKeyChain.KeyChainType {
        return when (type) {
            MasternodeKeyType.OWNER -> AuthenticationKeyChain.KeyChainType.MASTERNODE_OWNER
            MasternodeKeyType.VOTING -> AuthenticationKeyChain.KeyChainType.MASTERNODE_VOTING
            MasternodeKeyType.OPERATOR -> AuthenticationKeyChain.KeyChainType.MASTERNODE_OPERATOR
            MasternodeKeyType.PLATFORM -> AuthenticationKeyChain.KeyChainType.MASTERNODE_PLATFORM_OPERATOR
        }
    }

    fun getKeyChain(type: MasternodeKeyType): AuthenticationKeyChain {
        return authenticationGroup.getKeyChain(getAuthenticationKeyChainType(type))
    }

    fun getKeyChainInfo(type: MasternodeKeyType, refresh: Boolean): MasternodeKeyChainInfo {
        var keyChainInfo = masternodeKeyChainInfoMap[type]
        if (keyChainInfo == null || refresh) {
            val keyChain = authenticationGroup.getKeyChain(getAuthenticationKeyChainType(type))
            val keyInfoList = arrayListOf<MasternodeKeyInfo>()
            val maxKeyCount = getMaxKeyCount(type)

            for (i in 0 until max(1, maxKeyCount)) {
                keyInfoList.add(
                    MasternodeKeyInfo(
                        keyChain.getKey(
                            i,
                            keyChain.hasHardenedKeysOnly()
                        )
                    )
                )
            }
            keyChainInfo = MasternodeKeyChainInfo(keyChain, keyInfoList)
            masternodeKeyChainInfoMap[type] = keyChainInfo
        }
        return keyChainInfo
    }

    private fun getMaxKeyCount(type: MasternodeKeyType): Int {
        return if (getKeyUsage().isNotEmpty()) {
            val keyChainType = getAuthenticationKeyChainType(type)
            getKeyUsage().values.maxOf {
                if (it.key is IDeterministicKey && it.type == keyChainType) {
                    val key = it.key as IDeterministicKey
                    key.childNumber.num()
                } else {
                    0
                }
            }
        } else {
            0
        }
    }

    fun getKey(type: MasternodeKeyType, index: Int): IKey {
        val keyChain = getKeyChain(type)
        val key = keyChain.getKey(index, keyChain.hasHardenedKeysOnly())

        val securityGuard = SecurityGuard.getInstance()
        val password = securityGuard.retrievePassword()
        val encryptionKey = securityFunctions.deriveKey(walletData.wallet!!, password)

        return key.decrypt(walletData.wallet!!.keyCrypter, encryptionKey)
    }

    fun getKeyChainData(type: MasternodeKeyType): MasternodeKeyTypeInfo {
        val keyChainType = getAuthenticationKeyChainType(type)
        val usedKeys = authenticationGroup.keyUsage.values.count { usage ->
            if (usage.type == keyChainType) {
                usage.status == AuthenticationKeyStatus.CURRENT ||
                    usage.status == AuthenticationKeyStatus.PREVIOUS ||
                    usage.status == AuthenticationKeyStatus.REVOKED
            } else {
                false
            }
        }
        val maxKeyCount = getMaxKeyCount(type)
        return MasternodeKeyTypeInfo(type, max(1, maxKeyCount), usedKeys)
    }

    fun addKeyChains(pin: String) {
        if (authenticationGroup.missingAnyKeyChainTypes(masternodeKeyChainTypes)) {
            val securityGuard = SecurityGuard.getInstance()
            val password = securityGuard.retrievePassword()
            val encryptionKey = securityFunctions.deriveKey(walletData.wallet!!, password)

            authenticationGroup.addEncryptedKeyChains(
                Constants.NETWORK_PARAMETERS,
                walletData.wallet!!.keyChainSeed,
                encryptionKey,
                masternodeKeyChainTypes
            )

            // generate 1 fresh key per keychain type
            authenticationGroup.freshKey(AuthenticationKeyChain.KeyChainType.MASTERNODE_OWNER)
            authenticationGroup.freshKey(AuthenticationKeyChain.KeyChainType.MASTERNODE_VOTING)
            authenticationGroup.freshKey(AuthenticationKeyChain.KeyChainType.MASTERNODE_OPERATOR)
            authenticationGroup.freshKey(AuthenticationKeyChain.KeyChainType.MASTERNODE_PLATFORM_OPERATOR)

            initKeyChainInfo()
        }
    }

    fun copyToClipboard(text: String) {
        clipboardManager.setPrimaryClip(
            ClipData.newPlainText(
                "Dash Wallet masternode key",
                text
            )
        )
    }

    fun getKeyUsage(): Map<IKey, AuthenticationKeyUsage> {
        return authenticationGroup.keyUsage
    }

    /**
     * adds a new masternode key and returns its position
     */
    suspend fun addKey(masternodeKeyType: MasternodeKeyType): Int = withContext(Dispatchers.IO) {
        Context.propagate(walletData.wallet!!.context)
        val keyChainInfo = masternodeKeyChainInfoMap[masternodeKeyType]
        val maxKeyCount = max(1, keyChainInfo!!.masternodeKeyInfoList.size)
        if (maxKeyCount < keyChainInfo.masternodeKeyChain.issuedKeyCount) {
            keyChainInfo.masternodeKeyInfoList.add(
                MasternodeKeyInfo(
                    keyChainInfo.masternodeKeyChain.getKey(
                        maxKeyCount,
                        keyChainInfo.masternodeKeyChain.keyFactory.keyType == KeyType.EdDSA
                    )
                )
            )
        } else {
            if (keyChainInfo.masternodeKeyChain.hasHardenedKeysOnly()) {
                val securityGuard = SecurityGuard.getInstance()
                val password = securityGuard.retrievePassword()
                val encryptionKey = securityFunctions.deriveKey(walletData.wallet!!, password)
                val keyCrypter = walletData.wallet!!.keyCrypter
                val parent = keyChainInfo.masternodeKeyChain.watchingKey as Ed25519DeterministicKey
                val decryptedParent = parent.decrypt(keyCrypter, encryptionKey)
                log.info("decrypted parent key")

                // decrypt the key chain
                // this is not the best way because all keys are decrypted
                // but dashj doesn't have a means of decrypting the parent key to derive a new child
                val freshKey = decryptedParent.deriveChildKey(
                    ChildNumber(
                        keyChainInfo.masternodeKeyChain.issuedKeyCount,
                        true
                    )
                )
                log.info("derived key")

                val encryptedKey = freshKey.encrypt(keyCrypter, encryptionKey, parent)
                log.info("encrypted key")

                keyChainInfo.masternodeKeyInfoList.add(MasternodeKeyInfo(encryptedKey))
                addNewKey(encryptedKey, masternodeKeyType)
                log.info("add new key complete")
            } else {
                keyChainInfo.masternodeKeyInfoList.add(
                    MasternodeKeyInfo(
                        authenticationGroup.freshKey(
                            getAuthenticationKeyChainType(masternodeKeyType)
                        )
                    )
                )
            }
        }
        keyChainMap[masternodeKeyType]!!.totalKeys = keyChainInfo.masternodeKeyInfoList.size
        log.info("callback")
        return@withContext keyChainInfo.masternodeKeyInfoList.size - 1
    }

    private fun addNewKey(freshKey: IDeterministicKey, masternodeKeyType: MasternodeKeyType) {
        authenticationGroup.addNewKey(getKeyChain(masternodeKeyType).type, freshKey)
    }

    fun initKeyChainScreen(type: MasternodeKeyType) {
        rebuildKeyChainState(type)
        viewModelScope.launch(Dispatchers.IO) {
            val keyChainInfo = masternodeKeyChainInfoMap[type] ?: return@launch
            keyChainInfo.masternodeKeyInfoList.forEachIndexed { position, keyInfo ->
                if (keyInfo.privateKeyHex == null) {
                    val decrypted = getDecryptedKey(keyInfo.masternodeKey)
                    keyChainInfo.masternodeKeyInfoList[position] = decrypted
                    rebuildKeyChainState(type)
                }
            }
        }
    }

    private fun rebuildKeyChainState(type: MasternodeKeyType) {
        val keyChainInfo = getKeyChainInfo(type, false)
        val usage = getKeyUsage()
        val keypairs = keyChainInfo.masternodeKeyInfoList.mapIndexed { position, keyInfo ->
            val key = keyInfo.masternodeKey
            val usageEntry = if (usage.containsKey(key)) {
                usage[key]
            } else {
                usage.values.find { it.key.pubKey.contentEquals(key.pubKey) }
            }
            val index = if (key is IDeterministicKey) {
                key.path.last()?.num() ?: position
            } else {
                position
            }
            KeypairEntry(
                index = index,
                usageStatus = usageEntry?.status,
                usageIpAddress = usageEntry?.address?.addr?.toString()?.removePrefix("/"),
                fields = buildKeyFields(keyInfo)
            )
        }
        _uiState.update { it.copy(keyChainState = MasternodeKeyChainUIState(type, keypairs)) }
    }

    private fun buildKeyFields(keyInfo: MasternodeKeyInfo): List<KeyFieldEntry> {
        val key = keyInfo.masternodeKey
        return when (key.keyFactory.keyType) {
            KeyType.ECDSA -> listOf(
                KeyFieldEntry(KeyFieldType.ADDRESS, Address.fromKey(Constants.NETWORK_PARAMETERS, key).toBase58()),
                KeyFieldEntry(KeyFieldType.PUBLIC_KEY, Utils.HEX.encode(key.pubKey)),
                KeyFieldEntry(KeyFieldType.PRIVATE_KEY_HEX, keyInfo.privateKeyHex),
                KeyFieldEntry(KeyFieldType.PRIVATE_KEY_WIF, keyInfo.privateKeyWif)
            )
            KeyType.BLS -> {
                val blsPublicKey = key.pubKeyObject as BLSPublicKey
                listOf(
                    KeyFieldEntry(KeyFieldType.PUBLIC_KEY, blsPublicKey.toStringHex(false)),
                    KeyFieldEntry(KeyFieldType.PUBLIC_KEY_LEGACY, blsPublicKey.toStringHex(true)),
                    KeyFieldEntry(KeyFieldType.PRIVATE_KEY_HEX, keyInfo.privateKeyHex)
                )
            }
            KeyType.EdDSA -> listOf(
                KeyFieldEntry(KeyFieldType.KEY_ID, Utils.HEX.encode(key.pubKeyHash)),
                KeyFieldEntry(KeyFieldType.PRIVATE_PUBLIC_BASE64, keyInfo.privatePublicKeyBase64)
            )
            else -> emptyList()
        }
    }

    fun getDecryptedKey(key: IKey): MasternodeKeyInfo {
        val securityGuard = SecurityGuard.getInstance()
        val password = securityGuard.retrievePassword()
        val encryptionKey = securityFunctions.deriveKey(walletData.wallet!!, password)

        val decryptedKey = key.decrypt(walletData.wallet!!.keyCrypter, encryptionKey)
        val privateKeyHex = Utils.HEX.encode(decryptedKey.privKeyBytes)
        val privateKeyWif = decryptedKey.getPrivateKeyAsWiF(Constants.NETWORK_PARAMETERS)

        // create the platform node key (privateKey || publicKey)
        val bytes = ByteArray(64)
        decryptedKey.privKeyBytes.copyInto(bytes, 0, 0, 32)
        // public key bytes have a 0x00 prefix byte that is ignored
        decryptedKey.pubKey.copyInto(bytes, 32, 1, 33)
        val privatePublicKeyBase64 = Base64.toBase64String(bytes)

        return MasternodeKeyInfo(key, privateKeyHex, privateKeyWif, privatePublicKeyBase64)
    }
}

data class MasternodeKeysUIState(
    val keyTypes: List<MasternodeKeyTypeInfo> = emptyList(),
    val keyChainState: MasternodeKeyChainUIState = MasternodeKeyChainUIState(),
    val newKeysFound: Boolean = false
)

enum class KeyFieldType {
    ADDRESS, KEY_ID, PUBLIC_KEY, PUBLIC_KEY_LEGACY,
    PRIVATE_KEY_HEX, PRIVATE_KEY_WIF, PRIVATE_PUBLIC_BASE64
}

data class KeyFieldEntry(
    val type: KeyFieldType,
    val value: String?
)

data class KeypairEntry(
    val index: Int,
    val usageStatus: AuthenticationKeyStatus?,
    val usageIpAddress: String?,
    val fields: List<KeyFieldEntry>
)

data class MasternodeKeyChainUIState(
    val keyType: MasternodeKeyType? = null,
    val keypairs: List<KeypairEntry> = emptyList()
)
