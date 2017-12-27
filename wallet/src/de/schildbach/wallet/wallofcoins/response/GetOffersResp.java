package de.schildbach.wallet.wallofcoins.response;

import java.text.NumberFormat;
import java.text.ParseException;
import java.util.List;
import java.util.Locale;

public class GetOffersResp {

    /**
     * singleDeposit : [{"id":"eyJ1c2QiOiAiNTAxLjAwIiwgImVhIjogdHJ1ZSwgImFkIjogMTAsICJkaSI6ICIyOTQ4ZTMwMWYyZGJjMmNkNTJjNzI4NGUyZWJiZDZiZCJ9fHxTaW5nbGVEZXBvc2l0T2ZmZXJ8fDA3ZGQzOGVhY2NlYTlhYmRiY2E1ZDNlM2Q2OWY4Mjky","deposit":{"currency":"USD","amount":"501.00"},"crypto":"DASH","amount":{"DASH":"51.562","dots":"51,562,544.25","bits":"51,562,544.25","BTC":"51.562"},"discoveryId":"2948e301f2dbc2cd52c7284e2ebbd6bd","distance":0,"address":"","state":"","bankName":"MoneyGram","bankLogo":"/media/logos/logo_us_MoneyGram.png","bankLogoHq":"/media/logos/logo_us_MoneyGram%402x.png","bankIcon":"/media/logos/icon_us_MoneyGram.png","bankIconHq":"/media/logos/icon_us_MoneyGram%402x.png","bankLocationUrl":"https://secure.moneygram.com/locations","city":""},{"id":"eyJ1c2QiOiAiNTAyLjAwIiwgImVhIjogdHJ1ZSwgImFkIjogMTEsICJkaSI6ICIyOTQ4ZTMwMWYyZGJjMmNkNTJjNzI4NGUyZWJiZDZiZCJ9fHxTaW5nbGVEZXBvc2l0T2ZmZXJ8fGU1N2JlOWUxZTFmNTZmY2VjYzBiMjkyYzI0ZjdjM2Zj","deposit":{"currency":"USD","amount":"502.00"},"crypto":"DASH","amount":{"DASH":"32.480","dots":"32,480,900.05","bits":"32,480,900.05","BTC":"32.480"},"discoveryId":"2948e301f2dbc2cd52c7284e2ebbd6bd","distance":0.9639473391774286,"address":"240 N Washington Blvd, #100","state":"FL","bankName":"Chase","bankLogo":"/media/logos/logo_us_Chase.png","bankLogoHq":"/media/logos/logo_us_Chase%402x.png","bankIcon":"/media/logos/icon_us_Chase.png","bankIconHq":"/media/logos/icon_us_Chase%402x.png","bankLocationUrl":null,"city":"Sarasota"},{"id":"eyJ1c2QiOiAiNTAwLjAwIiwgImVhIjogdHJ1ZSwgImFkIjogMzgsICJkaSI6ICIyOTQ4ZTMwMWYyZGJjMmNkNTJjNzI4NGUyZWJiZDZiZCJ9fHxTaW5nbGVEZXBvc2l0T2ZmZXJ8fDMwMzViOTI0ODliNTI2ZmI1MWU5NWJhZWE5YmM5NGRl","deposit":{"currency":"USD","amount":"500.00"},"crypto":"DASH","amount":{"DASH":"5.508","dots":"5,508,014.94","bits":"5,508,014.94","BTC":"5.508"},"discoveryId":"2948e301f2dbc2cd52c7284e2ebbd6bd","distance":0.495030456581863,"address":"1605 Main St, #501","state":"FL","bankName":"Bank of America","bankLogo":"/media/logos/logo_us_Bank%20of%20America.png","bankLogoHq":"/media/logos/logo_us_Bank%20of%20America%402x.png","bankIcon":"/media/logos/icon_us_Bank%20of%20America.png","bankIconHq":"/media/logos/icon_us_Bank%20of%20America%402x.png","bankLocationUrl":null,"city":"Sarasota"}]
     * doubleDeposit : [{"id":"eyJkaSI6ICIyOTQ4ZTMwMWYyZGJjMmNkNTJjNzI4NGUyZWJiZDZiZCIsICJhZDIiOiAzMywgImFkMSI6IDQxfXx8RG91YmxlRGVwb3NpdE9mZmVyfHxlOGU2NzBmMTM2MmE5ZjQ5MGQzZGE0ZWI3MjIxNWE4MA==","firstOffer":{"deposit":{"currency":"USD","amount":"490.00"},"crypto":"DASH","amount":{"DASH":"47.547","dots":"47,547,115.08","bits":"47,547,115.08","BTC":"47.547"},"discoveryId":"2948e301f2dbc2cd52c7284e2ebbd6bd","distance":0.9639473391774286,"address":"240 N Washington Blvd, #100","state":"FL","bankName":"Chase","bankLogo":"/media/logos/logo_us_Chase.png","bankLogoHq":"/media/logos/logo_us_Chase%402x.png","bankIcon":"/media/logos/icon_us_Chase.png","bankIconHq":"/media/logos/icon_us_Chase%402x.png","bankLocationUrl":null,"city":"Sarasota"},"secondOffer":{"deposit":{"currency":"USD","amount":"10.00"},"crypto":"DASH","amount":{"DASH":"0.088","dots":"88,491.58","bits":"88,491.58","BTC":"0.088"},"discoveryId":"2948e301f2dbc2cd52c7284e2ebbd6bd","distance":0.9639473391774286,"address":"240 N Washington Blvd, #100","state":"FL","bankName":"Chase","bankLogo":"/media/logos/logo_us_Chase.png","bankLogoHq":"/media/logos/logo_us_Chase%402x.png","bankIcon":"/media/logos/icon_us_Chase.png","bankIconHq":"/media/logos/icon_us_Chase%402x.png","bankLocationUrl":null,"city":"Sarasota"},"totalAmount":{"bits":"47,635,606.67","BTC":"47.635"},"totalDeposit":{"currency":"USD","amount":"500.00"}}]
     * multipleBanks : []
     * isExtendedSearch : false
     * incremented : true
     */

    public boolean isExtendedSearch;
    public boolean incremented;
    public List<SingleDepositBean> singleDeposit;
    public List<DoubleDepositBean> doubleDeposit;
    public List<DoubleDepositBean> multipleBanks;

    public static class SingleDepositBean {
        /**
         * id : eyJ1c2QiOiAiNTAxLjAwIiwgImVhIjogdHJ1ZSwgImFkIjogMTAsICJkaSI6ICIyOTQ4ZTMwMWYyZGJjMmNkNTJjNzI4NGUyZWJiZDZiZCJ9fHxTaW5nbGVEZXBvc2l0T2ZmZXJ8fDA3ZGQzOGVhY2NlYTlhYmRiY2E1ZDNlM2Q2OWY4Mjky
         * deposit : {"currency":"USD","amount":"501.00"}
         * crypto : DASH
         * amount : {"DASH":"51.562","dots":"51,562,544.25","bits":"51,562,544.25","BTC":"51.562"}
         * discoveryId : 2948e301f2dbc2cd52c7284e2ebbd6bd
         * distance : 0
         * address :
         * state :
         * bankName : MoneyGram
         * bankLogo : /media/logos/logo_us_MoneyGram.png
         * bankLogoHq : /media/logos/logo_us_MoneyGram%402x.png
         * bankIcon : /media/logos/icon_us_MoneyGram.png
         * bankIconHq : /media/logos/icon_us_MoneyGram%402x.png
         * bankLocationUrl : https://secure.moneygram.com/locations
         * city :
         */

        public String id;
        public DepositBean deposit;
        public String crypto;
        public AmountBean amount;
        public String discoveryId;
        public double distance;
        public String address;
        public String state;
        public String bankName;
        public String bankLogo;
        public String bankLogoHq;
        public String bankIcon;
        public String bankIconHq;
        public String bankLocationUrl;
        public String city;


    }

    public static class DoubleDepositBean {
        /**
         * id : eyJkaSI6ICIyOTQ4ZTMwMWYyZGJjMmNkNTJjNzI4NGUyZWJiZDZiZCIsICJhZDIiOiAzMywgImFkMSI6IDQxfXx8RG91YmxlRGVwb3NpdE9mZmVyfHxlOGU2NzBmMTM2MmE5ZjQ5MGQzZGE0ZWI3MjIxNWE4MA==
         * firstOffer : {"deposit":{"currency":"USD","amount":"490.00"},"crypto":"DASH","amount":{"DASH":"47.547","dots":"47,547,115.08","bits":"47,547,115.08","BTC":"47.547"},"discoveryId":"2948e301f2dbc2cd52c7284e2ebbd6bd","distance":0.9639473391774286,"address":"240 N Washington Blvd, #100","state":"FL","bankName":"Chase","bankLogo":"/media/logos/logo_us_Chase.png","bankLogoHq":"/media/logos/logo_us_Chase%402x.png","bankIcon":"/media/logos/icon_us_Chase.png","bankIconHq":"/media/logos/icon_us_Chase%402x.png","bankLocationUrl":null,"city":"Sarasota"}
         * secondOffer : {"deposit":{"currency":"USD","amount":"10.00"},"crypto":"DASH","amount":{"DASH":"0.088","dots":"88,491.58","bits":"88,491.58","BTC":"0.088"},"discoveryId":"2948e301f2dbc2cd52c7284e2ebbd6bd","distance":0.9639473391774286,"address":"240 N Washington Blvd, #100","state":"FL","bankName":"Chase","bankLogo":"/media/logos/logo_us_Chase.png","bankLogoHq":"/media/logos/logo_us_Chase%402x.png","bankIcon":"/media/logos/icon_us_Chase.png","bankIconHq":"/media/logos/icon_us_Chase%402x.png","bankLocationUrl":null,"city":"Sarasota"}
         * totalAmount : {"bits":"47,635,606.67","BTC":"47.635"}
         * totalDeposit : {"currency":"USD","amount":"500.00"}
         */

        public String id;
        public FirstOfferBean firstOffer;
        public SecondOfferBean secondOffer;
        public AmountBean totalAmount;
        public DepositBean totalDeposit;

        public static class FirstOfferBean {
            /**
             * deposit : {"currency":"USD","amount":"490.00"}
             * crypto : DASH
             * amount : {"DASH":"47.547","dots":"47,547,115.08","bits":"47,547,115.08","BTC":"47.547"}
             * discoveryId : 2948e301f2dbc2cd52c7284e2ebbd6bd
             * distance : 0.9639473391774286
             * address : 240 N Washington Blvd, #100
             * state : FL
             * bankName : Chase
             * bankLogo : /media/logos/logo_us_Chase.png
             * bankLogoHq : /media/logos/logo_us_Chase%402x.png
             * bankIcon : /media/logos/icon_us_Chase.png
             * bankIconHq : /media/logos/icon_us_Chase%402x.png
             * bankLocationUrl : null
             * city : Sarasota
             */

            public DepositBean deposit;
            public String crypto;
            public AmountBean amount;
            public String discoveryId;
            public double distance;
            public String address;
            public String state;
            public String bankName;
            public String bankLogo;
            public String bankLogoHq;
            public String bankIcon;
            public String bankIconHq;
            public String bankLocationUrl;
            public String city;
        }

        public static class SecondOfferBean {
            /**
             * deposit : {"currency":"USD","amount":"10.00"}
             * crypto : DASH
             * amount : {"DASH":"0.088","dots":"88,491.58","bits":"88,491.58","BTC":"0.088"}
             * discoveryId : 2948e301f2dbc2cd52c7284e2ebbd6bd
             * distance : 0.9639473391774286
             * address : 240 N Washington Blvd, #100
             * state : FL
             * bankName : Chase
             * bankLogo : /media/logos/logo_us_Chase.png
             * bankLogoHq : /media/logos/logo_us_Chase%402x.png
             * bankIcon : /media/logos/icon_us_Chase.png
             * bankIconHq : /media/logos/icon_us_Chase%402x.png
             * bankLocationUrl : null
             * city : Sarasota
             */

            public DepositBean deposit;
            public String crypto;
            public AmountBean amount;
            public String discoveryId;
            public double distance;
            public String address;
            public String state;
            public String bankName;
            public String bankLogo;
            public String bankLogoHq;
            public String bankIcon;
            public String bankIconHq;
            public String bankLocationUrl;
            public String city;
        }

        public String sumAmounts(String... args) {

            double amount = 0;

            for (String s : args) {
                try {
                    amount += NumberFormat.getNumberInstance(Locale.getDefault()).parse(s).doubleValue();
                } catch (ParseException e) {
                    e.printStackTrace();
                }
            }
            return NumberFormat.getNumberInstance(Locale.getDefault()).format(amount);
        }
    }

    public static class DepositBean {
        /**
         * currency : USD
         * amount : 501.00
         */

        public String currency;
        public String amount;
    }

    public static class AmountBean {
        /**
         * DASH : 51.562
         * dots : 51,562,544.25
         * bits : 51,562,544.25
         * BTC : 51.562
         */

        public String DASH;
        public String dots;
        public String bits;
        public String BTC;
    }
}
