/*
 * Copyright 2026 Dash Core Group.
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

package de.schildbach.wallet.data

import org.bitcoinj.core.AddressFormatException
import org.bitcoinj.core.Base58
import java.net.URI
import java.net.URISyntaxException
import java.net.URLDecoder

/**
 * QR/link payload identifying a DashPay user: the Platform identity id (base58)
 * plus the preferred DPNS username, encoded as
 *
 *     dashpay://user?id=<base58-identity-id>&username=<label>
 *
 * Rendered as "my QR code" on the add-contact screen and parsed back when
 * another user scans it. Parsing is pure and offline; it proves nothing — the
 * username claim must be verified against Platform (DPNS lookup, identity id
 * match) before it is shown as that user.
 *
 * This is the shared wire contract with iOS `DashPayUserLink.swift`; keep the
 * parsers (and the test vectors in `DashPayUserLinkTest`) in sync between the
 * two repositories.
 */
data class DashPayUserLink(
    /** Base58 form of the 32-byte Platform identity id. */
    val userId: String,
    /** Preferred DPNS label, stored without the ".dash" suffix. */
    val username: String
) {
    /**
     * Canonical URI form — the QR payload. Values are interpolated without
     * percent-encoding: base58 ids and DPNS labels contain no reserved URI
     * characters, so this stays byte-identical to the iOS URLComponents output.
     */
    val uriString: String
        get() = "$SCHEME://$HOST?$PARAM_ID=$userId&$PARAM_USERNAME=$username"

    companion object {
        private const val SCHEME = "dashpay"
        private const val HOST = "user"
        private const val PARAM_ID = "id"
        private const val PARAM_USERNAME = "username"
        private const val DASH_SUFFIX = ".dash"
        private const val IDENTIFIER_LENGTH = 32

        /** DPNS labels render without the implied ".dash" parent domain. */
        fun stripDashSuffix(label: String): String =
            if (label.endsWith(DASH_SUFFIX)) label.dropLast(DASH_SUFFIX.length) else label

        /**
         * Parse a scanned string, enforcing the canonical `dashpay://user` shape
         * strictly so the cross-platform wire contract stays unambiguous:
         * scheme/host/parameter names are matched case-insensitively, but
         * userinfo, port, path, fragment, duplicate parameters, and unsupported
         * parameters are all rejected. `id` must decode to a 32-byte base58
         * identifier and `username` must be non-empty (a trailing ".dash" is
         * tolerated and stripped). Anything else — payment URIs, invitation
         * links, bare usernames — returns null.
         */
        fun parse(input: String): DashPayUserLink? {
            val uri = try {
                URI(input.trim())
            } catch (e: URISyntaxException) {
                return null
            }
            if (!SCHEME.equals(uri.scheme, ignoreCase = true) ||
                !HOST.equals(uri.host, ignoreCase = true) ||
                uri.userInfo != null ||
                uri.port != -1 ||
                !uri.path.isNullOrEmpty() ||
                uri.fragment != null
            ) {
                return null
            }

            var id: String? = null
            var username: String? = null
            val seenNames = hashSetOf<String>()
            val rawQuery = uri.rawQuery ?: ""
            if (rawQuery.isNotEmpty()) {
                for (param in rawQuery.split("&")) {
                    val name = percentDecode(param.substringBefore('='))?.lowercase() ?: return null
                    val value = percentDecode(param.substringAfter('=', "")) ?: return null
                    if (!seenNames.add(name)) {
                        return null
                    }
                    when (name) {
                        PARAM_ID -> id = value
                        PARAM_USERNAME -> username = value
                        else -> return null
                    }
                }
            }

            val identityId = id?.let {
                try {
                    Base58.decode(it)
                } catch (e: AddressFormatException) {
                    null
                }
            }
            if (identityId == null || identityId.size != IDENTIFIER_LENGTH) {
                return null
            }
            val label = stripDashSuffix((username ?: "").trim())
            if (label.isEmpty()) {
                return null
            }
            return DashPayUserLink(Base58.encode(identityId), label)
        }

        // URLDecoder turns '+' into a space, which neither iOS URLComponents nor
        // this contract does — protect literal plus signs before decoding.
        private fun percentDecode(component: String): String? = try {
            URLDecoder.decode(component.replace("+", "%2B"), "UTF-8")
        } catch (e: IllegalArgumentException) {
            null
        }
    }
}
