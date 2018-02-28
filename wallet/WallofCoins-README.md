### Wall of Coins Platform API

To receive sales affiliate commissions, you must have a Publisher account with Wall of Coins. Visit this page for more information: https://wallofcoins.com/developer-api

API endpoints:

* Production: https://wallofcoins.com/api/v1
* Development: https://woc.reference.genitrust.com/api/v1

### **Authentication methods**

WOC API supports authentication via auth token.

#### **Auth token:**

In order to be authenticated you should send a token within every API request. Token must be sent in request header called **‘X-Coins-Api-Token’**. Token must not be expired. Token must have a valid signature and all needed authentication data. Token will look like a long **base64** encoded string: **YXV0aDo2OjE0MjE1OTU1ODN8MDk3NTAyYmE1YzM4YWY4MzUxYTg1NDU2ODFjN2U4ODgyZDhkYmY0Yg==** Each token has a limited lifetime (currently 3 hours, but it can be changed). Token expiration time is always returned by API. Your application should care about automatical token renewal before it expires.


#### GET AVAILABLE PAYMENT CENTERS (OPTIONAL)

API for get payment center list using GET method...

```http
GET https://woc.reference.genitrust.com/api/v1/banks/
```

##### Response :

```json
[
  {
    "id": 14,
    "name": "Genitrust",
    "url": "http://genitrust.com/",
    "logo": null,
    "icon": null,
    "country": "us",
    "payFields": false
  }
]
```
This method is optional.



#### SEARCH & DISCOVERY

An API for discover available option, which will return Discovery ID along with list of information.



```http

HEADER:
        X-Coins-Publisher: ##
        Content-Type: application/json

POST https://woc.reference.genitrust.com/api/v1/discoveryInputs/
```

##### Request :

```json
{
  "publisherId": "",
  "cryptoAddress": "",
  "usdAmount": "500",
  "crypto": "DASH",
  "bank": "",
  "zipCode": "34236",
  "browserLocation":  {
    "latitude": 27.3331293,
    "longitude": -82.5456374
  }
}
```

*   Publisher Id: an Unique ID generated for commit transections.
*   cryptoAddress: Cryptographic Address for user, it's optional parameter.
*   usdAmount: Amount in USD (Need to apply conversation from DASH to USD)
*   crypto: crypto type either DASH or BTC for bitcoin.
*   bank: Selected bank ID from bank list. pass empty if selected none.
*   zipCode: zip code of user, need to take input from user.
*   browserLocation: Need to pass user device latitude and Longitude.

Note: when you specify the browserLocation parameter, it must have real values (latitude,longitude) and browserLocation will be used. When browserLocation is not set, you must have zipCode set to a valid zipCode. when zipCode is not set, you must specify the bank.
bank > browser location > zip code > country (get this value by phone # country code, by 'country' parameter should be used by the most specific location to the broadest location. so for example, bank is the most direct, then browser location, then zip code, then country.

##### Response :

```json
{
  "id": "935c882fe79e39e1acd98a801d8ce420",
  "usdAmount": "500",
  "cryptoAmount": "0",
  "crypto": "DASH",
  "fiat": "USD",
  "zipCode": "34236",
  "bank": 5,
  "state": null,
  "cryptoAddress": "",
  "createdIp": "182.76.224.130",
  "location": {
    "latitude": 27.3331293,
    "longitude": -82.5456374
  },
  "browserLocation": null,
  "publisher": null
}
```


#### GET OFFERS

An API for fetch all offers for received Discovery ID.

```http
GET https://woc.reference.genitrust.com/api/v1/discoveryInputs/<Discovery ID>/offers/
```

##### Response :

```json
{
  "singleDeposit": [{
    "id": "eyJ1c2QiOiAiNTA2LjAw...",
    "deposit": {
        "currency": "USD",
        "amount": "506.00"
    },
    "crypto": "DASH",
    "amount": {
        "DASH": "52.081",
        "dots": "52,081,512.22",
        "bits": "52,081,512.22",
        "BTC": "52.081"
    },
    "discoveryId": "1260e3afa4f03a195ac1e73c965c797",
    "distance": 0,
    "address": "",
    "state": "",
    "bankName": "MoneyGram",
    "bankLogo": "/media/logos/logo_us_MoneyGram.png",
    "bankIcon": "/media/logos/icon_us_MoneyGram.png",
    "bankLocationUrl": "https://secure.moneygram.com/locations",
    "city": ""}
  ],
  "doubleDeposit": [{
    "id": "eyJkaSI6IC...",
    "firstOffer": {
        "deposit": {
            "currency": "USD",
            "amount": "462.00"
        },
        "crypto": "DASH",
        "amount": {
            "DASH": "44.809",
            "dots": "44,809,058.44",
            "bits": "44,809,058.44",
            "BTC": "44.809"
        },
        "discoveryId": "1260e3afa4f03a195ac1e73c965c797",
        "distance": 0.9639473391774286,
        "address": "240 N Washington Blvd, #100",
        "state": "FL",
        "bankName": "Chase",
        "bankLogo": "/media/logos/logo_us_Chase.png",
        "bankIcon": "/media/logos/icon_us_Chase.png",
        "bankLocationUrl": null,
        "city": "Sarasota"
    },
    "secondOffer": {
        "deposit": {
            "currency": "USD",
            "amount": "38.00"
        },
        "crypto": "DASH",
        "amount": {
            "DASH": "0.368",
            "dots": "368,122.62",
            "bits": "368,122.62",
            "BTC": "0.368"
        },
        "discoveryId": "1260e3afa4f03a195ac1e73c965c797",
        "distance": 0.9639473391774286,
        "address": "240 N Washington Blvd, #100",
        "state": "FL",
        "bankName": "Chase",
        "bankLogo": "/media/logos/logo_us_Chase.png",
        "bankIcon": "/media/logos/icon_us_Chase.png",
        "bankLocationUrl": null,
        "city": "Sarasota"
    },
    "totalAmount": {
        "bits": "45,177,181.06",
        "BTC": "45.177"
    },
    "totalDeposit": {
        "currency": "USD",
        "amount": "500.00"
    }
  }],
  "multipleBanks": [],
  "isExtendedSearch": false,
  "incremented": true
}
```

When they Discovery Input offers object contains...

```
{
  . . .
  incremented: true
}
```

This means that the search amount (for example "$25") was not available for all offers--some offers that are available REQUIRE the end user (buyer) to deposit slightly more than $25. This is what is meant by the term ```incremented``` so buyer need to pay that incremented amount for buy dash.


#### GET AUTH DETAILS

**GET /api/v1/auth/<phone>/**

This endpoint will return HTTP 404 if phone is not registered in our system, otherwise it will return a list of available authentication methods.

**GET /api/v1/auth/15005550001/**

```http
HEADER:
        X-Coins-Publisher: ##
        Content-Type: application/json
```
It need  X-Coins-Publisher as a header parameter.


##### Request :

```json
{
"phone": "15005550001",
"availableAuthSources": [
    "device"
    ]
}
```
This endpoint will return HTTP 404 if phone is not registered in our system then call Create Hold
, otherwise it will return a list of available authentication methods.


#### POST AUTH DETAILS (PASSWORD)

**POST /api/v1/auth/<phone>/authorize**

This endpoint will return **token**  and use that **token** as **auth token** to create hold. You must need to pass **deviceId** with **password** in this API.

**POST /api/v1/auth/15005550001/authorize/**

```http
HEADER:
        X-Coins-Publisher: ##
        Content-Type: application/json
```
It need  X-Coins-Publisher as a header parameter.


##### Request :

```json
{
    "deviceId" : "768",
    "password" : "abc123",
    "publisherId" : "52"
}
```

##### Response :

```json
{
  "accessedOn" : "2018-02-16T06:28:41.745351Z",
  "authSource" : "password",
  "createdOn" : "2014-08-29T02:19:45.826334Z",
  "email" : "",
  "phone" : "12397776832",
  "token" : "YXV0aDoxOjE1MTg3NzMzMjF8OWFjMWQ5ZmNiOGU1OWZhOThhNTg3YWM2YjBlZWUxMDk1NGM3NGI3OQ==",
  "tokenExpiresAt" : "2018-02-16T09:28:41.742641Z"
}
```

#### POST AUTH DETAILS (DEVICE)

**POST /api/v1/auth/<phone>/authorize**

This endpoint will return **token**  and use that **token** as **auth token** to create hold. You must need to pass **deviceId** with **deviceCode** in this API.

**POST /api/v1/auth/15005550001/authorize/**

```http
    HEADER:
    X-Coins-Publisher: ##
    Content-Type: application/json
```
It need  X-Coins-Publisher as a header parameter.


##### Request :

```json
{
    "deviceId" : 769,
    "deviceCode" : "C1813921-DD87-4BE6-9F73-D78B603CF1C8",
    "publisherId" : "52"
}
```

##### Response :

```json
{
  "accessedOn" : "2018-02-16T06:28:41.745351Z",
  "authSource" : "password",
  "createdOn" : "2014-08-29T02:19:45.826334Z",
  "email" : "",
  "phone" : "12397776832",
  "token" : "YXV0aDoxOjE1MTg3NzMzMjF8OWFjMWQ5ZmNiOGU1OWZhOThhNTg3YWM2YjBlZWUxMDk1NGM3NGI3OQ==",
  "tokenExpiresAt" : "2018-02-16T09:28:41.742641Z"
}
```


#### CREATE HOLD

##### (NEW USER REGISTER USING PHONE NUMBER)
From offer list on offer click we have to create an hold on offer for generate initial request.

```http
HEADER:
        X-Coins-Publisher: ##
        Content-Type: application/json

POST https://woc.reference.genitrust.com/api/v1/holds/
```

It need  X-Coins-Publisher as a header parameter.

##### Request :

```json
{
  "publisherId": "",
  "offer": "eyJ1c2QiOiAiNTA...",
  "phone": "+19411101467",
  "deviceName": "Ref Client",
  "deviceCode": "device-code-is-a-password_and_MUST_be_30_characters_or_more"
}
```

##### (Existing user request for create hold)

```http
HEADER:
        X-Coins-Api-Token: ZGV2aWNlOjQ0NT...
        X-Coins-Publisher: ##
        Content-Type: application/json

POST https://woc.reference.genitrust.com/api/v1/holds/
```

It need X-Coins-Publisher and X-Coins-Api-Token as a header parameter.

##### Request :

```json
{
  "publisherId": "",
  "offer": "eyJ1c2QiOiAiNTA...",
  "phone": "+19411101467",
  "deviceName": "Ref Client",
  "deviceCode": "device-code-is-a-password_and_MUST_be_30_characters_or_more"
}
```


##### Response :

```json
{
  "id": "999fd1b03f78309988a64701cfaaae37",
  "expirationTime": "2017-08-21T10:08:40.592Z",
  "discoveryInput": "1260e3afa4f03a195ac1e73c965c797",
  "holds": [{
    "amount": "53.65853659",
    "currentPrice": "9.43",
    "status": ""
  }],
  "token": "ZGV2aWNlOjQ0N...",
  "tokenExpiresAt": "2017-08-21T13:05:40.535Z",
  "__PURCHASE_CODE": "CK99K"
}
```


##### Status Code :

* 201 returned when the hold is created
* 400 returned when one of the parameters are missing! for example, if you're creating a new device... you need "phone", "deviceName", and "deviceCode".
* 401 will be "returned when a X-Coins-Api-Token is required or the phone number supplied needs password".
* 403 will have details: "Not permitted to create a hold while an open hold or order with status WD is current."
* 404 returned when the offer no-longer is available (either the time expired or the ad will now be negative.)

if you have token then you have to pass only offer in JSON request

if you get status code 403, then you have to delete active hold first then need to create hold again. As you have **auth token** available, you need to call Get Hold API by passing **auth token** as **X-Coins-Api-Token** in header, which will return active hold in response. Get Hold API returns array of hold in response, then user need to delete active hold with status is equal to “AC” by calling Delete Hold API by passing **auth token** as **X-Coins-Api-Token** in header. Suppose user do not get any active holds then it means that there is some pending order. so you need to get all orders and find pending order with status = "WD" and give option to confirm deposite Or cancel Order functionality.

##### Hold Status :

PE = Pending
AC = Active, waiting for Order
CAP = Captured
EX = Expired
CAN = Canceled

This API will send purchase code to user's device on his register phone number and it will be same
as `__PURCHASE_CODE` value.


#### GET HOLD

**GET /api/v1/holds/**

This endpoint will return active **holdId**. You must need to pass **auth token** as **X-Coins-Api-Token** in header of this API.

```http
HEADER:
    X-Coins-Publisher: ##
    X-Coins-Api-Token: ZGV2aWN..
```
It need  X-Coins-Publisher and X-Coins-Api-Token as a header parameter.

##### Response

```json
[
  {
    "id": "ec11665efc6eeb8e8ca083360c70a659",
    "expirationTime": "2018-02-16T06:38:14.408227Z",
    "discoveryInput": "8f4a39a2ca29607da6d8c891e8318b26"
  }
]
```

Use **id** as **holdId** to delete an active hold.

#### DELETE HOLD

This endpoint will delete an active hold. You must need to pass **auth token** as **X-Coins-Api-Token** in header of this API.

```http
HEADER:
    X-Coins-Publisher: ##
    X-Coins-Api-Token: ZGV2aWN..
DELETE https://woc.reference.genitrust.com/api/v1/holds/<hold-Id>/
```
It need  X-Coins-Publisher and X-Coins-Api-Token as a header parameter.

##### Response :
204 NO CONTENT

#### CAPTURE HOLD

We have to match user input code with `__PURCHASE_CODE`  and if verify, we have to proceed further.

```http
HEADER:
       X-Coins-Api-Token: ZGV2aWNlOjQ0NT...
       X-Coins-Publisher: ##
       Content-Type: application/json

POST https://woc.reference.genitrust.com/api/v1/holds/<Hold ID>/capture/
```

#####Request :

```json
{
  "publisherId": "",
  "verificationCode": "CK99K"
}
```

##### Response :


```json
[
  {
    "id": 81,
    "total": "52.08151222",
    "payment": "506.0000000437",
    "paymentDue": "2017-08-21T12:15:49.024Z",
    "bankName": "MoneyGram",
    "nameOnAccount": "",
    "account": "[{\"displaySort\": 2.0, \"name\": \"birthCountry\", \"value\": \"US\", \"label\": \"Country of Birth\"}, {\"displaySort\": 0.5, \"name\": \"pickupState\", \"value\": \"Florida\", \"label\": \"Pick-up State\"}, {\"displaySort\": 1.0, \"name\": \"lastName\", \"value\": \"Genito\", \"label\": \"Last Name\"}, {\"displaySort\": 0.0, \"name\": \"firstName\", \"value\": \"Robert\", \"label\": \"First Name\"}]",
    "status": "WD",
    "nearestBranch": {
        "city": "",
        "state": "",
        "name": "MoneyGram",
        "phone": null,
        "address": ""
    },
    "bankUrl": "https://secure.moneygram.com",
    "bankLogo": "/media/logos/logo_us_MoneyGram.png",
    "bankIcon": "/media/logos/icon_us_MoneyGram.png",
    "privateId": "c149c6e90e13de979ff12e0aaa2a9c4d9f88d510"
    }
]
```


it will confirm the user authentication with  `__PURCHASE_CODE`  and in next step we have to confirm or cancel request with Order ID received in last response.



#### CONFIRM DEPOSIT

```http
REQUEST HEADER:
        X-Coins-Api-Token: ZGV2aWNlOjQ0NT...
        X-Coins-Publisher: ##
        Content-Type: application/json

POST https://woc.reference.genitrust.com/api/v1/orders/<Order ID>/confirmDeposit/
```

##### Response

```json
{
  "id": 81,
  "total": "52.08151222",
  "payment": "506.00",
  "paymentDue": "2017-08-21T12:15:49.024Z",
  "bankName": "MoneyGram",
  "nameOnAccount": "",
  "account": "[{\"displaySort\": 2.0, \"name\": \"birthCountry\", \"value\": \"US\", \"label\": \"Country of Birth\"}, {\"displaySort\": 0.5, \"name\": \"pickupState\", \"value\": \"Florida\", \"label\": \"Pick-up State\"}, {\"displaySort\": 1.0, \"name\": \"lastName\", \"value\": \"Genito\", \"label\": \"Last Name\"}, {\"displaySort\": 0.0, \"name\": \"firstName\", \"value\": \"Robert\", \"label\": \"First Name\"}]",
  "status": "WDV",
  "nearestBranch": {
    "city": "",
    "state": "",
    "name": "MoneyGram",
    "phone": null,
    "address": ""
  },
  "bankUrl": "https://secure.moneygram.com",
  "bankLogo": "/media/logos/logo_us_MoneyGram.png",
  "bankIcon": "/media/logos/icon_us_MoneyGram.png",
  "privateId": "c149c6e90e13de979ff12e0aaa2a9c4d9f88d510"
}
```
This method used for confirm user order

#### CANCEL ORDER

```http
REQUEST HEADER:
        X-Coins-Api-Token: ZGV2aWNlOjQ0NT...
        X-Coins-Publisher: ##
        Content-Type: application/json

DELETE https://woc.reference.genitrust.com/api/v1/orders/{orderId}/?<publisherId>
```

##### Response
    204 NO CONTENT
    
This method is used for cancel order by orderId created by user.

#### ORDER LIST

```http
REQUEST HEADER:
        X-Coins-Api-Token: ZGV2aWNlOjQ0NT...
        X-Coins-Publisher: ##
        Content-Type: application/json

GET https://woc.reference.genitrust.com/api/v1/orders/?<publisherId>
```


##### Response

```json
[
  {
    "id": 100823,
    "total": "0.43763420",
    "payment": "5.52",
    "paymentDue": "2018-02-08T11:06:06.842Z",
    "bankName": "Money Gram",
    "nameOnAccount": "",
    "account": "[{\"displaySort\": 0.0, \"name\": \"firstName\", \"value\": \"Paul\", \"label\": \"First Name\"}, {\"displaySort\": 1.0, \"name\": \"lastName\", \"value\": \"Alberto\", \"label\": \"Last Name\"}, {\"displaySort\": 2.0, \"name\": \"birthCountry\", \"value\": \"USA\", \"label\": \"Country of Birth\"}, {\"displaySort\": 1.5, \"name\": \"pickupState\", \"value\": \"FL\", \"label\": \"Pick-up State\"}]",
    "status": "WDV",
    "nearestBranch": {
      "city": "",
      "state": "",
      "name": "Money Gram",
      "phone": null,
      "address": ""
 },
  "bankUrl": "https://moneygram.com/",
  "bankLogo": "/media/logos/logo_us_Money%20Gram.png",
  "bankIcon": "/media/logos/icon_us_Money%20Gram.png",
  "privateId": "d674d55f9e",
  "currencyName": "US Dollar",
  "currencySymbol": "$",
  "cryptoName": "Dash",
  "cryptoSymbol": "\u0110"
  }
]
```
This method is user for get user order list

Below is the list of order status for user orders history:

WD = Waiting Deposit
WDV = Waiting Deposit Verification
RERR = Issue with Receipt
DERR = Issue with Deposit
RSD = Reserved for Deposit
RMIT = Remit Address Missing
UCRV = Under Review
PAYP = Done - Pending Delivery
SENT = Done - Units Delivered

If you get status = 'WD', then you will need to display 'Status: Waiting Deposit' to end user for all orders. 

#### CREATE DEVICE

```http
HEADER:
       X-Coins-Api-Token: ZGV2aWNlOjQ0NT...
       X-Coins-Publisher: ##
       Content-Type: application/json

POST https://woc.reference.genitrust.com/api/v1/devices/
```

#####Request :

```json
{
  "name": "Test Device",
  "code": "CK99K"
}
```

##### Response :


```json
{
  "id": 540,
  "name": "Dash Wallet (Android)",
  "createdOn": "2018-02-12T14:53:55.234Z"
}
```
This method use for register new device with API.
DeviceID(id) will be used to as Header parameter in "api/v1/auth/{phone}/authorize/" API for 
Authorize user 

#### GET DEVICES

```http
HEADER:
       X-Coins-Api-Token: ZGV2aWNlOjQ0NT...
       X-Coins-Publisher: ##
       Content-Type: application/json

GET https://woc.reference.genitrust.com/api/v1/devices/
```

##### Response :


```json
[
  {
    "id": 11,
    "name": "rgenito",
    "createdOn": "2015-05-13T00:17:49.210Z"
  },
  {
    "id": 13,
    "name": "rgenito",
    "createdOn": "2015-08-05T00:00:43.421Z"
  },
  {
    "id": 15,
    "name": "rgenito",  
    "createdOn": "2015-12-07T17:32:45.992Z"
  }
]
```
This method is use for get all devices for register user