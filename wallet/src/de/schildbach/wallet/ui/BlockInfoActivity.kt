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
import android.net.Uri
import android.os.Bundle
import de.schildbach.wallet.WalletApplication
import de.schildbach.wallet.data.BlockInfo
import de.schildbach.wallet_test.R
import kotlinx.android.synthetic.main.activity_block_info.*

class BlockInfoActivity : BaseMenuActivity() {

    companion object {

        private const val BLOCK_INFO_EXTRA = "block_info"

        @JvmStatic
        fun createIntent(context: Context, blockInfo: BlockInfo): Intent {
            val intent = Intent(context, BlockInfoActivity::class.java)
            intent.putExtra(BLOCK_INFO_EXTRA, blockInfo)
            return intent
        }
    }

    override fun getLayoutId(): Int {
        return R.layout.activity_block_info
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setTitle(R.string.block_info)

        val blockInfo = intent.getSerializableExtra(BLOCK_INFO_EXTRA) as BlockInfo
        block_height.text = "${blockInfo.height}"
        block_time.text = blockInfo.time
        block_hash.text = blockInfo.hash

        view_on_explorer.setOnClickListener {
            val config = WalletApplication.getInstance().configuration
            startActivity(Intent(Intent.ACTION_VIEW, Uri.withAppendedPath(
                    config.getBlockExplorer(R.array.preferences_block_explorer_values),
                    "block/" + blockInfo.hash)))
        }
    }

}
