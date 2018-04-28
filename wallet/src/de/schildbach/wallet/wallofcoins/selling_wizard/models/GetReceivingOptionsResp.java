package de.schildbach.wallet.wallofcoins.selling_wizard.models;

import com.google.gson.annotations.SerializedName;

import java.util.List;

/**
 * Created by  on 04-Apr-18.
 */

public class GetReceivingOptionsResp {

    /**
     * id : 19
     * name : Axis Bank
     * url : https://www.axisbank.com/
     * logo : https://woc.reference.genitrust.com/static/logos/logo_in_Axis%20Bank.png
     * logoHq : https://woc.reference.genitrust.com/static/logos/logo_in_Axis%20Bank%402x.png
     * icon : https://woc.reference.genitrust.com/static/logos/icon_in_Axis%20Bank.png
     * iconHq : https://woc.reference.genitrust.com/static/logos/icon_in_Axis%20Bank%402x.png
     * country : in
     * payFields : {"payFields":[{"name":"birthCountry","label":"Country of Birth","parse":"","displaySort":2,"paymentDestination_id":17,"id":7},{"name":"pickupState","label":"Pick-up State","parse":"","displaySort":0.5,"paymentDestination_id":17,"id":6},{"name":"lastName","label":"Last Name","parse":"","displaySort":1,"paymentDestination_id":17,"id":5},{"name":"firstName","label":"First Name","parse":"","displaySort":0,"paymentDestination_id":17,"id":4}],"confirmFields":[{"name":"receiveCode","label":"Receive Code (Confirm)","parse":"","displaySort":0,"paymentDestination_id":17,"id":3}],"trigger":"Check here if this is a biller account (Business)","dynamicFields":[{"name":"accountNumber","label":"Customer Account #","parse":"","displaySort":2,"paymentDestination_id":17,"id":4},{"name":"receiveCode","label":"Receive Code","parse":"","displaySort":1.5,"paymentDestination_id":17,"id":3},{"name":"businessCity","label":"Business City/State","parse":"","displaySort":1,"paymentDestination_id":17,"id":2},{"name":"businessName","label":"Business Name","parse":"","displaySort":0,"paymentDestination_id":17,"id":1}]}
     */

    public int id;
    public String name;
    public String url;
    public String logo;
    public String logoHq;
    public String icon;
    public String iconHq;
    public String country;
    public de.schildbach.wallet.wallofcoins.response.GetReceivingOptionsResp.PayFieldsBeanX payFields;


    public static class PayFieldsBeanX {
        /**
         * payFields : [{"name":"birthCountry","label":"Country of Birth","parse":"","displaySort":2,"paymentDestination_id":17,"id":7},{"name":"pickupState","label":"Pick-up State","parse":"","displaySort":0.5,"paymentDestination_id":17,"id":6},{"name":"lastName","label":"Last Name","parse":"","displaySort":1,"paymentDestination_id":17,"id":5},{"name":"firstName","label":"First Name","parse":"","displaySort":0,"paymentDestination_id":17,"id":4}]
         * confirmFields : [{"name":"receiveCode","label":"Receive Code (Confirm)","parse":"","displaySort":0,"paymentDestination_id":17,"id":3}]
         * trigger : Check here if this is a biller account (Business)
         * dynamicFields : [{"name":"accountNumber","label":"Customer Account #","parse":"","displaySort":2,"paymentDestination_id":17,"id":4},{"name":"receiveCode","label":"Receive Code","parse":"","displaySort":1.5,"paymentDestination_id":17,"id":3},{"name":"businessCity","label":"Business City/State","parse":"","displaySort":1,"paymentDestination_id":17,"id":2},{"name":"businessName","label":"Business Name","parse":"","displaySort":0,"paymentDestination_id":17,"id":1}]
         */


        public String trigger;
        @SerializedName("payFields")
        public List<de.schildbach.wallet.wallofcoins.response.GetReceivingOptionsResp.PayFieldsBeanX.PayFieldsBean> payFieldsX;
        public List<de.schildbach.wallet.wallofcoins.response.GetReceivingOptionsResp.PayFieldsBeanX.PayFieldsBean> confirmFields;
        public List<de.schildbach.wallet.wallofcoins.response.GetReceivingOptionsResp.PayFieldsBeanX.PayFieldsBean> dynamicFields;
        public Boolean payFieldsB;

        public static class PayFieldsBean {
            /**
             * name : birthCountry
             * label : Country of Birth
             * parse :
             * displaySort : 2
             * paymentDestination_id : 17
             * id : 7
             */

            @SerializedName("name")
            public String nameX;
            public String label;
            public String parse;
            public float displaySort;
            public int paymentDestination_id;
            @SerializedName("id")
            public int idX;


            @Override
            public String toString() {
                return "PayFieldsBean{" +
                        "nameX='" + nameX + '\'' +
                        ", label='" + label + '\'' +
                        ", parse='" + parse + '\'' +
                        ", displaySort=" + displaySort +
                        ", paymentDestination_id=" + paymentDestination_id +
                        ", idX=" + idX +
                        '}';
            }
        }

        @Override
        public String toString() {
            return "PayFieldsBeanX{" +
                    "trigger='" + trigger + '\'' +
                    ", payFieldsX=" + payFieldsX +
                    ", confirmFields=" + confirmFields +
                    ", dynamicFields=" + dynamicFields +
                    '}';
        }
    }

    @Override
    public String toString() {
        return "GetReceivingOptionsResp{" +
                "id=" + id +
                ", name='" + name + '\'' +
                ", url='" + url + '\'' +
                ", logo='" + logo + '\'' +
                ", logoHq='" + logoHq + '\'' +
                ", icon='" + icon + '\'' +
                ", iconHq='" + iconHq + '\'' +
                ", country='" + country + '\'' +
                ", payFields=" + payFields +
                '}';
    }
}
