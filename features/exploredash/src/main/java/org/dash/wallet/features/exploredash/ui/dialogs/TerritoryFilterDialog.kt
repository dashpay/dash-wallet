package org.dash.wallet.features.exploredash.ui.dialogs

import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.core.widget.doOnTextChanged
import androidx.fragment.app.DialogFragment
import org.dash.wallet.common.ui.ListDividerDecorator
import org.dash.wallet.common.ui.viewBinding
import org.dash.wallet.features.exploredash.R
import org.dash.wallet.features.exploredash.databinding.DialogTerritoryFilterBinding
import org.dash.wallet.features.exploredash.ui.adapters.TerritoryAdapter
import org.dash.wallet.features.exploredash.ui.viewitems.TerritoryViewItem


class TerritoryFilterDialog(
    private val territories: List<String>,
    private val selectedTerritory: String,
    private val clickListener: (String, DialogFragment) -> Unit
) : OffsetDialogFragment() {

    private val binding by viewBinding(DialogTerritoryFilterBinding::bind)

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.dialog_territory_filter, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val allStatesName = getString(R.string.explore_all_states)
        val fullTerritoryList = listOf(
            TerritoryViewItem(
                allStatesName,
                selectedTerritory.isEmpty()
            )
        ) + territories.map {
            TerritoryViewItem(it, it == selectedTerritory)
        }

        val adapter = TerritoryAdapter { item, _ ->
            clickListener.invoke(if (item.name == allStatesName) "" else item.name, this)
        }
        val divider = ContextCompat.getDrawable(requireContext(), R.drawable.list_divider)!!
        val decorator = ListDividerDecorator(divider, false)
        binding.territories.addItemDecoration(decorator)
        binding.territories.adapter = adapter
        adapter.submitList(fullTerritoryList)

        binding.search.doOnTextChanged { text, _, _, _ ->
            binding.clearBtn.isVisible = !text.isNullOrEmpty()
            if (text.isNullOrBlank()) {
                adapter.submitList(fullTerritoryList)
            } else {
                val filteredList = fullTerritoryList.filter {
                    it.name.lowercase().contains(text.toString().lowercase())
                }
                adapter.submitList(filteredList)
            }
        }

        binding.search.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                val inputManager = requireContext()
                    .getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                inputManager.toggleSoftInput(0, 0)
            }

            true
        }

        binding.clearBtn.setOnClickListener {
            binding.search.text.clear()
        }
    }
}