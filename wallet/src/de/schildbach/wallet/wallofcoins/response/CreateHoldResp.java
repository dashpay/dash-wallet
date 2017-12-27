package de.schildbach.wallet.wallofcoins.response;

import java.util.List;

public class CreateHoldResp {


    /**
     * id : 5e179adf417275ef3e138d73f1fa39aa
     * expirationTime : 2017-08-08T17:23:01.440Z
     * discoveryInput : 5cb0c411f83f7c2fdb5a4af909bdb690
     * holds : [{"amount":"53.23435843","currentPrice":"9.43","status":""}]
     * token : ZGV2aWNlOjM5NjoxNTAyMjIzNjAxfGIzNWZmOGYxOTlmYjRkNTM2YTdkZWNmZjJmNjY0MzNjN2M2NWZhOWQ=
     * tokenExpiresAt : 2017-08-08T20:20:01.399Z
     * __PURCHASE_CODE : AARXX
     */

    public String id;
    public String expirationTime;
    public String discoveryInput;
    public String token;
    public String tokenExpiresAt;
    public String __PURCHASE_CODE;
    public List<HoldsBean> holds;

    public static class HoldsBean {
        /**
         * amount : 53.23435843
         * currentPrice : 9.43
         * status :
         */

        public String amount;
        public String currentPrice;
        public String status;
    }
}
