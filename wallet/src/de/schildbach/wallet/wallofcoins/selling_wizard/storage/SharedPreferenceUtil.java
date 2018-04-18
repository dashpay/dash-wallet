package de.schildbach.wallet.wallofcoins.selling_wizard.storage;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.SharedPreferences;
import android.preference.PreferenceManager;

import java.util.Map;

/**
 * Creates SharedPreference for the application. and provides access to it
 */
public class SharedPreferenceUtil {

    private static SharedPreferences sharedPreferences = null;

    private static SharedPreferences.Editor editor = null;

    /**
     * Initialize the SharedPreferences instance for the app.
     * This method must be called before using any
     * other methods of this class.
     *
     * @param context {@link Context}
     */
    @SuppressLint("CommitPrefEdits")
    public static void init(Context mcontext) {

        if (sharedPreferences == null) {
            sharedPreferences = PreferenceManager
                    .getDefaultSharedPreferences(mcontext);
            editor = sharedPreferences.edit();
        }

    }

    /**
     * Puts new Key and its Values into SharedPreference map.
     *
     * @param key
     * @param value
     */
    public static void putValue(String key, String value) {
        editor.putString(key, value);
        editor.commit();

    }

    /**
     * Puts new Key and its Values into SharedPreference map.
     *
     * @param key
     * @param value
     */
    public static void putValue(String key, int value) {
        editor.putInt(key, value);
        editor.commit();
    }

    /**
     * Puts new Key and its Values into SharedPreference map.
     *
     * @param key
     * @param value
     */
    public static void putValue(String key, long value) {
        editor.putLong(key, value);
        editor.commit();
    }

    /**
     * Puts new Key and its Values into SharedPreference map.
     *
     * @param key
     * @param value
     */
    public static void putValue(String key, boolean value) {
        editor.putBoolean(key, value);
        editor.commit();
    }

    public static void remove(String key) {
        editor.remove(key);
        editor.commit();
    }

    /**
     * returns a values associated with a Key default value ""
     *
     * @return String
     */
    public static String getString(String key, String defValue) {
        return sharedPreferences.getString(key, defValue);
    }

    /**
     * returns a values associated with a Key default value -1
     *
     * @return String
     */
    public static int getInt(String key, int defValue) {
        return sharedPreferences.getInt(key, defValue);
    }

    /**
     * returns a values associated with a Key default value -1
     *
     * @return String
     */
    public static long getLong(String key, long defValue) {
        return sharedPreferences.getLong(key, defValue);
    }

    /**
     * returns a values associated with a Key default value false
     *
     * @return String
     */
    public static boolean getBoolean(String key, boolean defValue) {
        return sharedPreferences.getBoolean(key, defValue);
    }

    /**
     * Checks if key is exist in SharedPreference
     *
     * @param key
     * @return boolean
     */
    public static boolean contains(String key) {
        return sharedPreferences.contains(key);
    }

    /**
     * returns map of all the key value pair available in SharedPreference
     *
     * @return Map<String, ?>
     */
    public static Map<String, ?> getAll() {
        return sharedPreferences.getAll();
    }

    /**
     * clear all available sharedPreference
     */
    public static void clearAll() {
        editor.clear().commit();
    }

}
