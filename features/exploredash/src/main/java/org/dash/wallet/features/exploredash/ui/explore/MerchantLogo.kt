/*
 * Copyright 2026 Dash Core Group.
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

package org.dash.wallet.features.exploredash.ui.explore

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import org.dash.wallet.common.ui.components.MerchantNameIcon
import org.dash.wallet.features.exploredash.R

/**
 * Renders a merchant's icon, choosing the best available source:
 *  1. [logoBitmap] — an already-loaded logo bitmap (e.g. from transaction metadata),
 *  2. [logoUrl] — a remote logo loaded with Coil,
 *  3. otherwise a generated full-name icon ([MerchantNameIcon]) so merchants with no
 *     logo still get a recognisable, branded placeholder instead of a generic icon.
 */
@Composable
fun MerchantLogo(
    merchantName: String?,
    modifier: Modifier = Modifier,
    logoUrl: String? = null,
    logoBitmap: Bitmap? = null,
    size: Dp = 40.dp,
    shape: Shape = RoundedCornerShape(8.dp)
) {
    when {
        logoBitmap != null -> Image(
            bitmap = logoBitmap.asImageBitmap(),
            contentDescription = merchantName,
            contentScale = ContentScale.Crop,
            modifier = modifier
                .size(size)
                .clip(shape)
        )
        !logoUrl.isNullOrBlank() -> AsyncImage(
            model = logoUrl,
            contentDescription = merchantName,
            contentScale = ContentScale.Crop,
            placeholder = painterResource(R.drawable.ic_image_placeholder),
            error = painterResource(R.drawable.ic_image_placeholder),
            modifier = modifier
                .size(size)
                .clip(shape)
        )
        else -> MerchantNameIcon(
            merchantName = merchantName ?: "",
            modifier = modifier,
            size = size,
            shape = shape
        )
    }
}
