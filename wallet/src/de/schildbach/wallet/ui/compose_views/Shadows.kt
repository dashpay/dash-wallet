package de.schildbach.wallet.ui.compose_views

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

object Shadows {
    private val softShadowColor = Color(0x1AB8C1CC)
    
    fun Modifier.softShadow(cornerRadius: Dp): Modifier = this.shadow(
        elevation = 20.dp,
        shape = RoundedCornerShape(cornerRadius),
        spotColor = softShadowColor,
        ambientColor = Color(0x00B8C1CC),
        clip = false
    )
} 