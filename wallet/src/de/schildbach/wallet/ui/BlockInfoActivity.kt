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

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.appcompat.widget.Toolbar
import dagger.hilt.android.AndroidEntryPoint
import de.schildbach.wallet.data.BlockInfo
import de.schildbach.wallet.ui.util.showBlockExplorerSelectionSheet
import de.schildbach.wallet_test.R
import de.schildbach.wallet_test.databinding.ActivityBlockInfoBinding
import org.dash.wallet.common.Configuration
import javax.inject.Inject

@AndroidEntryPoint
class BlockInfoActivity : LockScreenActivity() {

    companion object {

        private const val BLOCK_INFO_EXTRA = "block_info"

        @JvmStatic
        fun createIntent(context: Context, blockInfo: BlockInfo): Intent {
            val intent = Intent(context, BlockInfoActivity::class.java)
            intent.putExtra(BLOCK_INFO_EXTRA, blockInfo)
            return intent
        }
    }

    private lateinit var binding: ActivityBlockInfoBinding
    @Inject
    lateinit var config: Configuration

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityBlockInfoBinding.inflate(layoutInflater)
        setContentView(binding.root)
        val toolbar = findViewById<Toolbar>(R.id.toolbar)
        toolbar.title = getString(R.string.block_info)
        toolbar.setNavigationOnClickListener { finish() }

        val blockInfo = intent.getSerializableExtra(BLOCK_INFO_EXTRA) as BlockInfo
        binding.blockHeight.text = "${blockInfo.height}"
        binding.blockTime.text = blockInfo.time
        binding.blockHash.text = blockInfo.hash

        binding.viewOnExplorer.setOnClickListener {
            showBlockExplorerSelectionSheet("block/" + blockInfo.hash)
        }
    }
}
