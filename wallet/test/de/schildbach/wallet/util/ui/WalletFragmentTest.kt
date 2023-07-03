/*
 * Copyright 2022 Dash Core Group.
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

package de.schildbach.wallet.util.ui

import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import de.schildbach.wallet.Constants
import de.schildbach.wallet.data.PaymentIntent
import de.schildbach.wallet.ui.main.WalletFragment
import de.schildbach.wallet.ui.payments.SweepWalletActivity
import de.schildbach.wallet.ui.send.SendCoinsActivity
import io.mockk.*
import junit.framework.TestCase.assertEquals
import junit.framework.TestCase.assertTrue
import org.bitcoinj.core.Coin
import org.bitcoinj.core.Transaction
import org.bitcoinj.params.MainNetParams
import org.dash.wallet.common.ui.dialogs.AdaptiveDialog
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestRule
import org.dash.wallet.common.WalletDataProvider
import org.junit.Ignore

@Ignore
// TODO: testing fragments is harder than activities because the fragment has to be attached.
// It's almost possible with Robolectric and HiltTestApplication, but various
// .getInstance() calls on WalletApplication and PlatformRepo are preventing it from getting there.
// We should keep replacing those with injections and restore this test at some point.
class WalletFragmentTest {
    @Rule
    @JvmField
    val rule: TestRule = InstantTaskExecutorRule()

    @Test
    fun handleEmptyPaste_showsDialog() {
        val dialogMessage = "DIALOG_MESSAGE"

        mockkObject(AdaptiveDialog.Companion)
        val mockedDialog = spyk(AdaptiveDialog(0))
        val walletFragment = spyk(WalletFragment())

        every { AdaptiveDialog.Companion.custom(any()) } returns mockedDialog
        every { mockedDialog.show(walletFragment.requireActivity(), any()) } returns Unit
        every { walletFragment.getString(any()) } returns dialogMessage

        walletFragment.handlePaste("")

        verify(exactly = 1) { AdaptiveDialog.Companion.custom(any()) }
        verify(exactly = 1) { mockedDialog.show(walletFragment.requireActivity(), any()) }
    }

    @Test
    fun handleInvalidPaste_showsDialog() {
        val errorDialogTitle = "DIALOG_TITLE"
        val input = "invalid input"

        mockkObject(AdaptiveDialog.Companion)

        val mockedDialog = spyk(AdaptiveDialog(0))
        val walletFragment = spyk(WalletFragment())

        every { AdaptiveDialog.Companion.custom(any()) } returns mockedDialog
        every { walletFragment.getString(any()) } returns errorDialogTitle
        every { walletFragment.getString(any(), any()) } returns errorDialogTitle
        every { mockedDialog.show(walletFragment.requireActivity(), any()) } returns Unit

        walletFragment.handlePaste(input)

        verify(exactly = 1) { AdaptiveDialog.Companion.custom(any()) }
        verify(exactly = 1) { mockedDialog.show(walletFragment.requireActivity(), any()) }
    }

    @Test
    fun handlePaste_dashAddress_startsSendCoins() {
        val dashAddress = if (Constants.NETWORK_PARAMETERS == MainNetParams.get()) {
            "XjBya4EnibUyxubEA8D2Y8KSrBMW1oHq5U"
        } else {
            "yeEpP1q7ow319JYbnQCq7ucwPcjkeSyuoz"
        }

        val walletFragment = spyk(WalletFragment())
        mockkStatic(SendCoinsActivity::class)
        val slot = slot<PaymentIntent>()

        every { SendCoinsActivity.start(any(), capture(slot)) } answers {
            SendCoinsActivity.start(mockk(), "", slot.captured, true)
        }
        every { SendCoinsActivity.start(any(), capture(slot)) } answers {
            SendCoinsActivity.start(mockk(), "", slot.captured, true)
        }
        every { SendCoinsActivity.start(any(), any(), capture(slot), any()) } returns Unit

        walletFragment.handlePaste(dashAddress)
        verify(exactly = 1) { SendCoinsActivity.start(any(), any(), any(), any()) }
        assertEquals(dashAddress, slot.captured.address?.toBase58())
    }

    @Test
    fun handlePaste_paymentRequest_startsSendCoins() {
        val amount = 1.72
        val dashAddress = if (Constants.NETWORK_PARAMETERS == MainNetParams.get()) {
            "XjBya4EnibUyxubEA8D2Y8KSrBMW1oHq5U"
        } else {
            "yeEpP1q7ow319JYbnQCq7ucwPcjkeSyuoz"
        }

        val walletFragment = spyk(WalletFragment())
        mockkStatic(SendCoinsActivity::class)
        val slot = slot<PaymentIntent>()

        every { SendCoinsActivity.start(any(), capture(slot)) } answers {
            SendCoinsActivity.start(mockk(), "", slot.captured, true)
        }
        every { SendCoinsActivity.start(any(), capture(slot)) } answers {
            SendCoinsActivity.start(mockk(), "", slot.captured, true)
        }
        every { SendCoinsActivity.start(any(), any(), capture(slot), any()) } returns Unit

        var request = "dash:${dashAddress}?amount=${amount}&cy=USD&local=96.20"

        walletFragment.handlePaste(request)
        verify(exactly = 1) { SendCoinsActivity.start(any(), any(), any(), any()) }
        assertEquals(dashAddress, slot.captured.address?.toBase58())
        assertEquals(Coin.parseCoin(amount.toString()), slot.captured.amount)

        request = "pay:${dashAddress}?amount=${amount}"

        walletFragment.handlePaste(request)
        verify(exactly = 2) { SendCoinsActivity.start(any(), any(), any(), any()) }
        assertEquals(dashAddress, slot.captured.address?.toBase58())
        assertEquals(Coin.parseCoin(amount.toString()), slot.captured.amount)
    }

    @Test
    fun handlePaste_wrongNetworkAddress_showsDialog() {
        val errorDialogTitle = "DIALOG_TITLE"

        val dashAddress = if (Constants.NETWORK_PARAMETERS == MainNetParams.get()) {
            "yeEpP1q7ow319JYbnQCq7ucwPcjkeSyuoz"
        } else {
            "XjBya4EnibUyxubEA8D2Y8KSrBMW1oHq5U"
        }

        mockkObject(AdaptiveDialog.Companion)
        val mockedDialog = spyk(AdaptiveDialog(0))
        val walletFragment = spyk(WalletFragment())

        every { AdaptiveDialog.Companion.custom(any()) } returns mockedDialog
        every { walletFragment.getString(any()) } returns errorDialogTitle
        every { walletFragment.getString(any(), any()) } returns errorDialogTitle
        every { mockedDialog.show(walletFragment.requireActivity(), any()) } returns Unit

        walletFragment.handlePaste(dashAddress)

        verify(exactly = 1) { AdaptiveDialog.Companion.custom(any()) }
        verify(exactly = 1) { mockedDialog.show(walletFragment.requireActivity(), any()) }
    }

    @Test
    fun handlePaste_compressedTransaction_callsProcessDirectTransaction() {
        val encoded = if (Constants.NETWORK_PARAMETERS == MainNetParams.get()) {
            "-1OC44IYYZ7351TO16.0L+8VUKI+5N8/LD*.A4M+RUQ2TDTCKNAS.MR2GA*9QAZT8DFKDPG5KQQ9NHIITIG*EM3\$E85WCXO5*CNIC3ZTFB//ML9W$$63BOGM9P.Z6SDU7RHN1Z7N+$1+.X7VNQB30W/1XUZ9UP32VC..TXKHP1+NHHXZ0/DNWDA*LG2BC0M.2/*3ZAX29EP4QKNAW-8939N6YI:6F3FQ*RR8/36TOKC\$VQD/S/0TT/LF4:F.CU4MUB9XTWAN-ADTF90:W+D.O3$*V9"
        } else {
            "-T:M\$70XNHTMZKKDWE9:2:4K:F\$PW751H-5:5ZXI7868QOE0B:V8\$-:T\$OT\$I-WQ-AP:NGLFGKOLIAO.EMC7Q\$217X4F2PAL4IIV\$GSJ/TERGOSO-3R1X9/*C/OKVJYHMFB5QP0GJ6ZFAEMCRFP*XV0FGCDT5H8/XP0MLXEC7\$CIRQO70NASCZ+5T.MSZRYC*02B1T4R\$:SNVP.03T4URJ3505GX.NW6GX4KO8SIW*1.EFS5:Z3HK6B3/TK-1P70LY769XXL.CM:NY43/S3MO-*FNR\$5NZ1I+C6MWVUJLQ5ZUOA+1TZO5XB116LI8:6MXW436XG.SAD50"
        }

        val txId = if (Constants.NETWORK_PARAMETERS == MainNetParams.get()) {
            "c715cd196b1a4b48f924b9fe20efe4239ca0dbc48cd61994ba302e0fc7cdb35c"
        } else {
            "749baf6851945010912f8b828a11dc3d029437b54985ef044717849f0d177067"
        }

        val walletFragment = spyk(WalletFragment())
        val walletDataProvider = mockk<WalletDataProvider>()
        val slot = slot<Transaction>()

//        walletFragment.walletDataProvider = walletDataProvider // TODO check viewModel instead

        every { walletDataProvider.processDirectTransaction(capture(slot)) } returns Unit
        walletFragment.handlePaste(encoded)

        verify(exactly = 1) { walletDataProvider.processDirectTransaction(any()) }
        assertEquals(txId, slot.captured.txId?.toString())
    }

    @Test
    fun handlePaste_privateKey_startsSweepWallet() {
        val privateKey = if (Constants.NETWORK_PARAMETERS == MainNetParams.get()) {
            "7qqCFxmiHnfjvDDUHGYR8NyHFqYTuUw5fGxSsaRxaQB8ytzhd3G"
        } else {
            "cPze4HiFvUm2qFzDuERcRE6PhRd7p32UvAPrk9nrmtHycjVe3RYi"
        }

        val walletFragment = spyk(WalletFragment())
        mockkStatic(SweepWalletActivity::class)

        every { SweepWalletActivity.start(any(), any()) } answers {
            SweepWalletActivity.start(mockk(), mockk(), true)
        }
        every { SweepWalletActivity.start(any(), any(), any()) } returns Unit

        walletFragment.handlePaste(privateKey)
        verify(exactly = 1) { SweepWalletActivity.start(any(), any(), any()) }
    }

    @Test
    fun handlePaste_inputWithDashAddress_confirmsAndStartsSendCoins() {
        val confirmDialogTitle = "CONFIRM_DIALOG_TITLE"
        val dashAddress = if (Constants.NETWORK_PARAMETERS == MainNetParams.get()) {
            "XjBya4EnibUyxubEA8D2Y8KSrBMW1oHq5U"
        } else {
            "yeEpP1q7ow319JYbnQCq7ucwPcjkeSyuoz"
        }

        mockkObject(AdaptiveDialog.Companion)
        mockkStatic(SendCoinsActivity::class)
        val mockedDialog = spyk(AdaptiveDialog(0))
        val walletFragment = spyk(WalletFragment())

        val dialogCallbackSlot = slot<(Boolean?) -> Unit>()
        val paymentIntentSlot = slot<PaymentIntent>()

        every { AdaptiveDialog.Companion.custom(any()) } returns mockedDialog
        every { walletFragment.getString(any()) } returns confirmDialogTitle
        every { walletFragment.getString(any(), any()) } returns confirmDialogTitle
        every { mockedDialog.show(walletFragment.requireActivity(), capture(dialogCallbackSlot)) } answers {
            dialogCallbackSlot.captured.invoke(true)
        }

        every { SendCoinsActivity.start(any(), capture(paymentIntentSlot)) } answers {
            SendCoinsActivity.start(mockk(), "", paymentIntentSlot.captured, true)
        }
        every { SendCoinsActivity.start(any(), capture(paymentIntentSlot)) } answers {
            SendCoinsActivity.start(mockk(), "", paymentIntentSlot.captured, true)
        }
        every { SendCoinsActivity.start(any(), any(), capture(paymentIntentSlot), any()) } returns Unit

        var input = "some text $dashAddress some text"
        walletFragment.handlePaste(input)

        assertTrue(paymentIntentSlot.captured.shouldConfirmAddress)
        verify(exactly = 1) { AdaptiveDialog.Companion.custom(any()) }
        assertEquals(dashAddress, paymentIntentSlot.captured.address?.toBase58())
        verify(exactly = 1) { SendCoinsActivity.start(any(), any(), any(), any()) }

        input = "dash:${dashAddress}?amount=234¤cy=USD&local=96.20" // Payment request with an error
        walletFragment.handlePaste(input)

        assertTrue(paymentIntentSlot.captured.shouldConfirmAddress)
        verify(exactly = 2) { AdaptiveDialog.Companion.custom(any()) }
        assertEquals(dashAddress, paymentIntentSlot.captured.address?.toBase58())
        verify(exactly = 2) { SendCoinsActivity.start(any(), any(), any(), any()) }
    }
}
