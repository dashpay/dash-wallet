/*
 * Copyright 2018 Dash Core Group
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

package de.schildbach.wallet.util;

import android.Manifest;
import android.annotation.SuppressLint;
import android.annotation.TargetApi;
import android.app.KeyguardManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.hardware.fingerprint.FingerprintManager;
import android.os.Build;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyPermanentlyInvalidatedException;
import android.security.keystore.KeyProperties;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import androidx.core.app.ActivityCompat;
import androidx.core.hardware.fingerprint.FingerprintManagerCompat;
import androidx.core.os.CancellationSignal;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.Charset;
import java.security.InvalidAlgorithmParameterException;
import java.security.InvalidKeyException;
import java.security.Key;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.UnrecoverableKeyException;
import java.util.ArrayList;

import javax.crypto.Cipher;
import javax.crypto.CipherInputStream;
import javax.crypto.CipherOutputStream;
import javax.crypto.KeyGenerator;
import javax.crypto.NoSuchPaddingException;
import javax.crypto.spec.IvParameterSpec;

import de.schildbach.wallet.WalletApplication;

import static android.content.Context.KEYGUARD_SERVICE;

public class FingerprintHelper {

    private static final Logger log = LoggerFactory.getLogger(FingerprintHelper.class);

    private static final String FINGERPRINT_PREFS_NAME = "FINGERPRINT_HELPER_PREFS";
    private static final String ENCRYPTED_PASS_SHARED_PREF_KEY = "ENCRYPTED_PASS_PREFS_KEY";
    private static final String LAST_USED_IV_SHARED_PREF_KEY = "LAST_USED_IV_PREFS_KEY";
    private static final String KEYSTORE_ALIAS = "DASH_WALLET_FINGERPRINT_KEYSTORE";
    private static final String FINGERPRINT_KEY_CHANGED = "FINGERPRINT_KEY_CHANGED";

    private FingerprintManagerCompat fingerprintManager;

    private final Context context;
    private KeyStore keyStore;

    public interface Callback {
        void onSuccess(String savedPass);

        void onFailure(String message, boolean canceled, boolean exceededMaxAttempts);

        void onHelp(int helpCode, String helpString);
    }

    public FingerprintHelper(Context context) {
        this.context = context;
    }

    @TargetApi(Build.VERSION_CODES.M)
    public boolean init() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            log.info("This Android version does not support fingerprint authentication");
            return false;
        }

        KeyguardManager keyguardManager = (KeyguardManager) context.getSystemService(KEYGUARD_SERVICE);
        fingerprintManager = FingerprintManagerCompat.from(context);

        if (!fingerprintManager.isHardwareDetected()) {
            log.info("Fingerprint hardware not detected");
            return  false;
        }

        if (!keyguardManager.isKeyguardSecure()) {
            log.info("User hasn't enabled Lock Screen");
            return false;
        }

        if (!hasPermission()) {
            log.info("User hasn't granted permission to use Fingerprint");
            return false;
        }

        if (!fingerprintManager.hasEnrolledFingerprints()) {
            log.info("User hasn't registered any fingerprints");
            return false;
        }

        if (!initKeyStore()) {
            return false;
        }
        return true;
    }

    @Nullable
    @RequiresApi(api = Build.VERSION_CODES.M)
    private Cipher createCipher(int mode) throws NoSuchPaddingException, NoSuchAlgorithmException,
            UnrecoverableKeyException, KeyStoreException, InvalidKeyException, InvalidAlgorithmParameterException {
        Cipher cipher = Cipher.getInstance(KeyProperties.KEY_ALGORITHM_AES + "/" +
                KeyProperties.BLOCK_MODE_CBC + "/" +
                KeyProperties.ENCRYPTION_PADDING_PKCS7);

        Key key = keyStore.getKey(KEYSTORE_ALIAS, null);
        if (key == null) {
            return null;
        }
        if(mode == Cipher.ENCRYPT_MODE) {
            cipher.init(mode, key);
            byte[] iv = cipher.getIV();
            saveIv(iv);
        } else {
            byte[] lastIv = getLastIv();
            cipher.init(mode, key, new IvParameterSpec(lastIv));
        }
        return cipher;
    }

    @NonNull
    @RequiresApi(api = Build.VERSION_CODES.M)
    private KeyGenParameterSpec createKeyGenParameterSpec() {
        return new KeyGenParameterSpec.Builder(KEYSTORE_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_CBC)
                .setUserAuthenticationRequired(true)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_PKCS7)
                .build();
    }

    @RequiresApi(api = Build.VERSION_CODES.M)
    private boolean initKeyStore() {
        try {
            keyStore = KeyStore.getInstance("AndroidKeyStore");
            KeyGenerator keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
            keyStore.load(null);
            if (getLastIv() == null) {
                KeyGenParameterSpec keyGeneratorSpec = createKeyGenParameterSpec();
                keyGenerator.init(keyGeneratorSpec);
                keyGenerator.generateKey();
            }
        } catch (Throwable t) {
            log.info("Failed init of keyStore & keyGenerator: " + t.getMessage());
            return false;
        }
        return true;
    }

    @RequiresApi(api = Build.VERSION_CODES.M)
    private void authenticate(CancellationSignal cancellationSignal, FingerprintAuthenticationListener authListener, int mode) {
        try {
            if (hasPermission()) {
                Cipher cipher = createCipher(mode);
                FingerprintManagerCompat.CryptoObject crypto = new FingerprintManagerCompat.CryptoObject(cipher);
                fingerprintManager.authenticate(crypto, 0, cancellationSignal, authListener, null);
            } else {
                log.warn("User hasn't granted permission to use Fingerprint");
                authListener.getCallback()
                        .onFailure("User hasn't granted permission to use Fingerprint",
                                false, false);
            }
        } catch (Throwable t) {
            if (t instanceof KeyPermanentlyInvalidatedException) {
                //reset fingerprint
                fingerprintKeyChanged();
            }
            log.warn("An error occurred", t);
            authListener.getCallback().onFailure("An error occurred: " + t.getMessage(),
                    false, false);
        }
    }

    /**
     * This method is called when the fingerprint key changes on the system level, mainly because
     * a new finger was added, as result, we clear previous authentication data and flag the change
     * so it can be reflected by the UI.
     */
    @SuppressLint("ApplySharedPref")
    private void fingerprintKeyChanged() {
        clear();
        getSharedPreferences().edit().putBoolean(FINGERPRINT_KEY_CHANGED, true).commit();
    }

    public boolean hasFingerprintKeyChanged() {
        return getSharedPreferences().getBoolean(FINGERPRINT_KEY_CHANGED, false);
    }

    public void resetFingerprintKeyChanged() {
        getSharedPreferences().edit().remove(FINGERPRINT_KEY_CHANGED).apply();
    }

    private String getSavedEncryptedPassword() {
        SharedPreferences sharedPreferences = getSharedPreferences();
        if (sharedPreferences != null) {
            return sharedPreferences.getString(ENCRYPTED_PASS_SHARED_PREF_KEY, null);
        }
        return null;
    }

    public boolean isFingerprintEnabled() {
        return getSavedEncryptedPassword() != null;
    }

    public void clear() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                && init() && isFingerprintEnabled()) {
            WalletApplication.getInstance().getConfiguration().setRemindEnableFingerprint(true);
        }
        getSharedPreferences().edit().clear().commit();
    }

    private void saveEncryptedPassword(String encryptedPassword) {
        SharedPreferences.Editor edit = getSharedPreferences().edit();
        edit.putString(ENCRYPTED_PASS_SHARED_PREF_KEY, encryptedPassword);
        edit.commit();
    }

    private byte[] getLastIv() {
        SharedPreferences sharedPreferences = getSharedPreferences();
        if (sharedPreferences != null) {
            String ivString = sharedPreferences.getString(LAST_USED_IV_SHARED_PREF_KEY, null);

            if (ivString != null) {
                return decodeBytes(ivString);
            }
        }
        return null;
    }

    private void saveIv(byte[] iv) {
        SharedPreferences.Editor edit = getSharedPreferences().edit();
        String string = encodeBytes(iv);
        edit.putString(LAST_USED_IV_SHARED_PREF_KEY, string);
        edit.commit();
    }

    private SharedPreferences getSharedPreferences() {
        return context.getSharedPreferences(FINGERPRINT_PREFS_NAME, 0);
    }

    @RequiresApi(api = Build.VERSION_CODES.M)
    private boolean hasPermission() {
        return ActivityCompat.checkSelfPermission(context,
                Manifest.permission.USE_FINGERPRINT) == PackageManager.PERMISSION_GRANTED;
    }

    @RequiresApi(api = Build.VERSION_CODES.M)
    public void savePassword(@NonNull String password, CancellationSignal cancellationSignal, Callback callback) {
        authenticate(cancellationSignal, new FingerprintEncryptPasswordListener(callback, password), Cipher.ENCRYPT_MODE);
    }

    @RequiresApi(api = Build.VERSION_CODES.M)
    public void getPassword(CancellationSignal cancellationSignal, Callback callback) {
        authenticate(cancellationSignal, new FingerprintDecryptPasswordListener(callback), Cipher.DECRYPT_MODE);
    }

    @RequiresApi(api = Build.VERSION_CODES.M)
    public boolean encryptPassword(Cipher cipher, String password) {
        try {
            // Encrypt the text
            if(password.isEmpty()) {
                log.info("Password is empty");
                return false;
            }

            if (cipher == null) {
                log.info("Could not create cipher");
                return false;
            }

            ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
            CipherOutputStream cipherOutputStream = new CipherOutputStream(outputStream, cipher);
            byte[] bytes = password.getBytes(Charset.defaultCharset());
            cipherOutputStream.write(bytes);
            cipherOutputStream.flush();
            cipherOutputStream.close();
            saveEncryptedPassword(encodeBytes(outputStream.toByteArray()));
        } catch (Throwable t) {
            log.info("Encryption failed " + t.getMessage());
            return false;
        }

        return true;
    }

    private byte[] decodeBytes(String s) {
        final int len = s.length();

        // "111" is not a valid hex encoding.
        if( len%2 != 0 )
            throw new IllegalArgumentException("hexBinary needs to be even-length: "+s);

        byte[] out = new byte[len/2];

        for( int i=0; i<len; i+=2 ) {
            int h = hexToBin(s.charAt(i  ));
            int l = hexToBin(s.charAt(i+1));
            if( h==-1 || l==-1 )
                throw new IllegalArgumentException("contains illegal character for hexBinary: "+s);

            out[i/2] = (byte)(h*16+l);
        }

        return out;
    }

    private static int hexToBin(char ch) {
        if( '0'<=ch && ch<='9' )    return ch-'0';
        if( 'A'<=ch && ch<='F' )    return ch-'A'+10;
        if( 'a'<=ch && ch<='f' )    return ch-'a'+10;
        return -1;
    }

    private static final char[] hexCode = "0123456789ABCDEF".toCharArray();

    private String encodeBytes(byte[] data) {
        StringBuilder r = new StringBuilder(data.length*2);
        for ( byte b : data) {
            r.append(hexCode[(b >> 4) & 0xF]);
            r.append(hexCode[(b & 0xF)]);
        }
        return r.toString();
    }

    private String decipher(Cipher cipher) throws IOException {
        String retVal = null;
        String savedEncryptedPassword = getSavedEncryptedPassword();
        if (savedEncryptedPassword != null) {
            byte[] decodedPassword = decodeBytes(savedEncryptedPassword);
            CipherInputStream cipherInputStream = new CipherInputStream(new ByteArrayInputStream(decodedPassword), cipher);

            ArrayList<Byte> values = new ArrayList<>();
            int nextByte;
            while ((nextByte = cipherInputStream.read()) != -1) {
                values.add((byte) nextByte);
            }
            cipherInputStream.close();

            byte[] bytes = new byte[values.size()];
            for (int i = 0; i < values.size(); i++) {
                bytes[i] = values.get(i).byteValue();
            }

            retVal = new String(bytes, Charset.defaultCharset());
        }
        return retVal;
    }

    @RequiresApi(Build.VERSION_CODES.M)
    protected class FingerprintAuthenticationListener extends FingerprintManagerCompat.AuthenticationCallback {

        protected final Callback callback;

        public FingerprintAuthenticationListener(@NonNull Callback callback) {
            this.callback = callback;
        }

        public void onAuthenticationError(int errorCode, CharSequence errString) {
            boolean canceled = FingerprintManager.FINGERPRINT_ERROR_CANCELED == errorCode;
            boolean exceededMaxAttempts = FingerprintManager.FINGERPRINT_ERROR_LOCKOUT == errorCode;
            callback.onFailure("Authentication error [" + errorCode + "] " + errString,
                    canceled, exceededMaxAttempts);
        }

        /**
         * Called when a recoverable error has been encountered during authentication. The help
         * string is provided to give the user guidance for what went wrong, such as
         * "Sensor dirty, please clean it."
         * @param helpCode An integer identifying the error message
         * @param helpString A human-readable string that can be shown in UI
         */
        public void onAuthenticationHelp(int helpCode, CharSequence helpString) {
            callback.onHelp(helpCode, helpString.toString());
        }

        /**
         * Called when a fingerprint is recognized.
         * @param result An object containing authentication-related data
         */
        public void onAuthenticationSucceeded(FingerprintManagerCompat.AuthenticationResult result) {
        }

        /**
         * Called when a fingerprint is valid but not recognized.
         */
        public void onAuthenticationFailed() {
            callback.onFailure("Authentication failed", false, false);
        }

        public @NonNull
        Callback getCallback() {
            return callback;
        }

    }

    @RequiresApi(api = Build.VERSION_CODES.M)
    private class FingerprintEncryptPasswordListener extends FingerprintAuthenticationListener {

        private final String password;

        public FingerprintEncryptPasswordListener(Callback callback, String password) {
            super(callback);
            this.password = password;
        }

        public void onAuthenticationSucceeded(FingerprintManagerCompat.AuthenticationResult result) {
            try {
                Cipher cipher = result.getCryptoObject().getCipher();
                if (encryptPassword(cipher, password)) {
                    log.info("password encrypted successfully");
                    callback.onSuccess("Encrypted");
                } else {
                    log.info("failed to encrypt password");
                    callback.onFailure("Encryption failed", false, false);
                }
            } catch (Exception e) {
                String message = "Encryption failed " + e.getMessage();
                log.info(message);
                callback.onFailure(message, false, false);
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.M)
    protected class FingerprintDecryptPasswordListener extends FingerprintAuthenticationListener {

        public FingerprintDecryptPasswordListener(@NonNull Callback callback) {
            super(callback);
        }

        public void onAuthenticationSucceeded(FingerprintManagerCompat.AuthenticationResult result) {

            try {
                Cipher cipher = result.getCryptoObject().getCipher();
                String savedPass = decipher(cipher);
                if (savedPass != null) {
                    log.info("password decrypted successfully");
                    callback.onSuccess(savedPass);
                } else {
                    log.info("failed to decrypt password");
                    callback.onFailure("Failed deciphering", false, false);
                }
            } catch (Exception e) {
                String message = "Deciphering failed " + e.getMessage();
                log.info(message);
                callback.onFailure(message, false, false);
            }
        }
    }
}