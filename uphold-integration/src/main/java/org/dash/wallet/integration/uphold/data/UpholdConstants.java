package org.dash.wallet.integration.uphold.data;

public class UpholdConstants {

    public static String CLIENT_ID = "";
    public static String CLIENT_SECRET = "";
    public static String CLIENT_BASE_URL;
    public static String INITIAL_URL;
    public static String CARD_URL_BASE;
    public static String TRANSACTION_URL;
    public static String LOGOUT_URL;
    public static String UPHOLD_CURRENCY_LIST;

    public static void initialize(boolean useSandbox) {
        if (!useSandbox) {
            CLIENT_BASE_URL = "https://api.uphold.com/";
            INITIAL_URL = "https://uphold.com/authorize/"+ CLIENT_ID +"?scope=accounts:read%%20cards:read%%20cards:write%%20transactions:deposit%%20transactions:read%%20transactions:transfer:application%%20transactions:transfer:others%%20transactions:transfer:self%%20transactions:withdraw%%20transactions:commit:otp%%20user:read&state=%s";
            CARD_URL_BASE = "https://uphold.com/dashboard/cards/%s/add";
            TRANSACTION_URL = "https://uphold.com/reserve/transactions/%s";
            LOGOUT_URL = "https://wallet.uphold.com/dashboard/more";
            UPHOLD_CURRENCY_LIST = "https://api.uphold.com/v0/assets";
        } else {
            CLIENT_BASE_URL = "https://api-sandbox.uphold.com/";

            INITIAL_URL = "https://sandbox.uphold.com/authorize/"+ CLIENT_ID +"?scope=accounts:read%%20cards:read%%20cards:write%%20transactions:deposit%%20transactions:read%%20transactions:transfer:application%%20transactions:transfer:others%%20transactions:transfer:self%%20transactions:withdraw%%20transactions:commit:otp%%20user:read&state=%s";
            CARD_URL_BASE = "https://sandbox.uphold.com/dashboard/cards/%s/add";
            TRANSACTION_URL = "https://sandbox.uphold.com/reserve/transactions/%s";
            LOGOUT_URL = "https://sandbox.uphold.com/dashboard/more";

            UPHOLD_CURRENCY_LIST = "https://api-sandbox.uphold.com/v0/assets";
        }
    }
    public static boolean hasValidCredentials() {
        return !CLIENT_ID.isEmpty() && !CLIENT_ID.contains("CLIENT_ID");
    }

}
