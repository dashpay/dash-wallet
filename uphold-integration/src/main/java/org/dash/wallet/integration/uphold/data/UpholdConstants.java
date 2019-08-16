package org.dash.wallet.integration.uphold.data;

public class UpholdConstants {

    public static final String CLIENT_ID = "dfb85d44118d6ca2b3e070d434da6e9102a3c7d9";
    public static final String CLIENT_SECRET = "fdb513ff0dd2672a23875816d31354076fc5372e";
    public static final String CLIENT_BASE_URL = "https://api-sandbox.uphold.com/";

    public static final String INITIAL_URL = "https://sandbox.uphold.com/authorize/dfb85d44118d6ca2b3e070d434da6e9102a3c7d9?scope=accounts:read%%20cards:read%%20cards:write%%20transactions:deposit%%20transactions:read%%20transactions:transfer:application%%20transactions:transfer:others%%20transactions:transfer:self%%20transactions:withdraw%%20transactions:commit:otp%%20user:read&state=%s";
    public static final String CARD_URL_BASE = "https://sandbox.uphold.com/dashboard/cards/%s/add";
    public static final String TRANSACTION_URL = "https://sandbox.uphold.com/reserve/transactions/%s";
    public static final String LOGOUT_URL = "https://sandbox.uphold.com/dashboard";

}
