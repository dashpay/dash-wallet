package org.dash.wallet.integration.uphold

import android.content.Context
import org.dash.wallet.integration.uphold.data.UpholdApiException
import org.junit.Assert.*
import org.junit.Test
import org.mockito.ArgumentMatchers.anyString
import org.mockito.kotlin.*

class UpholdErrorTest {

    @Test
    fun error403Test() {
        val fake403Description = "403_FAKE_STRING_%s"
        val fakeIdentityStr = "IDENTITY_FAKE_STRING"
        val fakeDueDiligenceStr = "DUE_DILIGENCE_FAKE_STRING"
        val fakeGenericStr = "GENERIC_FAKE_STRING"
        val fakeProofOfAddressStr = "PROOF_OF_ADDRESS_STRING"

        val mockContext = mock<Context> {
            on { getString(R.string.uphold_api_error_403_due_diligence) } doReturn fakeDueDiligenceStr
            on { getString(R.string.uphold_api_error_403_identity) } doReturn fakeIdentityStr
            on { getString(R.string.uphold_api_error_403_proof_of_address) } doReturn fakeProofOfAddressStr
            on { getString(R.string.uphold_api_error_403_generic) } doReturn fakeGenericStr
            on { getString(eq(R.string.uphold_api_error_403_description), anyString()) } doAnswer {
                fake403Description.format(it.getArgument(1) as String)
            }
        }

        val firstError = """
            {
                "capability": "sends",
                "code": "forbidden",
                "message": "Quote not allowed due to capability constraints",
                "requirements": ["user-must-submit-identity"],
                "restrictions": []
            }
        """.trimIndent()
        val firstException = UpholdApiException(firstError, 403)
        val arguments = HashMap<String, String>()
        assertTrue(firstException.isForbiddenError(arguments))
        assertEquals(403, firstException.code)
        assertEquals("user-must-submit-identity", arguments["requirements"])
        assertEquals(fake403Description.format(fakeIdentityStr), firstException.getDescription(mockContext))

        val secondError = """
            {
                "capability": "trades",
                "code": "forbidden",
                "message": "Quote not allowed due to capability constraints",
                "requirements": [ "user-must-submit-enhanced-due-diligence" ],
                "restrictions": []
            }
        """.trimIndent()
        val secondException = UpholdApiException(secondError, 403)
        arguments.clear()
        assertTrue(secondException.isForbiddenError(arguments))
        assertEquals(403, secondException.code)
        assertEquals("user-must-submit-enhanced-due-diligence", arguments["requirements"])
        assertEquals(fake403Description.format(fakeDueDiligenceStr), secondException.getDescription(mockContext))

        val thirdError = """
            {
                "capability": "withdrawals",
                "code": "forbidden",
                "message": "Quote not allowed due to capability constraints",
                "requirements": [],
                "restrictions": [
                  "user-status-not-valid"
                ]
            }
        """.trimIndent()

        val thirdException = UpholdApiException(thirdError, 403)
        arguments.clear()
        assertTrue(thirdException.isForbiddenError(arguments))
        assertEquals(403, thirdException.code)
        assertEquals(null, arguments["requirements"])
        assertEquals(fake403Description.format(fakeGenericStr), thirdException.getDescription(mockContext))

        val fourthError = """
            {
                "capability": "deposits",
                "code": "forbidden",
                "message": "Quote not allowed due to capability constraints",
                "requirements": [ "user-must-submit-proof-of-address" ],
                "restrictions": []
            }
        """.trimIndent()
        val fourthException = UpholdApiException(fourthError, 403)
        arguments.clear()
        assertTrue(fourthException.isForbiddenError(arguments))
        assertEquals(403, fourthException.code)
        assertEquals("user-must-submit-proof-of-address", arguments["requirements"])
        assertEquals(fake403Description.format(fakeProofOfAddressStr), fourthException.getDescription(mockContext))
    }

    @Test
    fun authenticationMethodChangedTest() {
        val error = """
            {
               "code":"validation_failed",
               "errors":
               {
                 "user":[
                   {
                       "code":"restricted_by_authentication_method_reset",
                       "message":"The user is restricted because authentication method has been changed recently",
                       "args":{
                           "recentAuthenticationRestrictionEndDate":"2021-09-21T16:02:59.605Z"
                       }
                   }
                 ]
               }
            }
        """.trimIndent()
        val exception = UpholdApiException(error, 400)
        val arguments = HashMap<String, String>()
        assertTrue(exception.isValidationFailed(arguments))
        assertEquals("restricted_by_authentication_method_reset", arguments["code"])
        assertEquals(400, exception.code)
    }

    @Test
    fun passwordChangedTest() {
        val error = """
            {
              "code": "validation_failed",
              "errors": {
                "user": [
                  {
                    "code": "password_reset_restriction",
                    "message": "The user password has been changed in the last 1 day",
                    "args": {
                      "recentPasswordRestrictionEndDate": "2021-09-16T17:19:34.996Z",
                      "threshold": 1,
                      "unit": "day"
                    }
                  }
                ]
              }
            }
        """.trimIndent()
        val exception = UpholdApiException(error, 400)
        val arguments = HashMap<String, String>()
        assertTrue(exception.isValidationFailed(arguments))
        assertEquals("password_reset_restriction", arguments["code"])
        assertEquals(400, exception.code)
    }

    @Test
    fun transferLimitsTest() {
        val error = """
            {
              "code":"validation_failed",
              "errors":{
                "beneficiary":[
                  {
                    "code":"required",
                    "message":"This value is required"
                  }
                ],
                "purpose": [
                  {
                    "code":"required",
                    "message":"This value is required"
                  }
                ]
              }
            }
        """.trimIndent()
        val exception = UpholdApiException(error, 400)
        val arguments = HashMap<String, String>()
        assertTrue(exception.isValidationFailed(arguments))
        assertEquals("required", arguments["code"])
        assertEquals(400, exception.code)
    }

    @Test
    fun cooldownPeriodTest() {
        val error = """
        {
           "code":"validation_failed",
           "errors":{
             "denomination": {
               "code": "validation_failed",
               "errors":{
                 "amount":[
                   {
                     "code":"sufficient_unlocked_funds",
                     "message":"You will have sufficient funds by 2021-11-24T03:38:58.663Z",
                     "args":{
                       "availableAt":"2021-11-24T03:38:58.663Z",
                       "currency":"DASH",
                       "missing":"6.21186161",
                       "restrictions":["ach-deposit-settlement","ach-deposit-cooldown"]
                     }
                   }
                 ]
               }
             }
           }
        }
        """.trimIndent()
        val exception = UpholdApiException(error, 400)
        val arguments = HashMap<String, String>()
        assertTrue(exception.isValidationFailed(arguments))
        assertEquals("sufficient_unlocked_funds", arguments["code"])
        assertEquals("6.21186161", arguments["missing"])
        assertEquals("DASH", arguments["currency"])
        assertEquals(400, exception.code)
    }

    @Test
    fun insufficientFundsTest() {
        val error = """
            {
              "code": "validation_failed",
              "errors": {
                "denomination": {
                  "code": "validation_failed",
                  "errors": {
                    "amount": [
                      {
                        "code": "sufficient_funds",
                        "message": "Not enough funds for the specified amount"
                      }
                    ]
                  }
                }
              }
            }
        """.trimIndent()
        val exception = UpholdApiException(error, 400)
        val arguments = HashMap<String, String>()
        assertTrue(exception.isValidationFailed(arguments))
        assertEquals("sufficient_funds", arguments["code"])
        assertEquals(400, exception.code)
    }

    @Test
    fun lessThanOrEqual() {
        val error = """
            {
              "code": "validation_failed",
              "errors": {
                "destination": {
                  "code": "validation_failed",
                  "errors": {
                    "amount": [
                      {
                        "code": "less_than_or_equal_to",
                        "message": "This value should be less than or equal to 25",
                        "args": {
                          "threshold": "25"
                        }
                      }
                    ]
                  }
                }
              }
            }
        """.trimIndent()
        val exception = UpholdApiException(error, 400)
        val arguments = HashMap<String, String>()
        assertTrue(exception.isValidationFailed(arguments))
        assertEquals("less_than_or_equal_to", arguments["code"])
        assertEquals("25", arguments["threshold"])
        assertEquals(400, exception.code)
    }

    @Test
    fun greaterThanOrEqual() {
        val error = """
            {
              "code":"validation_failed",
              "errors":{
                "denomination":{
                  "code":"validation_failed",
                  "errors":{
                    "amount":[
                      {
                        "code":"greater_than_or_equal_to",
                        "message":"This value should be greater than or equal to 0",
                        "args":{
                          "threshold":"0"
                        }
                      }
                    ]
                  }
                }
              }
            }
        """.trimIndent()
        val exception = UpholdApiException(error, 400)
        val arguments = HashMap<String, String>()
        assertTrue(exception.isValidationFailed(arguments))
        assertEquals("greater_than_or_equal_to", arguments["code"])
        assertEquals("0", arguments["threshold"])
        assertEquals(400, exception.code)
    }
}
