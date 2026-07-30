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

package de.schildbach.wallet.service.platform

/**
 * Tolerates the legacy dashj-platform (org.dashj.platform 4.0.0-RC2) identity
 * cache being unable to serialize an identity whose keys carry contract bounds.
 *
 * `Platform.identities.get(id)` (dashj `Identities.get` →
 * `PlatformStateRepository.fetchIdentity`) first FETCHES the identity from the
 * DAPI client and only THEN writes it into the in-memory identity cache via
 * `storeIdentity`, which CBOR-encodes the identity through
 * `org.dashj.platform.dpp.util.Cbor`. When a key on the identity carries a
 * `SingleContractDocumentType` bound, that encoder has no converter for it and
 * throws `IllegalArgumentException("No converter for ...")` (Cbor.kt:186). The
 * throw happens AFTER the fetch succeeded but BEFORE `fetchIdentity` returns,
 * so the caller never receives the perfectly-good identity.
 *
 * The trigger is NOT "an iOS username": the shipped iOS DashPay wallet
 * registers no contract bounds. It is any v4.1-era identity WITH contract-bound
 * keys — and, critically, that now INCLUDES the wallet's own newly-registered
 * 6-key identities, whose keys 4/5 carry
 * `SingleContractDocumentType(dashpay, "contactRequest")`. So the failure is no
 * longer limited to foreign identities: profile synthesis, invitation/top-up
 * status updates, and the accept/receive contact-request flow
 * (`PlatformBroadcastService.sendContactRequest` — which is also the reciprocal
 * broadcast used to ACCEPT an incoming request — and the
 * `updateContactRequests` reconcile loop) can all hit it on the app's own
 * identity.
 *
 * The cache is a pure optimization; nothing the contact-request flow does
 * depends on it. [fetchIdentityToleratingCacheError] runs the cached get and,
 * ONLY on that specific CBOR cache failure, refetches via a cache-bypassing
 * path (dashj `DapiClient.getIdentity`, which never calls `storeIdentity`),
 * recovering the identity. Any other failure propagates unchanged so real
 * fetch errors are never masked.
 *
 * Kept as top-level pure functions so the tolerance logic is unit-testable on
 * the host JVM without any native, network, or Android dependency.
 */

/**
 * True when [t] is the legacy identity-cache CBOR serialization failure raised
 * while caching a fetched v4.1 identity (see the file KDoc). Matched on the
 * dashj `Cbor` message because the library throws a plain
 * `IllegalArgumentException` with no typed marker.
 */
internal fun isLegacyIdentityCacheCborFailure(t: Throwable): Boolean =
    t is IllegalArgumentException && t.message?.contains("No converter for") == true

/**
 * Run [cachedGet]; if it fails with the legacy identity-cache CBOR failure
 * ([isLegacyIdentityCacheCborFailure]) — the identity was fetched but could
 * not be cached — recover it via [cacheBypassingFetch]. Every other throwable
 * propagates unchanged.
 */
internal inline fun <T> fetchIdentityToleratingCacheError(
    cachedGet: () -> T,
    cacheBypassingFetch: () -> T
): T = try {
    cachedGet()
} catch (e: IllegalArgumentException) {
    if (isLegacyIdentityCacheCborFailure(e)) {
        cacheBypassingFetch()
    } else {
        throw e
    }
}
