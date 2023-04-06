/*
 * Copyright 2023 Dash Core Group
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

package de.schildbach.wallet.ui.more.masternode_keys

import org.bitcoinj.crypto.IKey

data class MasternodeKeyInfo(
    val masternodeKey: IKey,
    val privateKeyHex: String? = null,
    val privateKeyWif: String? = null,
    val privatePublicKeyBase64: String? = null,
)
