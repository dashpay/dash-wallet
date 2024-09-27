package de.schildbach.wallet.ui.dashpay

import org.bitcoinj.wallet.DeterministicSeed
import org.bouncycastle.crypto.params.KeyParameter

data class CreateUsernameInfo(val username: String, val seed: DeterministicSeed, val keyParameter: KeyParameter?)