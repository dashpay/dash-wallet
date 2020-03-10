/*
 * Copyright 2020 Dash Core Group
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

package org.dash.android.lightpayprot

/**
 * <p>Utility methods and constants for working with <a href="https://github.com/bitcoin/bips/blob/master/bip-0070.mediawiki">
 * BIP 270 aka the simplified payment protocol</a>. These are low level wrappers around the protocol buffers. If you're implementing
 * a wallet app, look at {@link PaymentSession} for a higher level API that should simplify working with the protocol.</p>
 *
 * <p>BIP 70 defines a binary, protobuf based protocol that runs directly between sender and receiver of funds. Payment
 * protocol data does not flow over the Bitcoin P2P network or enter the block chain. It's instead for data that is only
 * of interest to the parties involved but isn't otherwise needed for consensus.</p>
 */
class SimplifiedPaymentProtocol {

    companion object {
        // MIME types as defined in BIP271.
        const val MIME_TYPE_PAYMENT_REQUEST = "application/dash-paymentrequest"
        const val MIME_TYPE_PAYMENT = "application/dash-payment"
        const val MIME_TYPE_PAYMENT_ACK = "application/dash-paymentack"

        const val PAY_URI_SCHEME = "pay"
    }


}