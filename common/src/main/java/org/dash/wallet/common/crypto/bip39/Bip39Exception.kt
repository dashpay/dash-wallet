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

package org.dash.wallet.common.crypto.bip39

/**
 * Dashj-free mirror of `org.bitcoinj.crypto.MnemonicException` (dashj 22.0.3).
 * Subtype-for-subtype identical so call sites can translate 1:1.
 */
sealed class Bip39Exception(message: String?) : Exception(message) {

    /** Mirror of `MnemonicException.MnemonicLengthException`: word count is not a multiple of 3 (or empty). */
    class LengthException(message: String) : Bip39Exception(message)

    /** Mirror of `MnemonicException.MnemonicWordException`: [badWord] was not found in the wordlist. */
    class WordException(val badWord: String) : Bip39Exception(badWord)

    /** Mirror of `MnemonicException.MnemonicChecksumException`: the checksum bits do not match. */
    class ChecksumException : Bip39Exception(null)
}
