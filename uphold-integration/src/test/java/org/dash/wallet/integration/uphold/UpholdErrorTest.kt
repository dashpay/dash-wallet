package org.dash.wallet.integration.uphold

import org.dash.wallet.integration.uphold.data.UpholdApiException
import org.junit.Test
import org.junit.Assert.*

class UpholdErrorsTest {

    @Test
    fun error403Test() {
        val firstError = """
            {
                "capability": "sends",
                "code": "forbidden",
                "message": "Quote not allowed due to capability constraints",
                "requirements": ["user-must-submit-identity"],
                "restrictions": []
            }
        """.trimIndent()
        val firstException = UpholdApiException(firstError, 403);
        val arguments = HashMap<String, String>()
        firstException.isForbiddenError(arguments)
        assertEquals("user-must-submit-identity", arguments["requirements"])

        val secondError = """
            {
                "capability": "trades",
                "code": "forbidden",
                "message": "Quote not allowed due to capability constraints",
                "requirements": [ "user-must-submit-enhanced-due-diligence" ],
                "restrictions": []
            }
        """.trimIndent()
        val secondException = UpholdApiException(secondError, 403);
        arguments.clear()
        secondException.isForbiddenError(arguments)
        assertEquals("user-must-submit-enhanced-due-diligence", arguments["requirements"])

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

        val thirdException = UpholdApiException(thirdError, 403);
        arguments.clear()
        thirdException.isForbiddenError(arguments)
        assertEquals(null, arguments["requirements"])
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
        val exception = UpholdApiException(error, 400);
        val arguments = HashMap<String, String>()
        exception.isValidationFailed(arguments)
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
        val exception = UpholdApiException(error, 400);
        val arguments = HashMap<String, String>()
        exception.isValidationFailed(arguments)
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
        val exception = UpholdApiException(error, 400);
        val arguments = HashMap<String, String>()
        exception.isValidationFailed(arguments)
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
        val exception = UpholdApiException(error, 400);
        val arguments = HashMap<String, String>()
        exception.isValidationFailed(arguments)
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
        val exception = UpholdApiException(error, 400);
        val arguments = HashMap<String, String>()
        exception.isValidationFailed(arguments)
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
        val exception = UpholdApiException(error, 400);
        val arguments = HashMap<String, String>()
        exception.isValidationFailed(arguments)
        assertEquals("less_than_or_equal_to", arguments["code"])
        assertEquals("25", arguments["threshold"])
        assertEquals(400, exception.code)
    }
}