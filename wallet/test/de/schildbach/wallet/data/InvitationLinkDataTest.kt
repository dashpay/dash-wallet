/*
 * Copyright 2026 Dash Core Group
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

package de.schildbach.wallet.data

import android.app.Application
import android.net.Uri
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28], manifest = Config.NONE)
class InvitationLinkDataTest {
    private val base = "dashpay://invite?du=alice&assetlocktx=a0963d1b&pk=cNihCPEQ&islock=0101b1" +
        "&display-name=Test%20Wallet%20(CoinJoin)"

    @Test
    fun `single-encoded avatar url is read as is`() {
        val link = Uri.parse("$base&avatar-url=https%3A%2F%2Fi.imgur.com%2FJgGDEQB.jpeg")
        assertTrue(InvitationLinkData.isValid(link))
        assertEquals("https://i.imgur.com/JgGDEQB.jpeg", InvitationLinkData(link).avatarUrl)
    }

    @Test
    fun `single-encoded avatar url keeps its own percent escapes`() {
        val link = Uri.parse("$base&avatar-url=https%3A%2F%2Fexample.com%2Fmy%2520avatar.png")
        assertEquals("https://example.com/my%20avatar.png", InvitationLinkData(link).avatarUrl)
    }

    @Test
    fun `double-encoded avatar url from older wallets is still decoded`() {
        val link = Uri.parse("$base&avatar-url=https%253A%252F%252Fi.imgur.com%252FJgGDEQB.jpeg")
        assertEquals("https://i.imgur.com/JgGDEQB.jpeg", InvitationLinkData(link).avatarUrl)
    }

    @Test
    fun `missing avatar url is empty`() {
        val data = InvitationLinkData(Uri.parse(base))
        assertEquals("", data.avatarUrl)
        assertEquals("Test Wallet (CoinJoin)", data.displayName)
    }
}
