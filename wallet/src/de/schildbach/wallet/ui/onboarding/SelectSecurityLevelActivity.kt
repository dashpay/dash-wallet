package de.schildbach.wallet.ui.onboarding

import android.content.Intent
import android.os.Bundle
import de.schildbach.wallet_test.databinding.ActivityPhrasewordcountBinding
import org.dash.wallet.common.SecureActivity

class SelectSecurityLevelActivity : SecureActivity() {
    private lateinit var binding: ActivityPhrasewordcountBinding
    private var selectedWordCount: Int = 12

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPhrasewordcountBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.titleBar.setOnClickListener {
            setResult(RESULT_CANCELED)
            finish()
        }

        binding.twelveWordsOption.setOnClickListener {
            setMode(12)
        }

        binding.twentyFourWordsOption.setOnClickListener {
            setMode(24)
        }

        binding.continueBtn.setOnClickListener {
            setResult(RESULT_OK, Intent().apply { putExtra(EXTRA_WORD_COUNT, selectedWordCount) })
            finish()
        }
        setMode(12)
    }

    private fun setMode(wordCount: Int) {
        if (wordCount == 12) {
            binding.twelveWordsOption.isSelected = true
            binding.twentyFourWordsOption.isSelected = false
            selectedWordCount = 12
        } else {
            binding.twelveWordsOption.isSelected = false
            binding.twentyFourWordsOption.isSelected = true
            selectedWordCount = 24
        }
    }

    companion object {
        const val EXTRA_WORD_COUNT = "extra_word_count"
    }
}