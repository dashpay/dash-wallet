package de.schildbach.wallet.service.platform

import de.schildbach.wallet.WalletApplication
import de.schildbach.wallet.data.CreditBalanceInfo
import de.schildbach.wallet.database.entity.BlockchainIdentityConfig
import de.schildbach.wallet.service.DashSystemService
import de.schildbach.wallet.ui.dashpay.PlatformRepo
import de.schildbach.wallet.ui.dashpay.utils.DashPayConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.bouncycastle.crypto.params.KeyParameter
import org.dash.wallet.common.WalletDataProvider
import org.dashj.platform.dashpay.BlockchainIdentity
import org.dashj.platform.dashpay.callback.WalletSignerCallback
import org.slf4j.LoggerFactory
import javax.inject.Inject

interface IdentityRepository {
    //suspend fun addMissingKeys(keyParameter: KeyParameter?): Boolean
    suspend fun getIdentityBalance(): CreditBalanceInfo?
    suspend fun upgradeIdentity(keyParameter: KeyParameter?): Boolean
}

class IdentityRepositoryImpl @Inject constructor(
    val blockchainIdentityDataStorage: BlockchainIdentityConfig,
    private val walletDataProvider: WalletDataProvider,
    private val platformRepo: PlatformRepo,
    private val dashPayConfig: DashPayConfig,
    private val dashSystemService: DashSystemService
) : IdentityRepository {
    companion object {
        private val log = LoggerFactory.getLogger(TopUpRepositoryImpl::class.java)
    }
    val blockchainIdentity: BlockchainIdentity
        get() = platformRepo.blockchainIdentity
    val platform = platformRepo.platform

    suspend fun hasIdentity(): Boolean = platformRepo.hasIdentity()

    suspend fun hasUsername(): Boolean = platformRepo.hasUsername()

    override suspend fun getIdentityBalance(): CreditBalanceInfo? = withContext(Dispatchers.IO) {
        try {
            CreditBalanceInfo(platformRepo.platform.client.getIdentityBalance(blockchainIdentity.uniqueIdentifier))
        } catch (e: Exception) {
            log.error("Failed to get identity balance", e)
            null
        }
    }

    /** should be called after loading identity from storage and updating from platform */
    override suspend fun upgradeIdentity(keyParameter: KeyParameter?): Boolean {
        // the only upgrade is to add missing keys
        return addMissingKeys(keyParameter)
    }

    /** assumes that the blockchainIdentity has been synced against platform */
    private suspend fun addMissingKeys(keyParameter: KeyParameter?): Boolean {
        if (hasIdentity()) {
            walletDataProvider.wallet?.let { wallet ->
                if (!blockchainIdentity.hasTransferKey() || !blockchainIdentity.hasEncryptionKey()) {
                    try {
                        log.info(
                            "one or more identity keys are missing [transfer=${
                                blockchainIdentity.hasTransferKey()
                            }, encryption=${
                                blockchainIdentity.hasEncryptionKey()
                            }]"
                        )
                        val enough = getIdentityBalance()
                        if (enough != null && !enough.isBalanceEmpty()) {
                            val signer = WalletSignerCallback(wallet, keyParameter)
                            blockchainIdentity.addMissingKeys(signer)
                            platformRepo.updateBlockchainIdentityData()
                            return true
                        }
                    } catch (E: Exception) {
                        log.error("failure to add missing keys", E)
                    }
                }
            }
        }
        return false
    }
}