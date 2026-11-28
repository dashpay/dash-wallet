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
package de.schildbach.wallet.service.platform.sdk;

import org.dashfoundation.dashsdk.ffi.WalletManagerNative;
import org.dashfoundation.dashsdk.wallet.PlatformWalletManager;

import java.lang.reflect.Field;

/**
 * Bridge to the SDK's masternode-by-voting-key DML lookup —
 * {@code WalletManagerNative.masternodesByVotingKey(managerHandle, votingKeyId)}
 * — the post-cutover replacement for dashj's
 * {@code MasternodeListManager.getMasternodesByVotingKey} used by
 * contested-username voting.
 *
 * <p>This is a JAVA class on purpose (same pattern as
 * {@link CoreSendAllNative}): {@code WalletManagerNative} is {@code internal}
 * to the SDK module, so Kotlin refuses the reference via Kotlin metadata, but
 * at the JVM level the object class and its {@code external fun} members are
 * plain public ({@code public final native byte[] masternodesByVotingKey(long,
 * byte[])} — verified with javap against the pinned v41int10 AAR, no
 * {@code $sdk_release} mangling because the member itself is public inside the
 * internal object) and javac links against the bytecode directly. If a future
 * AAR renames or hides the member, this class fails to COMPILE — a loud,
 * pre-runtime canary.
 *
 * <p>The manager handle is NOT reachable by direct linkage:
 * {@code PlatformWalletManager.managerHandle} is a {@code private} field and
 * its bytecode accessor ({@code access$getManagerHandle$p}) is
 * ACC_SYNTHETIC, which javac refuses to resolve. The one remaining route is
 * reflection on the private {@code long} field — cached, and validated to
 * fail loudly (so the Kotlin seam's fail-soft catch logs it) rather than
 * passing a garbage handle across JNI. The handle is valid for the manager's
 * open lifetime (the SDK's own methods pass the same raw long with no extra
 * fencing); callers must hold a live manager from
 * {@code DashSdkService.walletManagerOrNull()}.
 */
final class MasternodeVotingNative {

    private static volatile Field managerHandleField;

    private MasternodeVotingNative() {}

    /**
     * The proTxHashes of every masternode in the SDK's current-tip
     * deterministic masternode list whose voting-key hash matches
     * {@code votingKeyId} (20-byte hash160 of the voting public key), as a
     * flat array of concatenated 32-byte proTxHashes in INTERNAL (wire) byte
     * order. Empty (non-null) when the DML hasn't synced or no masternode
     * uses the key.
     *
     * @throws ReflectiveOperationException if the private
     *     {@code managerHandle} field disappeared (AAR layout change)
     * @throws IllegalStateException if the reflected handle is zero
     */
    static byte[] masternodesByVotingKey(PlatformWalletManager manager, byte[] votingKeyId)
            throws ReflectiveOperationException {
        Field field = managerHandleField;
        if (field == null) {
            field = PlatformWalletManager.class.getDeclaredField("managerHandle");
            field.setAccessible(true);
            managerHandleField = field;
        }
        final long managerHandle = field.getLong(manager);
        if (managerHandle == 0L) {
            throw new IllegalStateException("PlatformWalletManager.managerHandle is 0");
        }
        return WalletManagerNative.INSTANCE.masternodesByVotingKey(managerHandle, votingKeyId);
    }
}
