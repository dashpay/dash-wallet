/*
 * Copyright (c) 2022. Dash Core Group.
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

import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.util.DisplayMetrics
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.animation.Animation
import android.view.animation.AnimationUtils
import android.widget.RelativeLayout
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import androidx.fragment.app.DialogFragment
import dagger.hilt.android.AndroidEntryPoint
import de.schildbach.wallet_test.R
import kotlinx.android.synthetic.main.transaction_details_dialog.*
import kotlinx.android.synthetic.main.transaction_reclassification_content.*
import kotlinx.android.synthetic.main.transaction_reclassification_content.close_btn
import kotlinx.android.synthetic.main.transaction_reclassification_dialog.*
import kotlinx.android.synthetic.main.transaction_reclassification_dialog.transaction_classification_content_container
import kotlinx.android.synthetic.main.transaction_result_content.*

import org.slf4j.LoggerFactory

/**
 * @author Samuel Barbosa
 */
@AndroidEntryPoint
class ClassificationDialogFragment : DialogFragment() {

    private val log = LoggerFactory.getLogger(javaClass.simpleName)

    companion object {


        @JvmStatic
        fun newInstance(): ClassificationDialogFragment {
            val fragment = ClassificationDialogFragment()
            return fragment
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val dm = DisplayMetrics()
        requireActivity().windowManager.defaultDisplay.getMetrics(dm)
        transaction_classification_content_container.background = ContextCompat.getDrawable(requireContext(), R.drawable.rounded_corners_bgd_light_gray)
        transaction_classification_content_container.updateLayoutParams<RelativeLayout.LayoutParams> {
            topMargin = 30
        }

        //open_explorer_card.setOnClickListener { viewOnBlockExplorer() }
        close_btn.setOnClickListener { dismissAnimation() }
        second_close_btn.setOnClickListener { dismissAnimation() }

        showAnimation()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setStyle(STYLE_NO_FRAME, org.dash.wallet.common.R.style.FullScreenDialog)
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        dialog?.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            requestFeature(Window.FEATURE_NO_TITLE)
        }
        return inflater.inflate(R.layout.transaction_reclassification_dialog, container, false)
    }

    private fun showAnimation() {
        val contentAnimation = AnimationUtils.loadAnimation(activity, R.anim.slide_in_bottom)
        transaction_classification_content_container.postDelayed({
            val container = transaction_classification_content_container
            container.translationY = container.measuredHeight.toFloat()
            container.visibility = View.VISIBLE
            container.startAnimation(contentAnimation)
        }, 150)
    }

    private fun dismissAnimation() {
        val contentAnimation = AnimationUtils.loadAnimation(activity, R.anim.slide_out_bottom)
        transaction_classification_content_container.startAnimation(contentAnimation)
        val containerAnimation = AnimationUtils.loadAnimation(activity, R.anim.fade_out)
        transaction_classification_content_container.postDelayed({
            transaction_classification_content_container.startAnimation(containerAnimation)
        }, 150)
        containerAnimation.setAnimationListener(object : Animation.AnimationListener {
            override fun onAnimationEnd(animation: Animation?) {
                dismiss()
            }

            override fun onAnimationRepeat(animation: Animation?) {}

            override fun onAnimationStart(animation: Animation?) {}
        })
    }
}