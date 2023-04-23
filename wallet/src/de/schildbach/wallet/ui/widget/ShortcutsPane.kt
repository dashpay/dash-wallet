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
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import androidx.core.view.children
import com.google.android.flexbox.AlignItems
import com.google.android.flexbox.FlexDirection
import com.google.android.flexbox.FlexboxLayout
import com.google.android.flexbox.JustifyContent
import de.schildbach.wallet_test.R
import kotlin.math.min

class ShortcutsPane(context: Context, attrs: AttributeSet) : FlexboxLayout(context, attrs), View.OnClickListener {

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
    val payToContactButton: ShortcutButton by lazy {
        ShortcutButton(context,
                R.drawable.ic_shortcut_pay_to_contact,
                R.string.shortcut_pay_to_contact,
                this)
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
    private var onShortcutClickListener: OnClickListener? = null

    private val shortcuts = listOf(
        secureNowButton,
        explore,
        receiveButton,
        payToContactButton,
        buySellButton,
        scanToPayButton
    )

    var isPassphraseVerified: Boolean = true
        set(value) {
            secureNowButton.shouldAppear = !value

            if (field != value) {
                field = value
                refresh()
            }
        }

    var userHasBalance: Boolean = true
        set(value) {
            scanToPayButton.shouldAppear = value
            buySellButton.shouldAppear = !value
            payToAddressButton.shouldAppear = value
            payToContactButton.shouldAppear = value

            if (field != value) {
                field = value
                refresh()
            }
        }

    init {
        setBackgroundResource(R.drawable.white_background_rounded)
        minimumHeight = 180
        flexDirection = FlexDirection.ROW
        justifyContent = JustifyContent.SPACE_EVENLY
        alignItems = AlignItems.CENTER
        if (isInEditMode) {
            refresh()
        }
        val onPreDrawListener = object : ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean {
                viewTreeObserver.removeOnPreDrawListener(this)
                val sizeRation = width.toFloat() / height.toFloat()
                isSmallScreen = (sizeRation < 3.3)
                refresh()
                return false
            }
        }
        viewTreeObserver.addOnPreDrawListener(onPreDrawListener)
    }

    private fun refresh() {
        var slotsLeft = if (isSmallScreen) 3 else 4

        shortcuts.forEach { btn ->
            if (btn.shouldAppear && slotsLeft > 0) {
                addShortcut(btn)
                slotsLeft--
            } else {
                removeShortcut(btn)
            }
        }
    }

    private fun addShortcut(shortcut: ShortcutButton) {
        if (!children.contains(shortcut)) {
            val index = min(childCount, shortcuts.indexOf(shortcut))
            val layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            addView(shortcut, index, layoutParams)
        }
    }

    private fun removeShortcut(shortcut: ShortcutButton) {
        if (children.contains(shortcut)) {
            removeView(shortcut)
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
