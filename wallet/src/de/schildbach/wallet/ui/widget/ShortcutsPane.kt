/*
 * Copyright 2020 Dash Core Group.
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

package de.schildbach.wallet.ui.widget

import android.content.Context
import android.util.AttributeSet
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.LinearLayout
import androidx.core.view.children
import androidx.core.view.isGone
import androidx.core.view.isVisible
import de.schildbach.wallet_test.R

class ShortcutsPane(context: Context, attrs: AttributeSet) : LinearLayout(context, attrs), View.OnClickListener {

    val secureNowButton: ShortcutButton by lazy {
        ShortcutButton(
            context,
            R.drawable.ic_shortcut_secure_now,
            R.string.shortcut_secure_now,
            this
        )
    }
    val receiveButton: ShortcutButton by lazy {
        ShortcutButton(
            context,
            R.drawable.ic_shortcut_receive,
            R.string.shortcut_receive,
            this
        )
    }
    val scanToPayButton: ShortcutButton by lazy {
        ShortcutButton(
            context,
            R.drawable.ic_shortcut_scan_to_pay,
            R.string.shortcut_scan_to_pay,
            this
        )
    }
    val payToAddressButton: ShortcutButton by lazy {
        ShortcutButton(
            context,
            R.drawable.ic_shortcut_pay_to_address,
            R.string.shortcut_pay_to_address,
            this
        )
    }
    val buySellButton: ShortcutButton by lazy {
        ShortcutButton(
            context,
            R.drawable.ic_shortcut_buy_sell_dash,
            R.string.shortcut_buy_sell,
            this
        )
    }
    val importPrivateKey: ShortcutButton by lazy {
        ShortcutButton(
            context,
            R.drawable.ic_shortcut_import_key,
            R.string.shortcut_import_key,
            this
        )
    }
    val configButton: ShortcutButton by lazy {
        ShortcutButton(
            context,
            R.drawable.ic_shortcut_add,
            R.string.shortcut_add_shortcut,
            this
        )
    }

    val explore: ShortcutButton by lazy {
        ShortcutButton(
            context,
            R.drawable.ic_shortcut_bar_explore,
            R.string.menu_explore_title,
            this
        )
    }

    private var isSmallScreen = resources.displayMetrics.densityDpi <= DisplayMetrics.DENSITY_MEDIUM
    private val secondaryItems = mutableListOf<ShortcutButton>()

    private var showSecureNow: Boolean = true
    private var userHasBalance: Boolean = false
    private var showJoinDashPay: Boolean = true
    private var showPayToContact: Boolean = true

    private var onShortcutClickListener: OnClickListener? = null

    init {
        setBackgroundResource(R.drawable.white_background_rounded)
        minimumHeight = 180
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_HORIZONTAL
        if (isInEditMode) {
            setup()
            refresh()
        }
        val onPreDrawListener = object : ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean {
                viewTreeObserver.removeOnPreDrawListener(this)
                val sizeRation = width.toFloat() / height.toFloat()
                isSmallScreen = (sizeRation < 3.3)
                setup()
                refresh()
                return false
            }
        }
        viewTreeObserver.addOnPreDrawListener(onPreDrawListener)
    }

    fun setup() {
        addShortcut(secureNowButton)
        if (userHasBalance) {
            secondaryItems.add(scanToPayButton)
        }
        secondaryItems.add(receiveButton)

        if (userHasBalance) {
            secondaryItems.add(payToAddressButton)
        }
        secondaryItems.add(buySellButton)
        secondaryItems.forEach {
            addShortcut(it)
        }

        addShortcut(explore)
    }

    private fun refresh() {
        val displayed = mutableSetOf<ShortcutButton>()

        secureNowButton.isVisible = showSecureNow
        if (secureNowButton.isVisible) {
            displayed.add(secureNowButton)
        }

        scanToPayButton.isVisible = userHasBalance
        if (scanToPayButton.isVisible) {
            displayed.add(scanToPayButton)
        }

        displayed.add(receiveButton)

        buySellButton.isVisible = !userHasBalance
        payToAddressButton.isVisible = userHasBalance
        if (userHasBalance) {
            displayed.add(payToAddressButton)
        } else {
            displayed.add(buySellButton)
        }

        explore.isGone = userHasBalance && showSecureNow
        if (explore.isVisible) {
            displayed.add(explore)
        }
    }

    fun showSecureNow(showSecureNow: Boolean) {
        this.showSecureNow = showSecureNow
        refresh()
    }

    fun userHasBalance(userHasBalance: Boolean) {
        this.userHasBalance = userHasBalance
        refresh()
    }

    fun showJoinDashPay(showJoinDashPay: Boolean) {
        this.showJoinDashPay = showJoinDashPay
        refresh()
    }

    fun showPayToContact(showPayToContact: Boolean) {
        this.showPayToContact = showPayToContact
        refresh()
    }

    private fun addShortcut(shortcut: ShortcutButton, index: Int = -1) {
        if (!children.contains(shortcut)) {
            val layoutParams = ViewGroup.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT)
            addView(shortcut, index, layoutParams)
        }
    }

    fun setOnShortcutClickListener(listener: OnClickListener) {
        onShortcutClickListener = listener
        setOnClickListener(null)
    }

    override fun onClick(v: View) {
        onShortcutClickListener?.onClick(v)
    }
}