package de.schildbach.wallet.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.animation.Animation
import android.view.animation.AnimationUtils
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import de.schildbach.wallet_test.R
import kotlinx.android.synthetic.main.verify_seed_verify.*

/**
 * @author Samuel Barbosa
 */
class VerifySeedActivity : AppCompatActivity() {

    private val shakeAnimation by lazy { AnimationUtils.loadAnimation(this, R.anim.shake) }
    private val errorButtonDrawable by lazy { ContextCompat.getDrawable(this, R.drawable.error_button) }
    private val primaryButtonDrawable by lazy { ContextCompat.getDrawable(this, R.drawable.primary_button) }
    private val wordButtonsContainer by lazy { word_buttons_container }
    private val recoverySeedContainer by lazy { recovery_seed }
    private val wordButtons = arrayListOf<View>()
    private val inflater by lazy { LayoutInflater.from(this) }
    private val buttonsMap = HashMap<String, Button>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.verify_seed_verify)

        val words = listOf("netword", "stand", "grind", "bundle", "need", "eight", "blast",
                "topic", "depth", "right", "desk", "faith")
        for (word in words) {
            val button = inflater.inflate(R.layout.verify_seed_word_button, wordButtonsContainer, false)
            button as Button
            button.setOnClickListener { verifyWord(it as Button) }
            button.text = word
            wordButtons.add(button)
            wordButtonsContainer.addView(button)
            buttonsMap[word] = button
        }

        wordButtonsContainer.postDelayed({
            wordButtons.shuffle()
            wordButtonsContainer.removeAllViews()
            for (b in wordButtons) {
                wordButtonsContainer.addView(b)
            }
        }, 1300)

        /*
        recovery_seed.text = "network   stand   grid   bundle   need   eight   blast   topic   depth   right   desk   faith"
        written_down_checkbox.setOnCheckedChangeListener { buttonView, isChecked ->
            confirm_written_down_btn.isEnabled = isChecked
        }*/
    }

    private val addedWordClickListener = View.OnClickListener {
        recoverySeedContainer.removeView(it)
        buttonsMap[(it as TextView).text]?.isEnabled = true
    }

    private fun verifyWord(button: Button) {
        shakeAnimation.setAnimationListener(object : Animation.AnimationListener {
            override fun onAnimationRepeat(p0: Animation?) {

            }

            override fun onAnimationStart(animation: Animation) {
                button.background = errorButtonDrawable
            }

            override fun onAnimationEnd(animation: Animation) {
                button.background = primaryButtonDrawable
            }
        })
        if (wordButtonsContainer.indexOfChild(button) % 2 == 0) {
            button.startAnimation(shakeAnimation)
        } else {
            button.isEnabled = false
            val newWord = button.text.toString()
            val tv: TextView = inflater.inflate(R.layout.verify_seed_word_tv, recoverySeedContainer,
                    false) as TextView
            tv.text = newWord
            recoverySeedContainer.addView(tv)
            tv.setOnClickListener(addedWordClickListener)
        }
    }

}