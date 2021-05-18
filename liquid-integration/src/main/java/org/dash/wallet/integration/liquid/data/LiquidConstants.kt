package org.dash.wallet.integration.liquid.data

object LiquidConstants {

//      const val PUBLIC_API_KEY = "pk_sandbox_b2985a24-d772-45c3-9ac9-fca940548c2f"
   const val PUBLIC_API_KEY = "pk_28ef60cd-5bde-44c0-b5cc-99b6e69e348c"// Production key


    const val CLIENT_BASE_URL = "https://partners.liquid.com/"
    // const val CLIENT_BASE_URL = "https://api-sandbox.uphold.com/"


    const val INITIAL_URL = "https://partners.liquid.com/api/v1/session/"

    const val LOGOUT_URL = "https://app.liquid.com/sign-out"

    const val OAUTH_CALLBACK_SCHEMA = "liquid-oauth"
    const val OAUTH_CALLBACK_HOST = "callback"
    const val OAUTH_CALLBACK_URL = "$OAUTH_CALLBACK_SCHEMA://$OAUTH_CALLBACK_HOST"


    const val COUNTRY_NOT_SUPPORTED = " https://help.liquid.com/en/articles/2272984-can-i-use-liquid-in-my-country"

    const val BUY_WITH_CREDIT_CARD_URL = "https://plugin.partners.liquid.com"//"https://sandbox-demo.partners.liquid.com"//https://plugin.partners.liquid.com

}