/*
 * Copyright 2021 Dash Core Group.
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

package org.dash.wallet.features.exploredash.ui.adapters

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import coil.load
import coil.size.Scale
import coil.transform.RoundedCornersTransformation
import org.dash.wallet.features.exploredash.R
import org.dash.wallet.features.exploredash.databinding.MerchantLocationsHeaderBinding

class MerchantLocationsHeaderAdapter(private val name: String, private val type: String, private val image: String) :
    RecyclerView.Adapter<RecyclerView.ViewHolder>() {
    private lateinit var binding: MerchantLocationsHeaderBinding

    override fun getItemCount() = 1

    override fun getItemViewType(position: Int) = R.layout.merchant_locations_header

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        binding = MerchantLocationsHeaderBinding.inflate(inflater, parent, false)

        return object : RecyclerView.ViewHolder(binding.root) {}
    }

    override fun onBindViewHolder(viewHolder: RecyclerView.ViewHolder, position: Int) {
        binding.itemName.text = name
        binding.itemType.text = type

        binding.itemImage.load(image) {
            crossfade(200)
            scale(Scale.FILL)
            placeholder(R.drawable.ic_image_placeholder)
            error(R.drawable.ic_image_placeholder)
            transformations(
                RoundedCornersTransformation(
                    binding.itemImage.resources.getDimensionPixelSize(R.dimen.logo_corners_radius).toFloat()
                )
            )
        }
    }
}
