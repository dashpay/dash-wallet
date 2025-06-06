/*
 * Copyright (c) 2025. Dash Core Group.
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

package org.dash.wallet.features.exploredash

import org.dash.wallet.features.exploredash.repository.CTXSpendException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CTXSpendExceptionTest {
    @Test
    fun limitErrorTest() {
        val errorBody = """
            {
              "fields": {
                "fiatAmount": [
                  "above threshold"
                ],
                "message": "Bad request"
              }
            }
        """
        val exception = CTXSpendException("response error", 400, errorBody)
        assertEquals(400, exception.errorCode)
        assertTrue(exception.isLimitError)
    }

    @Test
    fun unknownErrorTest() {
        val exception = CTXSpendException("other type of error")
        assertEquals(null, exception.errorCode)
        assertFalse(exception.isLimitError)
    }

    @Test
    fun malformedJsonErrorTest() {
        val malformedErrorBody = "{ this is not valid json }"
        val exception = CTXSpendException("response error", 400, malformedErrorBody)
        assertEquals(400, exception.errorCode)
        // Verify that parsing errors don't cause the app to consider this a limit error
        assertFalse(exception.isLimitError)
    }
}
