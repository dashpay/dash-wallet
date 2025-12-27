/*
 * Copyright 2025 Dash Core Group.
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

package de.schildbach.wallet.service

import org.bitcoinj.core.NetworkParameters
import org.bitcoinj.core.Sha256Hash
import org.bitcoinj.core.StoredBlock
import org.bitcoinj.store.BlockStoreException
import org.bitcoinj.store.SPVBlockStore
import org.slf4j.LoggerFactory
import java.io.File

/**
 * A testable subclass of SPVBlockStore that can block the get(Sha256Hash) method
 * to trigger timeout errors for testing purposes.
 *
 * This is useful for testing timeout handling and error recovery mechanisms.
 */
class TestingSPVBlockStore(params: NetworkParameters, file: File?) : SPVBlockStore(params, file) {
    companion object {
        private val log = LoggerFactory.getLogger(TestingSPVBlockStore::class.java)
    }

    @Volatile
    private var blockGetMethod: Boolean = false

    /**
     * Sets whether the get(Sha256Hash) method should block indefinitely.
     *
     * @param block true to block the get method, false to allow normal operation
     */
    fun setBlockGetMethod(block: Boolean) {
        log.info("Setting blockGetMethod to: {}", block)
        blockGetMethod = block
    }

    /**
     * Gets whether the get(Sha256Hash) method is currently blocking.
     */
    fun isBlockingGetMethod(): Boolean {
        return blockGetMethod
    }

    @Throws(BlockStoreException::class)
    override fun get(hash: Sha256Hash): StoredBlock? {
        if (blockGetMethod) {
            log.warn("get(Sha256Hash) is blocked - entering infinite sleep to trigger timeout")
            try {
                // Block indefinitely to trigger timeout
                Thread.sleep(Long.MAX_VALUE)
            } catch (e: InterruptedException) {
                log.info("Blocking get method was interrupted")
                Thread.currentThread().interrupt()
                throw BlockStoreException("get(Sha256Hash) was interrupted during blocking test")
            }
        }
        return super.get(hash)
    }
}