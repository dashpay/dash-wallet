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
import androidx.core.net.toUri
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import de.schildbach.wallet.database.dao.DashPayProfileDao
import de.schildbach.wallet.data.InvitationLinkData
import de.schildbach.wallet.data.InvitationValidationState
import de.schildbach.wallet.database.entity.BlockchainIdentityBaseData
import de.schildbach.wallet.database.entity.BlockchainIdentityConfig
import de.schildbach.wallet.database.entity.IdentityCreationState
import de.schildbach.wallet.service.platform.TopUpRepository
import de.schildbach.wallet.ui.dashpay.BaseProfileViewModel
import de.schildbach.wallet.ui.dashpay.utils.DashPayConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import android.net.Uri
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.dash.wallet.common.services.BlockchainStateProvider
import org.slf4j.LoggerFactory
import javax.inject.Inject

@HiltViewModel
class InviteHandlerViewModel @Inject constructor(
    blockchainIdentityDataDao: BlockchainIdentityConfig,
    dashPayProfileDao: DashPayProfileDao,
    private val blockchainStateProvider: BlockchainStateProvider,
    private val topUpRepository: TopUpRepository,
    private val dashPayConfig: DashPayConfig
) : BaseProfileViewModel(blockchainIdentityDataDao, dashPayProfileDao) {
    companion object {
        private val log = LoggerFactory.getLogger(InviteHandlerViewModel::class.java)
    }

    /**
     * The pending invite, tagged with the [PendingInvitation.generation] it was
     * stored under. Every set, replace or clear — including one that arrives
     * through the persisted INVITATION_LINK — bumps the generation, so it, not
     * the link, identifies "this exact invite": reopening A after A → B → A
     * gets a new generation that A's earlier validation and dialog cannot match.
     */
    private val _pendingInvitation = MutableStateFlow<PendingInvitation?>(null)
    val pendingInvitation: StateFlow<PendingInvitation?> = _pendingInvitation.asStateFlow()

    var fromOnboarding = false

    /**
     * Serialises every change to [_pendingInvitation] (and the persisted link
     * behind it): set, clear, the INVITATION_LINK observer and the publication
     * of a validation verdict. Guards [generation].
     */
    private val storeMutex = Mutex()
    private var generation = 0L

    val isUsingInvite: Boolean
        get() = _pendingInvitation.value != null

    init {
        dashPayConfig.observe(DashPayConfig.INVITATION_LINK)
            .onEach { stored ->
                storeMutex.withLock {
                    val current = _pendingInvitation.value
                    if (stored.isNullOrEmpty()) {
                        if (current != null) {
                            _pendingInvitation.value = null
                            generation++
                        }
                    } else if (current?.data?.link?.toString() != stored) {
                        // Only adopt a DIFFERENT link. The in-memory copy carries the
                        // validation verdict; re-wrapping the same link would reset it
                        // to "unvalidated" and re-run validation (and its dialog).
                        // setInvitationLink() publishes a fresh copy itself.
                        _pendingInvitation.value = PendingInvitation(++generation, InvitationLinkData(stored.toUri()))
                    }
                }
            }
            .launchIn(viewModelScope)

        dashPayConfig.observe(DashPayConfig.INVITATION_FROM_ONBOARDING)
            .onEach {
                fromOnboarding = it ?: false
            }
            .launchIn(viewModelScope)
    }

    suspend fun handleInvite(intent: Intent): InvitationLinkData? = withContext(Dispatchers.IO) {
        val data = intent.data
        log.info("Processing intent data: $data")

        if (data != null) {
            // Check for AppsFlyer deep link parameter
            val deepLinkValue = data.getQueryParameter("af_dp")
            log.info("AppsFlyer deep_link_value: $deepLinkValue")

            if (deepLinkValue != null) {
                val link = Uri.parse(deepLinkValue)
                if (InvitationLinkData.isValid(link)) {
                    log.info("received valid invite from AppsFlyer: $link")
                    return@withContext InvitationLinkData(link)
                } else {
                    log.warn("Invalid invitation link from AppsFlyer: $link")
                }
            } else {
                // Check if the intent data itself is a valid invitation link
                if (InvitationLinkData.isValid(data)) {
                    log.info("received direct invite link: $data")
                    return@withContext InvitationLinkData(data)
                } else {
                    log.warn("Intent data is not a valid invitation link: $data")
                }
            }
        } else {
            log.warn("Intent data is null")
        }

        log.info("No valid invite found in intent")
        null
    }

    /**
     * Stores [invitation] as THE pending invite, replacing any earlier one, and
     * publishes it unvalidated under a new generation so it is (re)validated
     * and the user gets a verdict for it — even when the same link is opened
     * again, whose stored copy may carry a verdict that was never shown (e.g.
     * it was reached while the lock screen was up).
     */
    suspend fun setInvitationLink(invitation: InvitationLinkData, fromOnboarding: Boolean) {
        storeMutex.withLock {
            dashPayConfig.set(DashPayConfig.INVITATION_LINK, invitation.link.toString())
            dashPayConfig.set(DashPayConfig.INVITATION_FROM_ONBOARDING, fromOnboarding)
            _pendingInvitation.value = PendingInvitation(++generation, InvitationLinkData(invitation.link))
        }
    }

    /**
     * True while an identity is being created FROM AN INVITE (in progress or
     * stopped on an error the user can retry). Only then is a newly opened
     * invite answered with "DashPay is currently processing an invite"; in
     * every other case the new invite replaces any stored one and goes
     * through [validateInvitation], which answers a wallet that already has a
     * username with ALREADY_HAS_IDENTITY.
     *
     * Read from the store rather than [blockchainIdentity], which is posted
     * asynchronously and may not be populated yet on a cold start from a link.
     */
    suspend fun isInviteClaimInProgress(): Boolean = withContext(Dispatchers.IO) {
        inviteClaimInProgress(blockchainIdentityDataDao.load())
    }

    /**
     * Discards the pending invite. With [expectedGeneration], only if the
     * pending invite is still that exact one (same generation, and the
     * persisted link has not been changed elsewhere and not yet observed
     * here): a verdict reached for invite A must not delete an invite that
     * replaced A — even a reopened A — while A was being validated or its
     * dialog was up. Without it (the user dismissing the tile) it clears
     * whatever is stored.
     *
     * @return true if the invite was cleared
     */
    suspend fun clearInvitation(expectedGeneration: Long? = null): Boolean {
        storeMutex.withLock {
            if (expectedGeneration != null) {
                val current = _pendingInvitation.value
                val stored = dashPayConfig.get(DashPayConfig.INVITATION_LINK)
                if (current?.generation != expectedGeneration || stored != current.data.link.toString()) {
                    log.info("not clearing the invite: it was replaced after it was validated")
                    return false
                }
            }
            dashPayConfig.set(DashPayConfig.INVITATION_LINK, "")
            dashPayConfig.set(DashPayConfig.INVITATION_FROM_ONBOARDING, false)
            if (_pendingInvitation.value != null) {
                _pendingInvitation.value = null
                generation++
            }
            return true
        }
    }

    /**
     * Validates the current invite and publishes the verdict.
     *
     * Several validations of the same invite can overlap (the home screen
     * drives one from the invite feed and one from the sync-state feed), and
     * around sync completion one may see the chain unsynced while another
     * sees it synced. So a verdict is published only if
     *  - the invite is still the same generation (not replaced, reopened or
     *    cleared meanwhile), and
     *  - no definitive verdict has been published for that generation yet:
     *    the provisional [InvitationValidationState.NOT_SYNCED] can be
     *    replaced by a definitive verdict, never the other way round, and the
     *    first definitive verdict is final (so its dialog is shown once).
     *
     * @return the published invite, for the caller to show its verdict; null
     * if this call's verdict was discarded (the invite it validated was
     * superseded, or another validation already settled it).
     */
    suspend fun validateInvitation(): PendingInvitation? = withContext(Dispatchers.IO) {
        val snapshot = _pendingInvitation.value ?: return@withContext null
        val invite = snapshot.data
        // Read the identity straight from the store: the LiveData behind
        // hasIdentity/inVotingPeriod is posted asynchronously, and on a cold
        // start from an invite link validation can run before it is populated.
        // A wallet that already has a username was then treated as having
        // none, the invite was validated against the network instead (VALID),
        // and that verdict latched — the invite was never cleared, so every
        // later invite hit the "currently processing an invite" dialog.
        val identity = blockchainIdentityDataDao.load()
        val hasIdentity = identity?.hasUsername ?: false
        val inVotingPeriod = identity?.votingInProgress ?: false

        // The invite that created THIS wallet's identity is not an error
        // to report back to its owner — that claim succeeded. It can
        // still arrive again long afterwards, because the identity keeps
        // its own copy of the link (BlockchainIdentityConfig.INVITE_LINK,
        // deliberately never cleared) and because a launching intent is
        // replayed whenever the task is recreated. Drop it silently
        // instead of telling the user they cannot claim the invite they
        // already claimed.
        if (invite.isSameInvitation(identity?.invite)) {
            log.info("dropping the invite that created this wallet's identity")
            clearInvitation(expectedGeneration = snapshot.generation)
            return@withContext null
        }

        val verdict = if (hasIdentity || inVotingPeriod) {
            // we have an identity, don't check the validity
            invite.validate(
                if (inVotingPeriod) {
                    InvitationValidationState.ALREADY_HAS_REQUESTED_USERNAME
                } else {
                    InvitationValidationState.ALREADY_HAS_IDENTITY
                }
            )
        } else {
            try {
                // Post-cutover, L1 sync is driven by the SDK, so dashj's peerGroup.syncStage
                // never advances to BLOCKS. Gate on the real synced signal (percentageSync==100)
                // that the rest of the UI already uses, otherwise the invite stays NOT_SYNCED forever.
                val isValid = if (blockchainStateProvider.getState()?.isSynced() == true) {
                    topUpRepository.validateInvitation(invite)
                } else {
                    null
                }
                when (isValid) {
                    null -> invite.copy(isValid = null).validate(InvitationValidationState.NOT_SYNCED)
                    true -> invite.copy(isValid = true).validate(InvitationValidationState.VALID)
                    false -> {
                        log.info("invite already claimed?")
                        invite.copy(isValid = false).validate(InvitationValidationState.ALREADY_CLAIMED)
                    }
                }
            } catch (e: Exception) {
                log.info("error validating invite:", e)
                invite.validate(InvitationValidationState.INVALID)
            }
        }
        publishVerdict(snapshot.generation, verdict)
    }

    private suspend fun publishVerdict(forGeneration: Long, verdict: InvitationLinkData): PendingInvitation? =
        storeMutex.withLock {
            val current = _pendingInvitation.value
            when {
                current?.generation != forGeneration -> {
                    log.info("discarding the verdict for an invite that was replaced during validation")
                    null
                }
                current.data.validationState.let { it != null && it != InvitationValidationState.NOT_SYNCED } -> {
                    log.info(
                        "discarding {} verdict: invite already settled as {}",
                        verdict.validationState,
                        current.data.validationState
                    )
                    null
                }
                else -> PendingInvitation(forGeneration, verdict).also { _pendingInvitation.value = it }
            }
        }
}

/**
 * The pending invite together with the generation it was stored under; see
 * [InviteHandlerViewModel.pendingInvitation].
 */
data class PendingInvitation(val generation: Long, val data: InvitationLinkData)

/**
 * Pure rule behind [InviteHandlerViewModel.isInviteClaimInProgress]: an
 * identity record that was started from an invite and has neither finished
 * (VOTING or later) nor been reset.
 */
internal fun inviteClaimInProgress(identity: BlockchainIdentityBaseData?): Boolean =
    identity != null &&
        identity.usingInvite &&
        identity.creationState > IdentityCreationState.NONE &&
        identity.creationState < IdentityCreationState.VOTING
