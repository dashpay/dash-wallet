/*
 * Copyright 2019 Dash Core Group
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package de.schildbach.wallet.ui

import android.graphics.drawable.TransitionDrawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.animation.Animation
import android.view.animation.AnimationUtils
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.widget.Toolbar
import androidx.fragment.app.Fragment
import de.schildbach.wallet_test.R
import de.schildbach.wallet_test.databinding.VerifySeedVerifyBinding
import org.dash.wallet.common.ui.viewBinding

/**
 * @author Samuel Barbosa
 */
class VerifySeedConfirmFragment : Fragment(R.layout.verify_seed_verify) {
    private val binding by viewBinding(VerifySeedVerifyBinding::bind)
    private val shakeAnimation by lazy { AnimationUtils.loadAnimation(context, R.anim.shake) }
    private val wordButtons = arrayListOf<View>()
    private val inflater by lazy { LayoutInflater.from(context) }
    private val buttonsMap = HashMap<String, Button>()
    private var words = arrayListOf<String>()

    companion object {
        fun newInstance(seed: Array<String>): VerifySeedConfirmFragment {
            val fragment = VerifySeedConfirmFragment()
            val args = Bundle()
            args.putStringArray("seed", seed)
            fragment.arguments = args
            return fragment
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        view.findViewById<Toolbar>(R.id.toolbar).title = getString(R.string.verify)

        val illegalState = IllegalStateException(
            "This fragment needs to receive a String[] containing the recovery seed"
        )
        if (arguments?.containsKey("seed")!!) {
            arguments?.getStringArray("seed")?.let {
                words.addAll(it)
            } ?: throw illegalState
        } else {
            throw illegalState
        }
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
                (context as VerifySeedActions).onSeedVerified()
            }
        } else {
            button.startAnimation(shakeAnimation)
        }
    }
}
