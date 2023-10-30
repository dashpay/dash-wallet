package de.schildbach.wallet.service

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.launch
import java.util.concurrent.Executors

fun BlockchainServiceImpl.initCoinJoin() {
    val scope = CoroutineScope(Executors.newSingleThreadExecutor().asCoroutineDispatcher())
    scope.launch {
        coinJoinService.configureMixing(true)
    }
}