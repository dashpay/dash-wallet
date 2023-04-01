/*
 * Copyright 2023 Dash Core Group.
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

package de.schildbach.wallet.ui.verify

import android.graphics.drawable.TransitionDrawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.animation.Animation
import android.view.animation.AnimationUtils
import android.widget.Button
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.navigation.fragment.findNavController
import de.schildbach.wallet.ui.DecryptSeedViewModel
import de.schildbach.wallet_test.R
import de.schildbach.wallet_test.databinding.FragmentVerifyConfirmBinding
import org.dash.wallet.common.ui.viewBinding
import org.dash.wallet.common.util.safeNavigate

/**
 * @author Samuel Barbosa
 */
class VerifySeedConfirmFragment : Fragment(R.layout.fragment_verify_confirm) {
    private val binding by viewBinding(FragmentVerifyConfirmBinding::bind)
    private val viewModel: DecryptSeedViewModel by activityViewModels()

    private val shakeAnimation by lazy { AnimationUtils.loadAnimation(context, R.anim.shake) }
    private val wordButtons = arrayListOf<View>()
    private val inflater by lazy { LayoutInflater.from(context) }
    private val buttonsMap = HashMap<String, Button>()
    private var words = arrayListOf<String>()

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.verifyAppbar.toolbar.title = getString(R.string.verify)
        binding.verifyAppbar.toolbar.setNavigationOnClickListener { findNavController().popBackStack() }

        val seed = viewModel.seed.value ?: throw IllegalStateException("Recovery seed is empty")
        words.addAll(seed)

        for (word in words) {
            val button = inflater.inflate(R.layout.verify_seed_word_button, binding.wordButtonsContainer, false)
            button as Button
            button.setOnClickListener { verifyWord(it as Button) }
            button.text = word
            wordButtons.add(button)
            binding.wordButtonsContainer.addView(button)
            buttonsMap[word] = button
        }
        binding.wordButtonsContainer.postDelayed({
            wordButtons.shuffle()
            binding.wordButtonsContainer.removeAllViews()
            for (b in wordButtons) {
                binding.wordButtonsContainer.addView(b)
            }
        }, 750)
    }

    private val addedWordClickListener = View.OnClickListener {
        binding.recoverySeedContainer.removeView(it)
        buttonsMap[(it as TextView).text]?.isEnabled = true
        words.add(0, it.text.toString())
    }

    private fun verifyWord(button: Button) {
        val transitionDuration = 33
        val transitionDrawable = (button.background as TransitionDrawable)
        transitionDrawable.startTransition(transitionDuration)
        shakeAnimation.setAnimationListener(object : Animation.AnimationListener {
            override fun onAnimationRepeat(p0: Animation?) {
            }

            override fun onAnimationStart(animation: Animation) {
            }

            override fun onAnimationEnd(animation: Animation) {
                transitionDrawable.reverseTransition(transitionDuration)
            }
        })
        val word = button.text.toString()
        if (words.first() == word) {
            words.removeAt(0)
            button.isEnabled = false
            val tv: TextView = inflater.inflate(
                R.layout.verify_seed_word_tv,
                binding.recoverySeedContainer,
                false
            ) as TextView
            tv.text = word
            binding.recoverySeedContainer.addView(tv)
            tv.setOnClickListener(addedWordClickListener)
            if (words.size == 0) {
                viewModel.onBackedUp()
                safeNavigate(VerifySeedConfirmFragmentDirections.confirmToSuccess())
            }
        } else {
            button.startAnimation(shakeAnimation)
        }
    }
}
