package org.dash.wallet.integrations.uphold.data;


public class UpholdException extends Exception {

    private int code;
    public UpholdException(String error, String message, int code) {
        super(error +": message: " +message + " code:" + code);
        this.code = code;
    }

    public int getCode() {
        return code;
    }
}
