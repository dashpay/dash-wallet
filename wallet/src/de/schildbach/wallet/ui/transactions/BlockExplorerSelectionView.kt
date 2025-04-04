package de.schildbach.wallet.ui.transactions

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.ripple.rememberRipple
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import de.schildbach.wallet.ui.compose_views.Colors
import de.schildbach.wallet.ui.compose_views.Shadows.softShadow
import de.schildbach.wallet_test.R

@Composable
fun BlockExplorerSelectionView(
    onExplorerSelected: (BlockExplorer) -> Unit
) {
    val explorers = remember {
        listOf(
            BlockExplorer.BLOCKCHAIR,
            BlockExplorer.INSIGHT
        )
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp))
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 28.dp)
        ) {
            Text(
                text = stringResource(R.string.block_explorer_selection_title),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = Colors.textPrimary,
                textAlign = TextAlign.Center,
                modifier = Modifier.align(Alignment.Center)
            )
        }

        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(top = 25.dp, bottom = 50.dp)
                .softShadow(cornerRadius = 12.dp),
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(containerColor = Colors.backgroundSecondary)
        ) {
            Column(
                modifier = Modifier.padding(vertical = 6.dp)
            ) {
                explorers.forEach { explorer ->
                    Box(modifier = Modifier
                        .fillMaxWidth()
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = rememberRipple(color = Colors.textPrimary),
                            onClick = { onExplorerSelected(explorer) }
                        )
                    ) {
                        Row(
                            modifier = Modifier
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier.size(26.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                when (explorer) {
                                    BlockExplorer.BLOCKCHAIR -> Icon(
                                        painter = painterResource(id = R.drawable.ic_blockchair_logo),
                                        contentDescription = null,
                                        tint = Color.Unspecified
                                    )
                                    BlockExplorer.INSIGHT -> Icon(
                                        painter = painterResource(id = R.drawable.ic_dash_d_blue),
                                        contentDescription = null,
                                        tint = Color.Unspecified
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.width(16.dp))
                            val resources = LocalContext.current.resources
                            Text(
                                text = resources.getStringArray(R.array.preferences_block_explorer_labels)[explorer.index],
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium,
                                color = Colors.textPrimary
                            )
                        }
                    }
                }
            }
        }
    }
}

// Index must be kept in sync with preferences_block_explorer_values
enum class BlockExplorer(val index: Int) {
    INSIGHT(0),
    BLOCKCHAIR(1)
}

@Preview
@Composable
fun BlockExplorerSelectionViewPreview() {
    Box(Modifier.background(Colors.backgroundPrimary)) {
        BlockExplorerSelectionView { }
    }
}