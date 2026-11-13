/*
 * Copyright 2026 Dash Core Group.
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

package de.schildbach.wallet.ui.transactions

import android.app.Application
import android.widget.TextView
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.fragment.app.FragmentActivity
import de.schildbach.wallet.database.entity.DashPayProfile
import de.schildbach.wallet.service.platform.sdk.L1TxUiDirection
import de.schildbach.wallet.service.platform.sdk.L1TxUiStatus
import de.schildbach.wallet.service.platform.sdk.SdkTxDetail
import de.schildbach.wallet_test.databinding.TransactionResultContentBinding
import io.mockk.mockk
import org.bitcoinj.core.Address
import org.bitcoinj.core.Coin
import org.bitcoinj.core.Context
import org.bitcoinj.core.Transaction
import org.bitcoinj.params.TestNet3Params
import org.bitcoinj.script.ScriptBuilder
import org.bitcoinj.wallet.Wallet
import org.dash.wallet.common.money.MonetaryFormat
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * DW-1659 review round (06b-prereview.md finding 4): [TransactionResultViewBinder]
 * is bound a second time on the SAME instance once a withheld OUTGOING
 * recipient's TXO mirror resolves ([TransactionDetailsDialogFragment] /
 * [TransactionResultActivity] re-observe [SdkTxDetail] and call [bindSdkDetail]
 * again). None of [setInputs]/[setOutputs]/[setStringInputs]/[setStringOutputs]
 * cleared their row container first, so every refresh appended a second,
 * duplicate set of address rows under the first.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [29])
class TransactionResultViewBinderTest {

    private val txId = "ab".repeat(32)

    private fun withheldDetail() = SdkTxDetail(
        txIdDisplayHex = txId,
        netAmountDuffs = -70_247L,
        feeDuffs = 247L,
        timestampMs = 1_770_000_000_000L,
        status = L1TxUiStatus.IN_BLOCK,
        direction = L1TxUiDirection.OUTGOING,
        inputAddresses = listOf("ySenderAddressForDW1659TestXXXXX"),
        outputAddresses = emptyList(),
        hasOpReturn = false,
        recipientsPendingMirror = true
    )

    private fun resolvedDetail() = withheldDetail().copy(
        outputAddresses = listOf("yRecipientAddressForDW1659TestXX"),
        recipientsPendingMirror = false
    )

    private fun newBinder(): Pair<TransactionResultViewBinder, TransactionResultContentBinding> {
        val activity = Robolectric.buildActivity(FragmentActivity::class.java).setup().get()
        val parent = ConstraintLayout(activity)
        val contentBinding = TransactionResultContentBinding.inflate(activity.layoutInflater, parent)
        val binder = TransactionResultViewBinder(
            mockk<Wallet>(relaxed = true),
            MonetaryFormat().noCode(),
            contentBinding
        ) { }
        return binder to contentBinding
    }

    @Test
    fun `a second bindSdkDetail does not duplicate address rows`() {
        val (binder, contentBinding) = newBinder()

        binder.bindSdkDetail(withheldDetail())
        assertEquals(1, contentBinding.transactionInputAddressesContainer.childCount)
        assertEquals(0, contentBinding.transactionOutputAddressesContainer.childCount)

        // The mirror write lands: the sheet rebinds the SAME instance with
        // the now-resolved detail, as the ViewModel's refresh does.
        binder.bindSdkDetail(resolvedDetail())

        assertEquals(1, contentBinding.transactionInputAddressesContainer.childCount)
        assertEquals(1, contentBinding.transactionOutputAddressesContainer.childCount)
        val outputRow = contentBinding.transactionOutputAddressesContainer.getChildAt(0) as TextView
        assertEquals("yRecipientAddressForDW1659TestXX", outputRow.text.toString())

        // A third bind (e.g. a later unrelated TXO change) must still not pile up rows.
        binder.bindSdkDetail(resolvedDetail())
        assertEquals(1, contentBinding.transactionInputAddressesContainer.childCount)
        assertEquals(1, contentBinding.transactionOutputAddressesContainer.childCount)
    }

    @Test
    fun `a second bindSdkDetail does not duplicate the OP_RETURN row`() {
        val (binder, contentBinding) = newBinder()

        binder.bindSdkDetail(withheldDetail().copy(hasOpReturn = true))
        assertEquals(1, contentBinding.transactionOutputOpReturnsContainer.childCount)

        binder.bindSdkDetail(resolvedDetail().copy(hasOpReturn = true))
        assertEquals(1, contentBinding.transactionOutputOpReturnsContainer.childCount)
    }

    /**
     * DW-1659 review round (Andrei, 2026-11-12), sibling the sweep missed:
     * [bindSdkDetail] has the SAME unconditional title write as [bind]'s
     * [TransactionResultViewBinder.setTransactionDirection] (finding 3) — and
     * it is the function [TransactionDetailsDialogFragment] re-invokes on
     * every SDK-only-transaction mirror refresh.
     */
    @Test
    fun `a second bindSdkDetail does not lose a gift-card title`() {
        val (binder, contentBinding) = newBinder()
        val giftCardTitle = "DashDirect gift card"

        binder.bindSdkDetail(withheldDetail())
        binder.setCustomTitle(giftCardTitle)
        assertEquals(giftCardTitle, contentBinding.transactionTitle.text.toString())

        binder.bindSdkDetail(resolvedDetail())
        assertEquals(giftCardTitle, contentBinding.transactionTitle.text.toString())
    }

    /**
     * DW-1659 review round (Andrei, 2026-11-12): [bind]'s own DashPay-contact
     * branch, [TransactionResultViewBinder.setReturns] (reached here via
     * [TransactionResultViewBinder.setSentToReturn]) and
     * [TransactionResultViewBinder.setTransactionDirection]'s title write are
     * the three remaining call paths the sibling sweep on
     * [setInputs]/[setOutputs]/[setStringInputs]/[setStringOutputs] left
     * un-swept. [TransactionDetailsDialogFragment] re-invokes exactly this
     * sequence (bind → setSentToReturn → setCustomTitle) a second time, on
     * the same instance, once a withheld recipient's TXO mirror resolves —
     * binding the same detail twice must yield the same views as binding it
     * once.
     */
    @Test
    fun `a second bind of a contact payment with an OP_RETURN and a gift-card title does not duplicate rows or lose the title`() {
        val (binder, contentBinding) = newBinder()
        val params = TestNet3Params.get()
        Context.propagate(Context(params))

        val payeeAddress = Address.fromBase58(params, "yMY5bqWcknGy5xYBHSsh2xvHZiJsRucjuy")
        val tx = Transaction(params)
        tx.addOutput(Coin.valueOf(100_000), payeeAddress)
        tx.addOutput(Coin.ZERO, ScriptBuilder.createOpReturnScript(ByteArray(20)))

        val profile = DashPayProfile(userId = "contactUserId", username = "satoshi")
        val sdkOverride = SdkTxDetail(
            txIdDisplayHex = txId,
            netAmountDuffs = -100_247L,
            feeDuffs = 247L,
            timestampMs = 1_770_000_000_000L,
            status = L1TxUiStatus.IN_BLOCK,
            direction = L1TxUiDirection.OUTGOING,
            inputAddresses = emptyList(),
            outputAddresses = listOf(payeeAddress.toBase58()),
            hasOpReturn = true
        )
        val giftCardTitle = "DashDirect gift card"

        fun bindOnce() {
            binder.bind(tx, profile, sdkOverride = sdkOverride)
            binder.setSentToReturn(tx.versionShort, tx.type, error = false, completed = true)
            binder.setCustomTitle(giftCardTitle)
        }

        // First bind: the contact row, the OP_RETURN row, and the gift-card title.
        bindOnce()
        val outputsChildCount = contentBinding.outputsContainer.childCount
        val opReturnChildCount = contentBinding.transactionOutputOpReturnsContainer.childCount
        assertEquals(giftCardTitle, contentBinding.transactionTitle.text.toString())

        // The mirror write lands: the sheet re-runs the same sequence on the
        // SAME instance, as TransactionDetailsDialogFragment's refresh does.
        bindOnce()

        assertEquals(outputsChildCount, contentBinding.outputsContainer.childCount)
        assertEquals(opReturnChildCount, contentBinding.transactionOutputOpReturnsContainer.childCount)
        assertEquals(giftCardTitle, contentBinding.transactionTitle.text.toString())
    }

    /**
     * DW-1659 06b-prereview.md finding 1: [bind]'s DashPay-contact branch used to
     * `removeAllViews()` directly on [TransactionResultContentBinding.outputsContainer] /
     * [TransactionResultContentBinding.inputsContainer]. Those aren't dedicated row
     * containers — per `transaction_result_content.xml` they also hold the "Sent to"
     * caption label and the nested address/OP_RETURN container as layout-defined XML
     * siblings, so clearing them detached those siblings too. The NEXT
     * `TransactionResultContentBinding.bind(root)` — exactly what
     * `TransactionDetailsDialogFragment.initiateTransactionBinder()` /
     * `TransactionResultActivity.initiateTransactionBinder()` run on every re-bind —
     * then threw ViewBinding's "Missing required view" [NullPointerException], because
     * `bind()` re-resolves every required view by id via `findChildViewById`. Re-running
     * [TransactionResultViewBinder.bind] alone (as the other tests in this file do)
     * cannot see this: the crash is in ViewBinding's own `bind(root)` call, which none
     * of those tests invoke a second time on the same root.
     */
    @Test
    fun `a second TransactionResultContentBinding bind after a contact payment does not throw and keeps the caption and nested container`() {
        val (binder, contentBinding) = newBinder()
        val root = contentBinding.root
        val params = TestNet3Params.get()
        Context.propagate(Context(params))

        val payeeAddress = Address.fromBase58(params, "yMY5bqWcknGy5xYBHSsh2xvHZiJsRucjuy")
        val tx = Transaction(params)
        tx.addOutput(Coin.valueOf(100_000), payeeAddress)

        val profile = DashPayProfile(userId = "contactUserId", username = "satoshi")
        val sdkOverride = SdkTxDetail(
            txIdDisplayHex = txId,
            netAmountDuffs = -100_247L,
            feeDuffs = 247L,
            timestampMs = 1_770_000_000_000L,
            status = L1TxUiStatus.IN_BLOCK,
            direction = L1TxUiDirection.OUTGOING,
            inputAddresses = emptyList(),
            outputAddresses = listOf(payeeAddress.toBase58()),
            hasOpReturn = false
        )

        // First bind, as TransactionDetailsDialogFragment.onViewCreated() /
        // TransactionResultActivity.onCreate() do.
        binder.bind(tx, profile, sdkOverride = sdkOverride)

        // The re-bind every later refresh runs — TransactionDetailsDialogFragment /
        // TransactionResultActivity re-resolve the content binding from the SAME root
        // view before calling TransactionResultViewBinder.bind() again. Before the fix,
        // this threw because the first bind() had detached the caption label and the
        // nested address/OP_RETURN container from outputsContainer.
        TransactionResultContentBinding.bind(root)

        // The second TransactionResultViewBinder.bind() call the refresh then makes.
        binder.bind(tx, profile, sdkOverride = sdkOverride)

        assertEquals(contentBinding.outputsContainer, contentBinding.outputAddressesLabel.parent)
        assertEquals(contentBinding.outputsContainer, contentBinding.transactionOutputContainer.parent)

        val usernameRows = (0 until contentBinding.outputsContainer.childCount)
            .map { contentBinding.outputsContainer.getChildAt(it) }
            .filterIsInstance<TextView>()
            .filter { it.text == profile.username }
        assertEquals(1, usernameRows.size)
    }
}
