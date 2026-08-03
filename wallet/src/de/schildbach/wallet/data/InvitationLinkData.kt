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

package de.schildbach.wallet.data

import android.net.Uri
import android.os.Parcelable
import de.schildbach.wallet.Constants
import kotlinx.parcelize.IgnoredOnParcel
import kotlinx.parcelize.Parcelize
import org.bitcoinj.evolution.AssetLockTransaction
import org.bouncycastle.crypto.params.KeyParameter
import java.util.concurrent.TimeUnit
import androidx.core.net.toUri

enum class InvitationValidationState {
    /** there is no invitation present */
    NONE,
    /** the invitation is valid and can be used */
    VALID,
    /** this user already has an identity */
    ALREADY_HAS_IDENTITY,
    /** this user already has an identity and is requesting a username */
    ALREADY_HAS_REQUESTED_USERNAME,
    /** this invitation has already been claimed */
    ALREADY_CLAIMED,
    /** this invitation is not valid (malformed) */
    INVALID,
    /** the blockchain has not been synced, cannot check invite validity */
    NOT_SYNCED
}

@Parcelize
data class InvitationLinkData(
    val link: Uri,
    val isValid: Boolean? = null,
    val validationState: InvitationValidationState? = null,
    val validationTimestamp: Long? = null
) : Parcelable {
    companion object {
        private const val URI_PREFIX = "dashpay://invite"
        private const val PARAM_USER = "du"
        private const val PARAM_DISPLAY_NAME = "display-name"
        private const val PARAM_AVATAR_URL = "avatar-url"
        private const val PARAM_CFTX = "assetlocktx"
        private const val PARAM_PRIVATE_KEY = "pk"
        private const val PARAM_IS_LOCK = "islock"

        /**
         * Shielded (L2) invitation params — the private-invitation variant
         * (iOS-parity). Instead of a pre-created L1 asset lock ([PARAM_CFTX]
         * / [PARAM_PRIVATE_KEY] / [PARAM_IS_LOCK]), a shielded invite carries
         * a single-use Orchard spending key that the RECEIVER spends on claim
         * to create their identity directly from a shielded note:
         *
         * - [PARAM_ONE_TIME_KEY] (`osk`): the invitation's one-time 32-byte
         *   Orchard spending key, lowercase hex. Unlike the L1 [PARAM_PRIVATE_KEY]
         *   (a Core key in WiF), this is a raw Orchard scalar — WiF/Base58Check
         *   does not apply, so it is carried as hex.
         * - [PARAM_FUNDING_HEIGHT] (`bh`): the block height the funding note
         *   was created at — an advisory scan hint for the claim-side network
         *   scan (absent/blank ⇒ "no hint").
         *
         * An invite is the SHIELDED variant iff [PARAM_ONE_TIME_KEY] is
         * present; the L1 variant is unchanged and both parse (branch via
         * [isShielded]).
         */
        private const val PARAM_ONE_TIME_KEY = "osk"
        private const val PARAM_FUNDING_HEIGHT = "bh"

        /**
         * The shielded invitation's funding NOTE VALUE in Platform credits
         * (`amt`) — the fixed Type-20 exit denomination the inviter actually
         * funded (0.1 DASH = 10_000_000_000 credits non-contested, 0.3 DASH =
         * 30_000_000_000 contested).
         *
         * This exists because the claim side has NO other way to learn the
         * invite's tier. An L1 invite carries its asset-lock txid, so the
         * claimer can read the funded amount off chain; a shielded invite
         * carries only a one-time Orchard spending key, and the SDK's claim
         * FFI (`shieldedIdentityCreateFromOneTimeKey`) takes the denomination
         * as an INPUT — it never reports the note's value. Without this
         * parameter the claim screen cannot tell a 0.3 (contested) invite from
         * a 0.1 (non-contested) one and wrongly tells every claimer they may
         * only pick a non-contested username.
         *
         * ADVISORY and OPTIONAL: links minted before this parameter existed
         * omit it, and a claimer that cannot read a tier must not assert one
         * (see `InviteUsernameTier.UNKNOWN`). Two readers, neither of which
         * trusts it with funds it does not have:
         *
         * - the claim screen's tier UI (notice / requirement rows / submit
         *   gate);
         * - the claim itself, which requests the claimed value as the
         *   Type-20 denomination so the new IDENTITY receives the invite's
         *   full note value in credits regardless of the username tier the
         *   claimer picks (`SdkShieldedUsernameCreation
         *   .createIdentityFromInvitation`). Safe against tampering because
         *   the note itself is what the claim spends and the FFI fails
         *   CLOSED, pre-broadcast, when no note covers the requested
         *   denomination: a lying-high or junk `amt` just downgrades to the
         *   same descending-ladder attempt order a link without `amt` gets
         *   (0.3 → 0.1), and nothing is spent by a refused attempt. A
         *   lying-LOW `amt` (0.1 written onto a 0.3 invite) is believed —
         *   indistinguishable from a genuine 0.1 invite — and makes the
         *   claim spend the 0.3 note at the 0.1 denomination with the 0.2
         *   difference going to the CLAIMER's own Orchard change address.
         *   That moves no value to a third party: only the claimer benefits
         *   from tampering their own bearer link, and they were receiving
         *   the full value either way (as spendable shielded DASH instead
         *   of identity credits).
         */
        private const val PARAM_FUNDING_CREDITS = "amt"
        private val VALIDATION_EXPIRED = TimeUnit.MINUTES.toMillis(1)

        fun create(username: String, displayName: String, avatarUrl: String, cftx: AssetLockTransaction, aesKeyParameter: KeyParameter): InvitationLinkData {
            val privateKey = cftx.assetLockPublicKey.decrypt(aesKeyParameter)
            val linkBuilder = URI_PREFIX.toUri().buildUpon()
                .appendQueryParameter(PARAM_USER, username)
                .appendQueryParameter(PARAM_CFTX, cftx.txId.toString())
                .appendQueryParameter(PARAM_PRIVATE_KEY, privateKey.getPrivateKeyAsWiF(Constants.NETWORK_PARAMETERS))
                .appendQueryParameter(PARAM_IS_LOCK, cftx.confidence.instantSendlock?.toStringHex())

            if (displayName.isNotEmpty()) {
                linkBuilder.appendQueryParameter(PARAM_DISPLAY_NAME, displayName)
            }
            if (avatarUrl.isNotEmpty()) {
                linkBuilder.appendQueryParameter(PARAM_AVATAR_URL, avatarUrl)
            }
            return InvitationLinkData(linkBuilder.build(), null)
        }

        /**
         * Build the SHIELDED (L2) invitation link: the private-invitation
         * counterpart of [create]. Carries the one-time Orchard spending key
         * [oneTimeKeyHex] (lowercase hex of the 32-byte scalar) and the
         * funding note's [fundingHeight]; it DROPS the L1 asset-lock params
         * ([PARAM_CFTX] / [PARAM_PRIVATE_KEY] / [PARAM_IS_LOCK]) entirely.
         * There is no pre-created identity — the receiver creates theirs on
         * claim from the note funded to [oneTimeKeyHex].
         *
         * [fundingCredits] is the note's value in Platform credits — the tier
         * hint the claim screen needs (see [PARAM_FUNDING_CREDITS]). Omitted
         * from the link when null or non-positive.
         */
        fun createShielded(
            username: String,
            displayName: String,
            avatarUrl: String,
            oneTimeKeyHex: String,
            fundingHeight: Int,
            fundingCredits: Long? = null
        ): InvitationLinkData {
            val linkBuilder = URI_PREFIX.toUri().buildUpon()
                .appendQueryParameter(PARAM_USER, username)
                .appendQueryParameter(PARAM_ONE_TIME_KEY, oneTimeKeyHex)
                .appendQueryParameter(PARAM_FUNDING_HEIGHT, fundingHeight.toString())

            if (fundingCredits != null && fundingCredits > 0) {
                linkBuilder.appendQueryParameter(PARAM_FUNDING_CREDITS, fundingCredits.toString())
            }
            if (displayName.isNotEmpty()) {
                linkBuilder.appendQueryParameter(PARAM_DISPLAY_NAME, displayName)
            }
            if (avatarUrl.isNotEmpty()) {
                linkBuilder.appendQueryParameter(PARAM_AVATAR_URL, avatarUrl)
            }
            return InvitationLinkData(linkBuilder.build(), null)
        }

        /**
         * True when [link] is a well-formed invitation of EITHER variant —
         * the unchanged L1 asset-lock form ([isValidL1]) or the shielded L2
         * form ([isValidShielded]). Branch on presence of `osk` vs `pk`.
         */
        fun isValid(link: Uri): Boolean = isValidShielded(link) || isValidL1(link)

        /** The unchanged L1 asset-lock invitation validity check. */
        private fun isValidL1(link: Uri): Boolean {
            return try {
                val queryParams = link.queryParameterNames
                    queryParams.contains(PARAM_USER) &&
                    queryParams.contains(PARAM_PRIVATE_KEY) &&
                    queryParams.contains(PARAM_IS_LOCK) &&
                    queryParams.contains(PARAM_CFTX) &&
                    !link.getQueryParameter(PARAM_USER).isNullOrBlank() &&
                    !link.getQueryParameter(PARAM_PRIVATE_KEY).isNullOrBlank() &&
                    !link.getQueryParameter(PARAM_IS_LOCK).isNullOrBlank() &&
                    !link.getQueryParameter(PARAM_CFTX).isNullOrBlank()
            } catch (e: Exception) {
                false
            }
        }

        /**
         * The shielded (L2) invitation validity check: a user and a
         * non-blank one-time key. The funding height is advisory, so a
         * missing/blank `bh` does not invalidate the link.
         */
        private fun isValidShielded(link: Uri): Boolean {
            return try {
                val queryParams = link.queryParameterNames
                queryParams.contains(PARAM_USER) &&
                    queryParams.contains(PARAM_ONE_TIME_KEY) &&
                    !link.getQueryParameter(PARAM_USER).isNullOrBlank() &&
                    !link.getQueryParameter(PARAM_ONE_TIME_KEY).isNullOrBlank()
            } catch (e: Exception) {
                false
            }
        }
    }

    @IgnoredOnParcel
    val user by lazy {
        link.getQueryParameter(PARAM_USER)!!
    }

    @IgnoredOnParcel
    val displayName by lazy {
        link.getQueryParameter(PARAM_DISPLAY_NAME) ?: user
    }

    @IgnoredOnParcel
    val avatarUrl by lazy {
        link.getQueryParameter(PARAM_AVATAR_URL)?.run {
            Uri.decode(this)
        } ?: ""
    }

    @IgnoredOnParcel
    val assetLockTx by lazy {
        link.getQueryParameter(PARAM_CFTX)!!.lowercase()
    }

    @IgnoredOnParcel
    val privateKey by lazy {
        link.getQueryParameter(PARAM_PRIVATE_KEY)!!
    }

    @IgnoredOnParcel
    val instantSendLock by lazy {
        link.getQueryParameter(PARAM_IS_LOCK)!!.lowercase()
    }

    /**
     * Whether this is the SHIELDED (L2) invitation variant — carries a
     * one-time Orchard key (`osk`) instead of an L1 asset lock. The claim
     * path branches on this: shielded links go through
     * `shieldedIdentityCreateFromOneTimeKey`, L1 links through the
     * asset-lock claim. Only the L1 accessors ([assetLockTx], [privateKey],
     * [instantSendLock]) are safe when this is false; [oneTimeKey] /
     * [fundingHeight] only when it is true.
     */
    @IgnoredOnParcel
    val isShielded: Boolean by lazy {
        !link.getQueryParameter(PARAM_ONE_TIME_KEY).isNullOrBlank()
    }

    /** The one-time 32-byte Orchard spending key, lowercase hex (L2 only). */
    @IgnoredOnParcel
    val oneTimeKey by lazy {
        link.getQueryParameter(PARAM_ONE_TIME_KEY)!!.lowercase()
    }

    /**
     * The funding note's block height (L2 advisory scan hint), or null when
     * absent/unparseable — the claim FFI treats null as "no hint".
     */
    @IgnoredOnParcel
    val fundingHeight: Int? by lazy {
        link.getQueryParameter(PARAM_FUNDING_HEIGHT)?.toIntOrNull()
    }

    /**
     * The shielded funding note's value in Platform credits (L2 only), or
     * null when the link does not carry it — either because it is an L1
     * invite or because it was minted before [PARAM_FUNDING_CREDITS] existed.
     * Null means "tier unknown", NOT "non-contested": callers must not
     * assert a tier they cannot read (see [PARAM_FUNDING_CREDITS]).
     * Non-positive/unparseable values are treated as absent.
     */
    @IgnoredOnParcel
    val shieldedFundingCredits: Long? by lazy {
        link.getQueryParameter(PARAM_FUNDING_CREDITS)?.toLongOrNull()?.takeIf { it > 0 }
    }

    @Deprecated("use link")
    fun getUri(): Uri = "https://invitations.dashpay.io/applink".toUri().buildUpon()
        .appendQueryParameter(PARAM_USER, user)
        .appendQueryParameter(PARAM_DISPLAY_NAME, displayName)
        .appendQueryParameter(PARAM_AVATAR_URL, avatarUrl)
        .appendQueryParameter(PARAM_CFTX, assetLockTx)
        .appendQueryParameter(PARAM_PRIVATE_KEY, privateKey)
        .appendQueryParameter(PARAM_IS_LOCK, instantSendLock)
        .build()

    val expired: Boolean
        get() = validationTimestamp?.let { it < System.currentTimeMillis() - VALIDATION_EXPIRED } ?: true

    fun validate(validationState: InvitationValidationState): InvitationLinkData {
        return copy(validationState = validationState, validationTimestamp = System.currentTimeMillis())
    }
}
