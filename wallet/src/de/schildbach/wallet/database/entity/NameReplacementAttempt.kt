/*
 * Copyright 2026 Dash Core Group
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

package de.schildbach.wallet.database.entity

import org.bitcoinj.core.Coin

enum class NameReplacementState { APPROVED, DISPATCHING, DISPATCHED, NAME_SAVED }

enum class NameReplacementFailure { RETRYABLE, FINAL }

/**
 * The user's approval to replace a rejected name of an invite-funded identity, and how far it got.
 * A zero [approvedTopUp] is paid from the identity's credits; otherwise the wallet tops up the identity first.
 */
data class NameReplacementAttempt(
    val id: String,
    val identityId: String,
    val username: String,
    val verificationLink: String?,
    val approvedTopUp: Coin,
    val state: NameReplacementState = NameReplacementState.APPROVED,
    val failure: NameReplacementFailure? = null,
    val failureMessage: String? = null,
    val topUpKeyHash: ByteArray? = null,
    val topUpTx: ByteArray? = null,
    val rejectedState: IdentityCreationState? = null,
    val rejectedError: String? = null
) {
    val paidFromCredits: Boolean
        get() = approvedTopUp.isZero

    /** Only an attempt with no top-up in flight may give way to a new approval. */
    val canBeReplaced: Boolean
        get() = failure == NameReplacementFailure.FINAL ||
            (
                failure == NameReplacementFailure.RETRYABLE &&
                    (state == NameReplacementState.APPROVED || state == NameReplacementState.NAME_SAVED)
                )
}

interface NameReplacementStore {
    /** Throws on a storage error rather than reporting that there is no attempt. */
    suspend fun loadReplacement(): NameReplacementAttempt?

    /** Saves [attempt] only for a failed name with nothing in flight, and moves the identity error into it. */
    suspend fun approveReplacement(attempt: NameReplacementAttempt): Boolean

    suspend fun markReplacementDispatching(id: String, topUpKeyHash: ByteArray): NameReplacementAttempt?

    suspend fun markReplacementDispatched(id: String, topUpTx: ByteArray): NameReplacementAttempt?

    /** Saves the replacement name, its verification link and the registration checkpoint in one write. */
    suspend fun saveReplacementName(id: String, from: NameReplacementState): NameReplacementAttempt?

    suspend fun failReplacement(id: String, failure: NameReplacementFailure, message: String): Boolean

    suspend fun clearReplacementFailure(id: String): NameReplacementAttempt?

    suspend fun completeReplacement(id: String)
}
