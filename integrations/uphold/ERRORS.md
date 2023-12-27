
# Authentication Changed
```json
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
```

# Password Changed
```json
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
```

# Uphold Transfer Limits ($3000)
```json
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
```

# Uphold Cooldown Period
```json
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
```
# Insufficent Funds
This is probably avoided since the integration queries the balance before allowing a transfer.
```json
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
```
# Less than or Equal
The cause for this error is not known.
```json
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
```
```json
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
```

# Uphold Account is Under Review
Error: 403
```json
{
  "error": {
    "capability": "crypto_withdrawals",
    "code": "forbidden",
    "message": "Quote not allowed due to capability constraints",
    "requirements": [],
    "restrictions": [
      "user-status-not-valid"
    ]
  }
}
```

# User needs to submit answers to more questions
The following is an example of the error message when creating the transaction:

HTTP 403
```json
{
"capability": "trades",
"code": "forbidden",
"message": "Quote not allowed due to capability constraints",
"requirements": [ "user-must-submit-enhanced-due-diligence" ],
"restrictions": []
}
```
The capability may be different, depending on the type of transaction.

# Identity Not Verified
Uphold is also asking existing EU/UK users who haven’t verified their identity to do so. 
Some actions like pulling funds from user’s accounts will trigger the following error:

HTTP 403
```json
{
"capability": "sends",
"code": "forbidden",
"message": "Quote not allowed due to capability constraints",
"requirements": ["user-must-submit-identity"],
"restrictions": []
}
```