package org.dash.wallet.common.util

import android.text.*
import android.text.method.LinkMovementMethod
import android.text.style.ClickableSpan
import android.view.View
import android.widget.TextView
import androidx.annotation.ColorRes
import androidx.core.content.ContextCompat
import java.util.*

fun TextView.makeLinks(vararg links: Pair<String, View.OnClickListener>, @ColorRes linkColor: Int = -1, isUnderlineText: Boolean = true) {
    val spannableString = SpannableString(this.text)
    var startIndexOfLink = -1
    for (link in links) {
        val clickableSpan = object : ClickableSpan() {
            override fun updateDrawState(textPaint: TextPaint) {
                textPaint.isUnderlineText = isUnderlineText
                if (linkColor != -1) {
                    textPaint.color = ContextCompat.getColor(context, linkColor)
                }
            }

            override fun onClick(view: View) {
                Selection.setSelection((view as TextView).text as Spannable, 0)
                view.invalidate()
                link.second.onClick(view)
            }
        }

        startIndexOfLink = this.text.toString().toLowerCase(Locale.getDefault()).indexOf(
            link.first.toLowerCase(
                Locale.getDefault()
            ),
            startIndexOfLink + 1
        )

        if (startIndexOfLink == -1) continue // todo if you want to verify your texts contains links text

        spannableString.setSpan(
            clickableSpan,
            startIndexOfLink,
            startIndexOfLink + link.first.length,
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
        )
    }

    this.setText(spannableString, TextView.BufferType.SPANNABLE)
    this.movementMethod =
        LinkMovementMethod.getInstance()
    this.linksClickable = true
}
