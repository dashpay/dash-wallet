### WOC Buy API Setup (Android)

#### Installation

Setup [Retrofit](https://github.com/square/retrofit) for making API call. 
Create Structure (Classes and interface) for make API call with Retrofit
Set Base URL: http://woc.reference.genitrust.com/api/v1



#### GET AVAILABLE PAYMENT CENTERS (OPTIONAL)

API for get payment center list using GET method...

```http
GET http://woc.reference.genitrust.com/api/v1/banks/
```

##### Response : 

```json
[{
    "id": 14,
    "name": "Genitrust",
    "url": "http://genitrust.com/",
    "logo": null,
    "logoHq": null,
    "icon": null,
    "iconHq": null,
    "country": "us",
    "payFields": false},...
]
```
This method is optional.



#### SEARCH & DISCOVERY

An API for discover available option, which will return Discovery ID along with list of information.

```http
POST http://woc.reference.genitrust.com/api/v1/discoveryInputs/
```

##### Request :

```json
{
  "publisherId": "",
  "cryptoAddress": "",
  "usdAmount": "500",
  "crypto": "DASH",
  "bank": "",
  "zipCode": "34236"
}
```

>   Publisher Id: an Unique ID generated for commit transections.
>   cryptoAddress: Cryptographic Address for user, it's optional parameter.
>   usdAmount: Amount in USD (Need to apply conversation from DASH to USD)
>   crypto: crypto type either DASH or BTC for bitcoin.
>   bank: Selected bank ID from bank list. pass empty if selected none.
>   zipCode: zip code of user, need to take input from user.

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
GET http://woc.reference.genitrust.com/api/v1/discoveryInputs/<Discovery ID>/offers/
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
    "bankLogoHq": "/media/logos/logo_us_MoneyGram%402x.png",
    "bankIcon": "/media/logos/icon_us_MoneyGram.png",
    "bankIconHq": "/media/logos/icon_us_MoneyGram%402x.png",
    "bankLocationUrl": "https://secure.moneygram.com/locations",
    "city": ""},
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
        "bankLogoHq": "/media/logos/logo_us_Chase%402x.png",
        "bankIcon": "/media/logos/icon_us_Chase.png",
        "bankIconHq": "/media/logos/icon_us_Chase%402x.png",
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
        "bankLogoHq": "/media/logos/logo_us_Chase%402x.png",
        "bankIcon": "/media/logos/icon_us_Chase.png",
        "bankIconHq": "/media/logos/icon_us_Chase%402x.png",
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


#### CREATE HOLD

From offer list on offer click we have to create an hold on offer for generate initial request.

```http
HEADER X-Coins-Api-Token: 

POST http://woc.reference.genitrust.com/api/v1/holds/
```

It need X-Coins-Api-Token as a header parameter which is five time mobile number without space and country code.

##### Request :

```json
{
  "publisherId": "",
  "offer": "eyJ1c2QiOiAiNTA...",
  "phone": "+19411101467",
  "deviceName": "Ref Client",
  "password": "94111014679411101467941110146794111014679411101467"
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
  This API will send purchase code to user's device and it will be same as `__PURCHASE_CODE` value.



#### CAPTURE HOLD

We have to match user input code with `__PURCHASE_CODE`  and if verify, we have to proceed further.

```http
HEADER X-Coins-Api-Token: ZGV2aWNlOjQ0NT...

POST http://woc.reference.genitrust.com/api/v1/holds/<Hold ID>/capture/
```

#####Request : 

```
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
    "bankIconHq": "/media/logos/icon_us_MoneyGram%402x.png",
    "privateId": "c149c6e90e13de979ff12e0aaa2a9c4d9f88d510"
    }
]
```


it will confirm the user authentication with  `__PURCHASE_CODE`  and in next step we have to confirm or cancel request with Order ID received in last response.



#### CONFIRM DEPOSIT

```http
HEADER X-Coins-Api-Token: 

POST http://woc.reference.genitrust.com/api/v1/orders/<Order ID>/confirmDeposit/
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
  "bankIconHq": "/media/logos/icon_us_MoneyGram%402x.png",
  "privateId": "c149c6e90e13de979ff12e0aaa2a9c4d9f88d510"
}
```

it will provide transaction details which will be display to user for proceed manually at bank location.
