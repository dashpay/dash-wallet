/*
 * Copyright 2011 Google Inc.
 * Copyright 2018 Andreas Schildbach
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

package org.dash.wallet.common.payments.parsers;

/**
 * Self-contained port of {@code org.bitcoinj.core.AddressFormatException} (dashj 22.0.3) so that
 * modules depending on {@code common} need no dashj on their classpath. Same exception hierarchy,
 * same messages.
 */
public class AddressFormatException extends IllegalArgumentException {
    public AddressFormatException() {
        super();
    }

    public AddressFormatException(String message) {
        super(message);
    }

    /**
     * This exception is thrown by Base58, Bech32 and the address classes when you try to decode data and a character
     * isn't valid. You shouldn't allow the user to proceed in this case.
     */
    public static class InvalidCharacter extends AddressFormatException {
        public final char character;
        public final int position;

        public InvalidCharacter(char character, int position) {
            super("Invalid character '" + Character.toString(character) + "' at position " + position);
            this.character = character;
            this.position = position;
        }
    }

    /**
     * This exception is thrown by Base58, Bech32 and the address classes when you try to decode data and the data
     * isn't of the right size. You shouldn't allow the user to proceed in this case.
     */
    public static class InvalidDataLength extends AddressFormatException {
        public InvalidDataLength() {
            super();
        }

        public InvalidDataLength(String message) {
            super(message);
        }
    }

    /**
     * This exception is thrown by Base58, Bech32 and the address classes when you try to decode data and the checksum
     * isn't valid. You shouldn't allow the user to proceed in this case.
     */
    public static class InvalidChecksum extends AddressFormatException {
        public InvalidChecksum() {
            super("Checksum does not validate");
        }

        public InvalidChecksum(String message) {
            super(message);
        }
    }

    /**
     * This exception is thrown by the address classes when you try to decode data and the data isn't a valid prefix or
     * version. You shouldn't allow the user to proceed in this case.
     */
    public static class InvalidPrefix extends AddressFormatException {
        public InvalidPrefix() {
            super();
        }

        public InvalidPrefix(String message) {
            super(message);
        }
    }

    /**
     * This exception is thrown by the address classes when you try and decode an address or private key with an
     * invalid prefix (version header or human-readable part) for the network you are on. The client of this library
     * should handle this exception in a "safe" way by informing the user, requesting them to check the network, etc.
     */
    public static class WrongNetwork extends InvalidPrefix {
        public WrongNetwork(int versionHeader) {
            super("Version code of address did not match acceptable versions for network: " + versionHeader);
        }

        public WrongNetwork(String hrp) {
            super("Human readable part of address did not match acceptable HRPs for network: " + hrp);
        }
    }
}
