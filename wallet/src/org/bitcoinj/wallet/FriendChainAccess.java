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

import java.util.Arrays;

import org.bitcoinj.crypto.ChildNumber;
import org.bitcoinj.crypto.DeterministicKey;
import org.bitcoinj.crypto.HDKeyDerivation;
import org.bitcoinj.evolution.EvolutionContact;

/**
 * Access to a DIP-15 SENDING friendship chain: READ-ONLY for its extended public
 * key ({@link #sendingExtendedPublicKeyOrNull}), plus one deliberate write —
 * {@link #markSendingAddressUsed}, which advances the chain past a key the SDK paid.
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

    /** Outcome of {@link #markSendingAddressUsed}. */
    public enum MarkResult {
        /** The key was marked used and dashj's current sending key moved past it. */
        MARKED,
        /** dashj's current sending key was already past the key — nothing changed. */
        ALREADY_BEHIND_CURRENT,
        /** This wallet holds no SENDING chain for {@code contact}. */
        NO_CHAIN,
        /**
         * No key on the chain has that hash, up to {@link #MAX_FORWARD_SCAN} keys past
         * the issued ones — another contact's address, or another account reference's.
         */
        KEY_NOT_ON_CHAIN
    }

    /**
     * How far past dashj's issued keys {@link #markSendingAddressUsed} looks for a
     * paid key it has not derived yet. The SDK picks the lowest unused address of its
     * own pool for the contact, so its lead over dashj is the payments dashj missed
     * plus the pool's gap — far below this.
     */
    static final int MAX_FORWARD_SCAN = 100;

    /**
     * THE ONE WRITE in this class: records that the SENDING chain key with
     * {@code pubKeyHash} has been paid, so dashj's next
     * {@code currentAddress(contact, SENDING_CHAIN)} returns a later key.
     *
     * <h2>Why this is needed</h2>
     *
     * dashj only advances a contact's current sending key when it SEES a transaction
     * paying it ({@code Wallet.markKeysAsUsed}, private, run from {@code commitTx} /
     * {@code receive}). After the SDK cutover the SDK broadcasts and the dashj wallet
     * is held, so it never sees the payment and keeps handing out the same address.
     *
     * <h2>What it does</h2>
     *
     * Under {@code keyChainGroupLock} (the lock {@code markKeysAsUsed} takes):
     * <ol>
     *   <li>marks the key used on THIS contact's chain only, which raises the chain's
     *       issued-key count past it, as {@code markKeysAsUsed} would. A key the SDK
     *       chose can be beyond what dashj has derived (a sending chain keeps little or
     *       no lookahead); it is then found by deriving forward from the chain's public
     *       key and issued up to, so it can be marked;</li>
     *   <li>if dashj's current key for the chain is unset, or is at or below the paid
     *       key's index, issues the next key and makes it current. The paid key can be
     *       AHEAD of dashj's current key when the SDK chose it, and on reload dashj
     *       rebuilds "current" as the last issued key — which, after step 1 alone,
     *       would be the paid key itself;</li>
     *   <li>schedules a wallet save so the advance survives a restart.</li>
     * </ol>
     * Idempotent: a repeat call for the same key finds the current key already past
     * it and changes nothing. Pre-cutover, where dashj already advanced the key when
     * it committed the transaction, it is likewise a no-op.
     *
     * <p>{@code Wallet.sendingToFriendsGroup}, {@code FriendKeyChainGroup.currentContactKeys},
     * {@code Wallet.keyChainGroupLock} and {@code Wallet.saveLater()} are not public,
     * which is why this lives in dashj's package (see the class doc).
     */
    public static MarkResult markSendingAddressUsed(
            Wallet wallet, EvolutionContact contact, byte[] pubKeyHash) {
        wallet.keyChainGroupLock.lock();
        try {
            FriendKeyChainGroup group = wallet.sendingToFriendsGroup;
            if (group == null) {
                return MarkResult.NO_CHAIN;
            }
            FriendKeyChain chain =
                    group.getFriendKeyChain(contact, FriendKeyChain.KeyChainType.SENDING_CHAIN);
            if (chain == null) {
                return MarkResult.NO_CHAIN;
            }
            DeterministicKey paid = chain.markPubHashAsUsed(pubKeyHash);
            if (paid == null) {
                int index = indexAheadOfIssued(chain, pubKeyHash);
                if (index < 0) {
                    return MarkResult.KEY_NOT_ON_CHAIN;
                }
                int missing = index + 1 - chain.getIssuedExternalKeys();
                if (missing > 0) {
                    // Issues (derives and imports) keys up to and including `index`.
                    chain.getKeys(KeyChain.KeyPurpose.RECEIVE_FUNDS, missing);
                }
                paid = chain.markPubHashAsUsed(pubKeyHash);
                if (paid == null) {
                    return MarkResult.KEY_NOT_ON_CHAIN;
                }
            }
            DeterministicKey current = group.currentContactKeys.get(chain.getAccountPath());
            if (current != null
                    && current.getChildNumber().num() > paid.getChildNumber().num()) {
                return MarkResult.ALREADY_BEHIND_CURRENT;
            }
            DeterministicKey next =
                    group.freshKey(contact, FriendKeyChain.KeyChainType.SENDING_CHAIN);
            group.currentContactKeys.put(chain.getAccountPath(), next);
        } finally {
            wallet.keyChainGroupLock.unlock();
        }
        wallet.saveLater();
        return MarkResult.MARKED;
    }

    /**
     * The child index, from the issued count up to {@link #MAX_FORWARD_SCAN} past it,
     * whose key hashes to {@code pubKeyHash}; -1 if none. Pure derivation from the
     * chain's public key — a friendship chain's keys are its direct non-hardened
     * children (see {@code FriendKeyChain.getKeys}). Lower indices are already in the
     * chain, where {@code markPubHashAsUsed} would have found them.
     */
    private static int indexAheadOfIssued(FriendKeyChain chain, byte[] pubKeyHash) {
        DeterministicKey accountKey = chain.getWatchingKey();
        int from = chain.getIssuedExternalKeys();
        for (int i = from; i < from + MAX_FORWARD_SCAN; i++) {
            DeterministicKey child = HDKeyDerivation.deriveChildKey(accountKey, new ChildNumber(i, false));
            if (Arrays.equals(child.getPubKeyHash(), pubKeyHash)) {
                return i;
            }
        }
        return -1;
    }
}
