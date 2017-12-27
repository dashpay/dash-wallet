package de.schildbach.wallet.wallofcoins.response;

public class ConfirmDepositResp {

    /**
     * id : 85243
     * total : 9.62314870
     * payment : 3344.4500006610
     * paymentDue : 2017-09-04T16:04:21.290Z
     * bankName : Bank of America
     * nameOnAccount : Gerald Verzosa
     * account : 7475844139
     * status : WD
     * nearestBranch : {"city":"Sarasota","state":"FL","name":"Bank of America","phone":"(941) 952-2868","address":"1605 Main St, #501"}
     * bankUrl : http://bankofamerica.com
     * bankLogo : https://wallofcoins-static.s3.amazonaws.com/logos/logo_11.png
     * bankIcon : https://wallofcoins-static.s3.amazonaws.com/logos/icon_11.png
     * bankIconHq : https://wallofcoins-static.s3.amazonaws.com/logos/icon_11%402x.png
     * privateId : 05dbf8a622641ce60cb7b26543e7a9908209034d
     */

    private int id;
    private String total;
    private String payment;
    private String paymentDue;
    private String bankName;
    private String nameOnAccount;
    private String account;
    private String status;
    private NearestBranchBean nearestBranch;
    private String bankUrl;
    private String bankLogo;
    private String bankIcon;
    private String bankIconHq;
    private String privateId;

    public int getId() {
        return id;
    }

    public void setId(int id) {
        this.id = id;
    }

    public String getTotal() {
        return total;
    }

    public void setTotal(String total) {
        this.total = total;
    }

    public String getPayment() {
        return payment;
    }

    public void setPayment(String payment) {
        this.payment = payment;
    }

    public String getPaymentDue() {
        return paymentDue;
    }

    public void setPaymentDue(String paymentDue) {
        this.paymentDue = paymentDue;
    }

    public String getBankName() {
        return bankName;
    }

    public void setBankName(String bankName) {
        this.bankName = bankName;
    }

    public String getNameOnAccount() {
        return nameOnAccount;
    }

    public void setNameOnAccount(String nameOnAccount) {
        this.nameOnAccount = nameOnAccount;
    }

    public String getAccount() {
        return account;
    }

    public void setAccount(String account) {
        this.account = account;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public NearestBranchBean getNearestBranch() {
        return nearestBranch;
    }

    public void setNearestBranch(NearestBranchBean nearestBranch) {
        this.nearestBranch = nearestBranch;
    }

    public String getBankUrl() {
        return bankUrl;
    }

    public void setBankUrl(String bankUrl) {
        this.bankUrl = bankUrl;
    }

    public String getBankLogo() {
        return bankLogo;
    }

    public void setBankLogo(String bankLogo) {
        this.bankLogo = bankLogo;
    }

    public String getBankIcon() {
        return bankIcon;
    }

    public void setBankIcon(String bankIcon) {
        this.bankIcon = bankIcon;
    }

    public String getBankIconHq() {
        return bankIconHq;
    }

    public void setBankIconHq(String bankIconHq) {
        this.bankIconHq = bankIconHq;
    }

    public String getPrivateId() {
        return privateId;
    }

    public void setPrivateId(String privateId) {
        this.privateId = privateId;
    }

    public static class NearestBranchBean {
        /**
         * city : Sarasota
         * state : FL
         * name : Bank of America
         * phone : (941) 952-2868
         * address : 1605 Main St, #501
         */

        private String city;
        private String state;
        private String name;
        private String phone;
        private String address;

        public String getCity() {
            return city;
        }

        public void setCity(String city) {
            this.city = city;
        }

        public String getState() {
            return state;
        }

        public void setState(String state) {
            this.state = state;
        }

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public String getPhone() {
            return phone;
        }

        public void setPhone(String phone) {
            this.phone = phone;
        }

        public String getAddress() {
            return address;
        }

        public void setAddress(String address) {
            this.address = address;
        }
    }
}
