package org.dash.wallet.common.ui.segmented_picker

import android.content.Context
import android.util.AttributeSet
import android.util.Log
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.core.view.ViewCompat.animate
import androidx.core.view.doOnAttach
import androidx.core.view.doOnLayout
import androidx.core.view.doOnPreDraw
import org.dash.wallet.common.R
import org.dash.wallet.common.databinding.SegmentedPickerBinding

class SegmentedPicker(context: Context, attrs: AttributeSet): FrameLayout(context, attrs) {
    private val binding = SegmentedPickerBinding.inflate(LayoutInflater.from(context), this)
    private val options = listOf("Option 1", "Option 2", "Option 3")

    init {
        setBackgroundResource(R.drawable.segmented_picker_background)

        val adapter = PickerOptionsAdapter(options) { option, index ->
            animate(binding.thumb).apply {
                duration = 200
                translationX(measuredWidth / options.size * index.toFloat())
            }.start()
        }

        binding.options.layoutManager = LinearLayoutManager(context, RecyclerView.HORIZONTAL, false)
        binding.options.adapter = adapter
    }
}
