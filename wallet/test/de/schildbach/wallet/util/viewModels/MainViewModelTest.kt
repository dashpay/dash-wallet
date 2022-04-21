/*
 * Copyright 2022 Dash Core Group.
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

package de.schildbach.wallet.util.viewModels

import android.content.ClipDescription
import android.content.ClipboardManager
import de.schildbach.wallet.ui.MainActivityViewModel
import io.mockk.*
import junit.framework.TestCase.assertEquals
import org.junit.Test

class MainViewModelTest {
    @Test
    fun getClipboardInput_noClip_returnsEmptyString() {
        val clipboardManagerMock = mockk<ClipboardManager>()
        val viewModel = spyk(MainActivityViewModel(mockk(), clipboardManagerMock))

        every { clipboardManagerMock.hasPrimaryClip() } returns false

        val clipboardInput = viewModel.getClipboardInput()
        assertEquals("", clipboardInput)
    }

    @Test
    fun getClipboardInput_returnsCorrectText() {
        val mockUri = "mock://example.uri"
        val mockText = "some text"
        val clipboardManagerMock = mockk<ClipboardManager>()
        val viewModel = spyk(MainActivityViewModel(mockk(), clipboardManagerMock))
        val clipDescription = mockk<ClipDescription>()

        every { clipboardManagerMock.hasPrimaryClip() } returns true
        every { clipboardManagerMock.primaryClip?.description } returns clipDescription

        every { clipboardManagerMock.primaryClip?.getItemAt(0)?.uri?.toString() } returns mockUri
        every { clipDescription.hasMimeType(ClipDescription.MIMETYPE_TEXT_URILIST) } returns true
        every { clipDescription.hasMimeType(ClipDescription.MIMETYPE_TEXT_PLAIN) } returns false
        every { clipDescription.hasMimeType(ClipDescription.MIMETYPE_TEXT_HTML) } returns false

        var clipboardInput = viewModel.getClipboardInput()
        assertEquals(mockUri, clipboardInput)

        every { clipboardManagerMock.primaryClip?.getItemAt(0)?.text?.toString() } returns mockText
        every { clipDescription.hasMimeType(ClipDescription.MIMETYPE_TEXT_URILIST) } returns false
        every { clipDescription.hasMimeType(ClipDescription.MIMETYPE_TEXT_PLAIN) } returns true
        every { clipDescription.hasMimeType(ClipDescription.MIMETYPE_TEXT_HTML) } returns false

        clipboardInput = viewModel.getClipboardInput()
        assertEquals(mockText, clipboardInput)

        every { clipDescription.hasMimeType(ClipDescription.MIMETYPE_TEXT_URILIST) } returns false
        every { clipDescription.hasMimeType(ClipDescription.MIMETYPE_TEXT_PLAIN) } returns false
        every { clipDescription.hasMimeType(ClipDescription.MIMETYPE_TEXT_HTML) } returns true

        clipboardInput = viewModel.getClipboardInput()
        assertEquals(mockText, clipboardInput)
    }
}