package de.schildbach.wallet.ui

/**
 * @author Samuel Barbosa
 */
interface VerifySeedActions {
    fun startSeedVerification()
    fun skipSeedVerification()
    fun showRecoveryPhrase()
    fun onVerifyWriteDown()
    fun onSeedVerified()
}