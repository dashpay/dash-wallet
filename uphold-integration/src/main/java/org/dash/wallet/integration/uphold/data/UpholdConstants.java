package org.dash.wallet.integration.uphold.data;

public class UpholdConstants {

    public static final String CLIENT_BASE_URL = "https://api.uphold.com/";
    public static final String CLIENT_ID = "c184650d0cb44e73d8e5cb2021753a721c41f74a";
    public static final String CLIENT_SECRET = "da72feee8236f7709df6d0c235a8896ad45f2a91";

    public static final String INITIAL_URL = "https://uphold.com/authorize/c184650d0cb44e73d8e5cb2021753a721c41f74a?scope=accounts:read%%20cards:read%%20cards:write%%20transactions:deposit%%20transactions:read%%20transactions:transfer:application%%20transactions:transfer:others%%20transactions:transfer:self%%20transactions:withdraw%%20transactions:commit:otp%%20user:read&state=%s";
    public static final String CARD_URL_BASE = "https://uphold.com/dashboard/cards/%s/add";
    public static final String TRANSACTION_URL = "https://uphold.com/reserve/transactions/%s";

}
