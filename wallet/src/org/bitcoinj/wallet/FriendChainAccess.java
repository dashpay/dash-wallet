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

package org.bitcoinj.wallet;

import org.bitcoinj.crypto.DeterministicKey;
import org.bitcoinj.evolution.EvolutionContact;

/**
 * READ-ONLY access to a DIP-15 SENDING friendship chain's extended public key.
 *
 * <h2>Why this class lives in dashj's package</h2>
 *
 * dashj publishes {@link Wallet#getReceivingExtendedPublicKey(EvolutionContact)}
 * for the RECEIVING chain but has no equivalent for the SENDING chain: the only
 * public sending-side entry points are {@code currentKey}/{@code currentAddress}/
 * {@code freshKey}, all of which ISSUE — they memoize into
 * {@code FriendKeyChainGroup.currentContactKeys} and can drive lookahead
 * derivation, which moves the issuance counter and the bloom filter. A diagnostic
 * must never do that. {@code Wallet.sendingToFriendsGroup} is package-private and
 * {@code FriendKeyChainGroup.getFriendKeyChain(...)} is public, so declaring this
 * helper in the same package reaches the stored chain with a COMPILE-TIME
 * reference — no reflection, and therefore nothing for R8 to break in a minified
 * build (it renames the field and this reference together).
 *
 * <p>Deriving children from the returned key with {@code HDKeyDerivation} is pure
 * arithmetic over already-published public material; it touches no wallet state.
 *
 * <h2>Direction</h2>
 *
 * The sending chain's keys come from the CONTACT's xpub (published in the request
 * THEY authored to us and decrypted by
 * {@code BlockchainIdentity.addPaymentKeyChainToContact}), so it cannot be
 * re-derived from our seed — the stored chain is the only source. Its path is
 * {@code root / theirAccountReference' / their-id / our-id} (see
 * {@link FriendKeyChain#getContactPath}), which is why the contact key must be
 * built as {@code EvolutionContact(ourId, 0, theirId, theirAccountReference)} —
 * exactly what {@code PlatformSyncService.checkAndAddReceivedRequest} uses when
 * it creates the chain.
 */
public final class FriendChainAccess {

    private FriendChainAccess() {
    }

    /**
     * The extended PUBLIC key of the stored SENDING chain for {@code contact}, or
     * {@code null} when this wallet holds no such chain (no request from that
     * contact yet, or a different account reference). Never issues a key.
     */
    public static DeterministicKey sendingExtendedPublicKeyOrNull(
            Wallet wallet, EvolutionContact contact) {
        FriendKeyChainGroup group = wallet.sendingToFriendsGroup;
        if (group == null) {
            return null;
        }
        FriendKeyChain chain =
                group.getFriendKeyChain(contact, FriendKeyChain.KeyChainType.SENDING_CHAIN);
        return chain == null ? null : chain.getWatchingKey();
    }
}
