package de.schildbach.wallet.service

import android.content.res.Resources
import android.telephony.TelephonyManager
import android.util.DisplayMetrics
import org.slf4j.LoggerFactory

class DeviceInfoProvider(
    private val resources: Resources,
    private val telephonyManager: TelephonyManager
) {
    companion object {
        private val log = LoggerFactory.getLogger(DeviceInfoProvider::class.java)
    }

    val isSmallScreen: Boolean
        get() = resources.displayMetrics.densityDpi <= DisplayMetrics.DENSITY_MEDIUM

    /**
     * Get ISO 3166-1 alpha-2 country code for this device (or null if not available)
     * If available, call [.showFiatCurrencyChangeDetectedDialog]
     * passing the country code.
     */
    fun getSimOrNetworkCountry(): String {
        try {
            val simCountry = telephonyManager.simCountryIso
            log.info("Detecting currency based on device, mobile network or locale:")

            if (simCountry != null && simCountry.length == 2) { // SIM country code is available
                log.info("Device Sim Country: $simCountry")
                return simCountry
            } else if (telephonyManager.phoneType != TelephonyManager.PHONE_TYPE_CDMA) {
                // device is not 3G (would be unreliable)
                val networkCountry = telephonyManager.networkCountryIso
                log.info("Network Country: $simCountry")

                if (networkCountry != null && networkCountry.length == 2) { // network country code is available
                    return networkCountry
                }
            }
        } catch (e: Exception) {
            // fail safe
            log.info("NMA-243:  Exception thrown obtaining Locale information: ", e)
        }

        return ""
    }
}