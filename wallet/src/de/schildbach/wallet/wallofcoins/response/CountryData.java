package de.schildbach.wallet.wallofcoins.response;

import com.google.gson.annotations.SerializedName;

import java.util.List;

public class CountryData {


    public List<CountriesBean> countries;

    public static class CountriesBean {
        /**
         * name : United States
         * code : +1
         * currency : USD
         * short : us
         */

        public String name;
        public String code;
        public String currency;
        @SerializedName("short")
        public String shortX;
        public int top;
        public int left;

        @Override
        public String toString() {
            return name + " (" + code + ")";
        }
    }
}
