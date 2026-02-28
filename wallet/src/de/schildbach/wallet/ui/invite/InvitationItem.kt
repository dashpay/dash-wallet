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

package de.schildbach.wallet.ui.invite

import de.schildbach.wallet.database.entity.Invitation
import de.schildbach.wallet.util.PlatformUtils
import org.bitcoinj.core.Sha256Hash

data class InvitationItem(val type: Int,
                          val invitation: Invitation? = null,
                          val uniqueIndex: Int = 0) {

    val id: Long by lazy {
        if (invitation != null) {
            PlatformUtils.longHashFromEncodedString(invitation.userId)
        } else {
            val bytes = Sha256Hash.ZERO_HASH.bytes
            bytes[0] = type.toByte()
            PlatformUtils.longHash(Sha256Hash.wrap(bytes))
        }
    }
}