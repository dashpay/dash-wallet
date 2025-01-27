package de.schildbach.wallet.service

import de.schildbach.wallet.Constants
import org.bitcoinj.manager.DashSystem
import javax.inject.Inject


interface DashSystemService {
    val system: DashSystem
}

class DashSystemServiceImpl @Inject constructor() : DashSystemService {
    override val system = DashSystem(Constants.CONTEXT)
}