/*
 * Copyright (c) 2026. Dash Core Group.
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

package de.schildbach.wallet.performance

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.base.Stopwatch
import junit.framework.TestCase.assertEquals
import org.bitcoinj.params.TestNet3Params
import org.bitcoinj.wallet.UnreadableWalletException
import org.bitcoinj.wallet.Wallet
import org.bitcoinj.wallet.WalletEx
import org.bitcoinj.wallet.WalletProtobufSerializer
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.slf4j.LoggerFactory
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.time.LocalDateTime
import java.util.concurrent.TimeUnit


@RunWith(AndroidJUnit4::class)
class WalletLoadPerformanceTest {

    companion object {
        private val log = LoggerFactory.getLogger(WalletLoadPerformanceTest::class.java)
        private const val MNEMONIC = "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about"
        private const val EXPECTED_SAVE_TIME_MS = 5000L // Expected maximum save time
    }

    private lateinit var context: Context
    private lateinit var wallet: Wallet
    private lateinit var tempWalletFile: File

    @Before
    fun setup() {
        try {
            org.bitcoinj.core.Context.propagate(org.bitcoinj.core.Context(TestNet3Params.get()))
            context = InstrumentationRegistry.getInstrumentation().context
            context.assets.open("test_wallet.wallet").use { `is` ->
            //javaClass.getResourceAsStream("test_wallet.wallet").use { `is` ->
                val watch = Stopwatch.createStarted()
                wallet = WalletProtobufSerializer().readWallet(`is`) as WalletEx
                info("loading wallet: {}; {} transactions", watch, wallet.getTransactionCount(true))
            }
        } catch (e: IOException) {
            throw RuntimeException(e)
        } catch (e: UnreadableWalletException) {
            throw RuntimeException(e)
        }
    }

    @Test
    @Throws(IOException::class, UnreadableWalletException::class)
    fun walletLoadPerformanceTest() {
        info("=== Wallet Load Performance Test (serial vs parallel) ===")

        // Serialize the wallet once so we reload from identical bytes each time
        val baos = ByteArrayOutputStream()
        WalletProtobufSerializer().writeWallet(wallet, baos)
        val walletBytes = baos.toByteArray()
        info("Wallet serialized: {} bytes", walletBytes.size)

        val warmupRuns = 1
        val timedRuns = 3

        // --- Serial ---
        val serialSerializer = WalletProtobufSerializer()
        serialSerializer.isParallelLoad = false

        for (i in 0..<warmupRuns) {
            serialSerializer.readWallet(ByteArrayInputStream(walletBytes))
            System.gc()
        }

        val serialTimes = LongArray(timedRuns)
        for (i in 0..<timedRuns) {
            System.gc()
            val w: Stopwatch = Stopwatch.createStarted()
            val loaded = serialSerializer.readWallet(ByteArrayInputStream(walletBytes)) as WalletEx
            serialTimes[i] = w.elapsed(TimeUnit.MILLISECONDS)
            assertEquals(wallet.getTransactionCount(true), loaded.getTransactionCount(true))
            assertEquals(
                wallet.getBalance(Wallet.BalanceType.ESTIMATED),
                loaded.getBalance(Wallet.BalanceType.ESTIMATED)
            )
        }

        // --- Parallel ---
        val parallelSerializer = WalletProtobufSerializer()
        parallelSerializer.isParallelLoad = true

        for (i in 0..<warmupRuns) {
            parallelSerializer.readWallet(ByteArrayInputStream(walletBytes))
            System.gc()
        }

        val parallelTimes = LongArray(timedRuns)
        for (i in 0..<timedRuns) {
            System.gc()
            val w: Stopwatch = Stopwatch.createStarted()
            val loaded = parallelSerializer.readWallet(ByteArrayInputStream(walletBytes)) as WalletEx
            parallelTimes[i] = w.elapsed(TimeUnit.MILLISECONDS)
            assertEquals(wallet.getTransactionCount(true), loaded.getTransactionCount(true))
            assertEquals(
                wallet.getBalance(Wallet.BalanceType.ESTIMATED),
                loaded.getBalance(Wallet.BalanceType.ESTIMATED)
            )
        }

        val serialAvg: Long = average(serialTimes)
        val serialMin: Long = min(serialTimes)
        val parallelAvg: Long = average(parallelTimes)
        val parallelMin: Long = min(parallelTimes)

        info("Serial   avg={}ms  min={}ms  runs={}", serialAvg, serialMin, formatTimes(serialTimes))
        info("Parallel avg={}ms  min={}ms  runs={}", parallelAvg, parallelMin, formatTimes(parallelTimes))

        if (parallelAvg < serialAvg) {
            val speedup = serialAvg.toDouble() / parallelAvg
            info("Parallel is {:.2f}x faster (avg)", speedup)
        } else {
            info("Serial is faster on this device — consider setParallelLoad(false)")
        }
    }

    private fun average(times: LongArray): Long {
        var sum: Long = 0
        for (t in times) sum += t
        return sum / times.size
    }

    private fun min(times: LongArray): Long {
        var m = times[0]
        for (t in times) if (t < m) m = t
        return m
    }

    fun info(format: String, vararg args: Any?) {
        log("INFO", format, *args)
    }

    fun error(format: String, vararg args: Any?) {
        log("ERROR", format, *args)
    }

    private fun formatTimes(times: LongArray): String {
        val sb = StringBuilder("[")
        for (i in times.indices) {
            if (i > 0) sb.append(", ")
            sb.append(times[i]).append("ms")
        }
        return sb.append("]").toString()
    }

    private fun log(level: String, format: String, vararg args: Any?) {
        val timestamp = LocalDateTime.now().toString()
        val message = formatMessage(format, *args)
        System.out.printf("%s [%s] %s%n", timestamp, level, message)
    }

    private fun formatMessage(format: String, vararg args: Any?): String {
        var result = format
        var argIndex = 0

        // Handle formatted placeholders like {:.2f}
        while (result.contains("{:.") && argIndex < args.size) {
            val start = result.indexOf("{:.")
            if (start >= 0) {
                val end = result.indexOf("}", start)
                if (end >= 0) {
                    val formatSpec = result.substring(start + 2, end)
                    val arg = args[argIndex++]
                    var replacement: String

                    if (arg is Number && formatSpec.endsWith("f")) {
                        // Handle decimal formatting like .2f, .1f
                        try {
                            val decimals = formatSpec.substring(1, formatSpec.length - 1).toInt()
                            val value = arg.toDouble()
                            replacement = if (decimals == 1) {
                                String.format("%.1f", value)
                            } else if (decimals == 2) {
                                String.format("%.2f", value)
                            } else {
                                String.format("%.2f", value) // default
                            }
                        } catch (e: NumberFormatException) {
                            replacement = arg.toString()
                        }
                    } else {
                        replacement = arg?.toString() ?: "null"
                    }

                    result = result.substring(0, start) + replacement + result.substring(end + 1)
                } else {
                    break
                }
            } else {
                break
            }
        }

        // Handle simple {} placeholders
        for (i in argIndex..<args.size) {
            val pos = result.indexOf("{}")
            if (pos >= 0) {
                val replacement = if (args[i] == null) "null" else args[i].toString()
                result = result.substring(0, pos) + replacement + result.substring(pos + 2)
            }
        }

        return result
    }
}