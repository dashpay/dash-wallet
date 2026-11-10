package de.schildbach.wallet.ui.dashpay

import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import de.schildbach.wallet.WalletApplication
import de.schildbach.wallet.database.dao.DashPayProfileDao
import de.schildbach.wallet.database.entity.BlockchainIdentityConfig
import de.schildbach.wallet.database.entity.BlockchainIdentityData
import de.schildbach.wallet.database.entity.IdentityCreationState
import de.schildbach.wallet.service.platform.sdk.SdkTransparentUsernameCreation
import de.schildbach.wallet.service.platform.work.RestoreIdentityOperation
import de.schildbach.wallet.ui.dashpay.utils.DashPayConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import org.dash.wallet.common.services.analytics.AnalyticsService
import javax.inject.Inject
import org.slf4j.LoggerFactory

@HiltViewModel
class CreateIdentityViewModel @Inject constructor(
    private val walletApplication: WalletApplication,
    private val analytics: AnalyticsService,
    blockchainIdentityDataDao: BlockchainIdentityConfig,
    dashPayProfileDao: DashPayProfileDao,
    val dashPayConfig: DashPayConfig,
    private val transparentUsernameCreation: SdkTransparentUsernameCreation
) : BaseProfileViewModel(blockchainIdentityDataDao, dashPayProfileDao) {

    private val _creationState = MutableStateFlow(IdentityCreationState.NONE)
    val creationState: StateFlow<IdentityCreationState>
        get() = _creationState

    private val _creationException = MutableStateFlow<String?>(null)
    val creationException: StateFlow<String?>
        get() = _creationException

    // One-shot: a name-only retry that was refused before it started, with the error on record.
    private val _nameRetryRefused = Channel<String?>(Channel.CONFLATED)
    val nameRetryRefused: Flow<String?> = _nameRetryRefused.receiveAsFlow()

    init {
        blockchainIdentityDataDao.observe(BlockchainIdentityConfig.CREATION_STATE)
            .filterNotNull()
            .onEach {
                _creationState.value = IdentityCreationState.valueOf(it)
            }
            .launchIn(viewModelScope)

        blockchainIdentityDataDao.observe(BlockchainIdentityConfig.CREATION_STATE_ERROR_MESSAGE)
            .filterNotNull()
            .onEach {
                _creationException.value = it
            }
            .launchIn(viewModelScope)
    }

    /**
     * The existing identity a new name is registered for (a taken name's "Try again"): the
     * observed one, or the stored record when the observation has not been delivered yet (it is
     * posted asynchronously). Null only when there is no identity record at all.
     */
    suspend fun existingIdentityForNewName(): BlockchainIdentityData? =
        blockchainIdentity.value ?: blockchainIdentityDataDao.load()

    fun retryCreateIdentity() {
        viewModelScope.launch {
            // A name-only retry's error (an existing identity, after the cutover) goes back into the
            // name-only retry with the requested names on record, never to CreateIdentityService,
            // which would reset the identity and fund it again. A name that did land meanwhile is
            // not submitted again there.
            // Every routing field from ONE strict read: a lenient per-key read turns an unreadable
            // store into "no error, not restoring, no invite", which sends a name-only retry's
            // error to CreateIdentityService. Unknown refuses, starting nothing.
            val routing = try {
                blockchainIdentityDataDao.retryRouting()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.warn("retry refused: the identity record cannot be read", e)
                _nameRetryRefused.trySend(_creationException.value)
                return@launch
            }
            val error = routing.errorMessage
            if (SdkTransparentUsernameCreation.isNameOnlyRetryError(error)) {
                val identityId = routing.identityId
                val username = routing.username
                val accepted = identityId != null && username != null &&
                    transparentUsernameCreation.retryNameForExistingIdentity(
                        identityId,
                        username,
                        routing.usernameSecondary
                    )
                if (!accepted) {
                    _nameRetryRefused.trySend(error)
                }
                return@launch
            }
            val isRestoring = routing.restoring

            if (isRestoring) {
                routing.identityId?.let { identityId ->
                    RestoreIdentityOperation(walletApplication)
                        .create(identityId, true)
                        .enqueue()
                }
            } else {
                if (routing.usingInvite) {
                    walletApplication.startService(
                        CreateIdentityService.createIntentForRetryFromInvite(
                            walletApplication,
                            true
                        )
                    )
                } else {
                    walletApplication.startService(
                        CreateIdentityService.createIntentForRetry(
                            walletApplication
                        )
                    )
                }
            }
        }
    }

    companion object {
        private val log = LoggerFactory.getLogger(CreateIdentityViewModel::class.java)
    }
}
