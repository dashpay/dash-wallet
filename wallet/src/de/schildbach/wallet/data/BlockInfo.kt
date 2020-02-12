package de.schildbach.wallet.data

import java.io.Serializable

/**
 * @author Samuel Barbosa
 */
data class BlockInfo(val height: Int, val time: String, val hash: String) : Serializable
