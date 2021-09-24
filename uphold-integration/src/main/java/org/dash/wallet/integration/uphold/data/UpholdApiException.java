package org.dash.wallet.integration.uphold.data;


import android.annotation.SuppressLint;
import android.content.Context;

import org.dash.wallet.integration.uphold.R;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.text.DateFormat;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;

import retrofit2.Response;

public class UpholdApiException extends Exception {

    public static final String IDENTITY_ERROR_KEY = "identity";
    public static final String TOKEN_ERROR_KEY = "token";
    public static final String LOCKED_FUNDS_ERROR = "sufficient_unlocked_funds";
    public static final String AVAILABLE_AT_KEY = "availableAt";
    public static final String FORBIDDEN_ERROR = "forbidden";
    public static final String VALIDATION_FAILED = "validation_failed";
    private static final DateFormat formatter = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.DEFAULT);


    private int httpStatusCode;

    private JSONObject errorBody;

    public UpholdApiException(Response response) {
        this.httpStatusCode = response.code();
        if (response.errorBody() != null) {
            try {
                errorBody = new JSONObject(response.errorBody().string());
            } catch (Exception e) {
                errorBody = null;
            }
        }
    }

    public UpholdApiException(int httpStatusCode) {
        this.httpStatusCode = httpStatusCode;
    }

    public String getErrorCode() {
        if (errorBody != null) {
            try {
                return errorBody.getString("code");
            } catch (JSONException e) {
                // swallow
            }
        }
        return null;
    }

    public JSONObject getErrors() {
        if (errorBody != null) {
            try {
                return errorBody.getJSONObject("errors");
            } catch (JSONException e) {
                // swallow
            }
        }
        return null;
    }

    public JSONObject getErrorsWithException() throws JSONException {
        if (errorBody != null) {
            return errorBody.getJSONObject("errors");
        }
        throw new JSONException("There is no error object");
    }


    private JSONObject getError(String errorKey) {
        JSONObject errors = getErrors();
        if (errors != null) {
            try {
                return errors.getJSONObject(errorKey);
            } catch (JSONException e) {
                // swallow
            }
        }
        return null;
    }

    private String getErrorField(String errorKey, String errorField) {
        JSONObject error = getError(errorKey);
        if (error != null) {
            try {
                return error.getString(errorField);
            } catch (JSONException e) {
                // swallow
            }
        }
        return null;
    }

    public String getErrorCode(String errorKey) {
        return getErrorField(errorKey, "code");
    }

    public String getErrorMessage(String errorKey) {
        return getErrorField(errorKey, "message");
    }

    public boolean hasError(String errorField) {
        JSONObject errors = getErrors();
        return (errors != null) && errors.has(errorField);
    }

    public boolean isTokenError() {
        JSONObject errors = getErrors();
        return (errors != null) && errors.has(TOKEN_ERROR_KEY);
    }

    public boolean isIdentityError() {
        JSONObject errors = getErrors();
        return (errors != null) && errors.has(IDENTITY_ERROR_KEY);
    }

    /**
     *
     * {
     *      "args": {
     *          "availableAt":"2020-04-17T22:42:00.151Z",
     *          "missing":"1.03430005",
     *          "currency":"DASH"
     *      },
     *      "code":"sufficient_unlocked_funds",
     *      "message":"You will have sufficient funds by 2020-04-17T22:42:00.151Z"
     * }
     *
     * {
     *      "code":"validation_failed",
     *      "errors":{
     *          "denomination":
     *              {"code": "validation_failed",
     *              "errors":{
     *                  "amount":[
     *                      {
     *                          "code":"sufficient_unlocked_funds","
     *                          "message":"You will have sufficient funds by 2021-11-24T03:38:58.663Z",
     *                          "args":{
     *                              "availableAt":"2021-11-24T03:38:58.663Z",
     *                              "currency":"DASH",
     *                              "missing":"6.21186161",
     *                              "restrictions":["ach-deposit-settlement","ach-deposit-cooldown"]
     *                           }
     *                       }
     *                   ]
     *              }
     *          }
     *      }
     * }
     *
     * {
     *      "code":"validation_failed",
     *      "errors":
     *      {"user":[
     *          {
     *              "code":"restricted_by_authentication_method_reset",
     *              "message":"The user is restricted because authentication method has been changed recently",
     *              "args":{
     *                  "recentAuthenticationRestrictionEndDate":"2021-09-21T16:02:59.605Z"
     *              }
     *          }
     *      ]
     *      }
     * }
     */

    private boolean isLockedFundsError() {
        JSONObject errors = getErrors();
        return (errors != null) && errors.has(LOCKED_FUNDS_ERROR);
    }

    private boolean isValidationFailed(HashMap<String, String> arguments) {
        try {
            JSONObject errors = getErrorsWithException();
            if (errors.has("denomination")) {
                JSONObject denomination = (JSONObject)errors.get("denomination");
                String code = denomination.getString("code");
                if (code.equals(VALIDATION_FAILED)) {
                    JSONObject errorArray = (JSONObject) denomination.get("errors");
                    JSONArray amount = errorArray.getJSONArray("amount");
                    JSONObject firstAmount = (JSONObject) amount.get(0);
                    if (firstAmount.has("code") && firstAmount.get("code").equals("sufficient_unlocked_funds")) {
                        JSONObject args = (JSONObject) firstAmount.get("args");
                        arguments.put("code", "sufficient_unlocked_funds");
                        Date date = convertISO8601Date( args.getString("availableAt"));
                        arguments.put("availableAt", formatter.format(date));
                        arguments.put("missing", args.getString("missing"));
                        arguments.put("currency", args.getString("currency"));
                        return true;
                    } else if (firstAmount.has("code") && firstAmount.get("code").equals("sufficient_funds")) {
                        // JSONObject args = (JSONObject) firstAmount.get("args");
                        arguments.put("code", "sufficient_funds");
                        return true;
                    }
                }
            } else if (errors.has("destination")) {
                JSONObject destination = (JSONObject)errors.get("destination");
                String code = destination.getString("code");
                if (code.equals(VALIDATION_FAILED)) {
                    JSONObject errorArray = (JSONObject) destination.get("errors");
                    JSONArray amount = errorArray.getJSONArray("amount");
                    JSONObject firstAmount = (JSONObject) amount.get(0);
                    if (firstAmount.has("code") && firstAmount.get("code").equals("less_than_or_equal_to")) {
                        JSONObject args = (JSONObject) firstAmount.get("args");
                        arguments.put("code", "less_than_or_equal_to");
                        arguments.put("threshold", args.getString("threshold"));
                        return true;
                    }
                }
            } else if (errors.has("beneficiary")) {
                // {"code":"validation_failed","errors":{"beneficiary":[{"code":"required","message":"This value is required"}],"purpose":[{"code":"required","message":"This value is required"}]}}
                JSONArray beneficiaryArray = errors.getJSONArray("beneficiary");
                JSONObject firstCode = (JSONObject) beneficiaryArray.get(0);

                if (firstCode.has("code") && firstCode.get("code").equals("invalid_beneficiary")) {
                    arguments.put("code", "invalid_beneficiary");
                    return true;
                } else if (firstCode.has("code") && firstCode.get("code").equals("required")) {
                    arguments.put("code", "required");
                    return true;
                }

            } else if (errors.has("user")) {
                JSONArray userArray = (JSONArray) errors.getJSONArray("user");
                JSONObject user = (JSONObject) userArray.get(0);
                if (user.has("code")) {
                    if (user.get("code").equals("password_reset_restriction")) {
                        if (user.has("args")) {
                            JSONObject args = (JSONObject) user.get("args");
                            Date date = convertISO8601Date(args.getString("recentPasswordRestrictionEndDate"));
                            arguments.put("code", "password_reset_restriction");
                            arguments.put("recentPasswordRestrictionEndDate", formatter.format(date));
                            return true;
                        }
                    } else if (user.get("code").equals("restricted_by_authentication_method_reset")) {
                        /*
                            {
                                "user":[
                                    {
                                        "code":"restricted_by_authentication_method_reset",
                                        "message":"The user is restricted because authentication method has been changed recently",
                                        "args":{"recentAuthenticationRestrictionEndDate":"2021-09-21T16:02:59.605Z"}
                                    }
                                ]
                            }
                        */
                        if (user.has("args")) {
                            JSONObject args = (JSONObject) user.get("args");
                            Date date = convertISO8601Date(args.getString("recentPasswordRestrictionEndDate"));
                            arguments.put("code", "restricted_by_authentication_method_reset");
                            arguments.put("recentAuthenticationRestrictionEndDate", formatter.format(date));
                            return true;
                        }
                    }
                }
            }
            return false;
        } catch (JSONException x) {
            return false;
        }
    }

    @SuppressLint("SimpleDateFormat")
    private Date convertISO8601Date(String date) {
        // in android 8 and above, we could use this: Date.from(Instant.parse(date))
        try {
            DateFormat df = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'");
            return df.parse(date);
        } catch (ParseException x) {
            return null;
        }
    }

    /**
     *
     * {
     *   "code": "validation_failed",
     *   "errors": {
     *     "denomination": {
     *       "code": "validation_failed",
     *       "errors": {
     *         "amount": [
     *           {
     *             "code": "sufficient_funds",
     *             "message": "Not enough funds for the specified amount"
     *           }
     *         ]
     *       }
     *     }
     *   }
     * }
     */

    /*
     * {
     *   "code": "validation_failed",
     *   "errors": {
     *     "destination": {
     *       "code": "validation_failed",
     *       "errors": {
     *         "amount": [
     *           {
     *             "code": "less_than_or_equal_to",
     *             "message": "This value should be less than or equal to 25",
     *             "args": {
     *               "threshold": "25"
     *             }
     *           }
     *         ]
     *       }
     *     }
     *   }
     * }
     */

    // {
    //      "code":"validation_failed",
    //      "errors":{
    //          "user":[
    //              {
    //                  "code":"password_reset_restriction",
    //                  "message":"The user password has been changed in the last 1 day",
    //                  "args":{
    //                      "recentPasswordRestrictionEndDate":"2021-09-16T17:19:34.996Z",
    //                      "threshold":1,
    //                      "unit":"day"
    //                  }
    //               }
    //          ]
    //      }
    //  }

    private boolean isPasswordResetRestrictionError(StringBuilder stringBuilder) {
        JSONObject errors = getErrors();
        try {
            if (errors != null && errors.has("user")) {
                JSONArray userArray = (JSONArray) errors.getJSONArray("user");
                JSONObject user = (JSONObject) userArray.get(0);
                if (user.has("code")) {
                    if (user.get("code").equals("password_reset_restriction")) {
                        if (user.has("args")) {
                            JSONObject args = (JSONObject) user.get("args");
                            Date date = convertISO8601Date(args.getString("recentPasswordRestrictionEndDate"));
                            stringBuilder.append(formatter.format(date));
                            return true;
                        }
                    }
                }
            }
        } catch (JSONException x) {
            //swallow
        }
        return false;
    }

    /*

    In both cases, incomplete beneficiary information will be reported in a format similar to this:

    {
      "code": "validation_failed",
      "errors": {
        "beneficiary": {
          "code": "validation_failed",
          "errors": {
            "name": [
              {
                "code": "required",
                "message": "This value is required"
              }
            ]
          }
        }
      }
    }

    Invalid beneficiary information will be reported like this:

    {
      "code": "validation_failed",
      "errors": {
        "beneficiary": {
          "code": "validation_failed",
          "errors": {
            "name": [
              {
                "code": "invalid_beneficiary",
                "message": "The provided beneficiary is invalid"
              }
            ]
          }
        }
      }
    }
     */

    /**
     * 403
     * error: {
     *      "capability":"withdrawals",
     *      "code":"forbidden",
     *      "message":"Quote not allowed due to capability constraints",
     *      "requirements":[],
     *      "restrictions":["user-status-not-valid"]
     *      }
     */

    private boolean isForbiddenError() {
        JSONObject errors = getErrors();
        try {
            if (errors != null && errors.has("code")) {
                if (errors.get("code").equals(FORBIDDEN_ERROR)) {
                    return true;
                }
            }
        } catch (JSONException x) {
            //swallow
        }
        return false;
    }



    private JSONObject getErrorArgs() {
        if (errorBody != null) {
            try {
                return errorBody.getJSONObject("args");
            } catch (JSONException e) {
                // swallow
            }
        }
        return null;
    }

    public String getErrorArg(String errorArgKey) {
        JSONObject errorArgs = getErrorArgs();
        if (errorArgs != null) {
            try {
                return errorArgs.getString(errorArgKey);
            } catch (JSONException e) {
                // swallow
            }
        }
        return null;
    }

    public int getCode() {
        return httpStatusCode;
    }

    public String getDescription(Context context) {
        StringBuilder stringBuilder = new StringBuilder();
        if (isIdentityError()) {
            return context.getString(R.string.uphold_api_error_identity);
        } else if (httpStatusCode == 400) {
            HashMap arguments = new HashMap<String, String>(4);
            if (isValidationFailed(arguments)) {
                switch ((String)arguments.get("code")) {
                    case "sufficient_unlocked_funds":
                        return context.getString(R.string.uphold_api_error_400_locked,
                                arguments.get("missing"),
                                arguments.get("currency"),
                                arguments.get("availableAt"));
                    case "sufficient_funds":
                        return context.getString(R.string.uphold_api_error_400_tx_insufficentfunds);
                    case "less_than_or_equal_to":
                        return context.getString(R.string.uphold_api_error_400_less_than_or_equal_to, arguments.get("threshold"));
                    case "invalid_beneficiary":
                        return context.getString(R.string.uphold_api_error_400_invalid_beneficiary);
                    case "required":
                        return context.getString(R.string.uphold_api_error_400_required);
                    case "password_reset_restriction":
                        return context.getString(R.string.uphold_api_error_400_password_reset, stringBuilder.toString());
                    case "restricted_by_authentication_method_reset":
                        return context.getString(R.string.uphold_api_error_400_authentication_change, stringBuilder.toString());
                    default:
                        return context.getString(R.string.loading_error);
                }
            } else if (isLockedFundsError()) {
                // This may be obsolete
                String availableAt = getErrorArg(UpholdApiException.AVAILABLE_AT_KEY);
                Date date = convertISO8601Date(availableAt);
                stringBuilder.append(formatter.format(date));
                return context.getString(R.string.uphold_api_error_400_description, availableAt);
            } else {
                // undefined
                return context.getString(R.string.loading_error);
            }
        } else if (httpStatusCode == 403) {
            if (isForbiddenError()) {
                return context.getString(R.string.uphold_api_error_403_description);
            } else {
                return context.getString(R.string.loading_error);
            }
        } else {
            return context.getString(R.string.loading_error);
        }
    }

    // based on https://uphold.com/en/developer/api/documentation/#errors
    private String getGenericDescription() {
        switch (httpStatusCode) {
            case 400:
                return "Bad Request – Validation failed";
            case 401:
                return "Unauthorized – Bad credentials";
            case 403:
                return "Forbidden – Access forbidden";
            case 404:
                return "Not Found – Object not found";
            case 409:
                return "Conflict – Entity already exists";
            case 412:
                return "Precondition Failed";
            case 416:
                return "Requested Range Not Satisfiable";
            case 429:
                return "Too Many Requests – Rate limit exceeded";
            default:
                return "Unknown Error";
        }
    }
}
