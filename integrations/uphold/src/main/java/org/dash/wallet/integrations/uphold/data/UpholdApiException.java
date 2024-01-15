package org.dash.wallet.integrations.uphold.data;


import android.annotation.SuppressLint;
import android.content.Context;

import org.dash.wallet.integrations.uphold.R;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.text.DateFormat;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;

import retrofit2.Response;

public class UpholdApiException extends Exception {

    public static final String IDENTITY_ERROR_KEY = "identity";
    public static final String TOKEN_ERROR_KEY = "token";
    public static final String LOCKED_FUNDS_ERROR = "sufficient_unlocked_funds";
    public static final String AVAILABLE_AT_KEY = "availableAt";
    public static final String FORBIDDEN_ERROR = "forbidden";
    public static final String VALIDATION_FAILED = "validation_failed";
    private static final DateFormat formatter = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.DEFAULT);

    private final int httpStatusCode;

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

    // for testing
    public UpholdApiException(String errorResponse, int httpStatusCode) {
        this.httpStatusCode = httpStatusCode;
        try {
            errorBody = new JSONObject(errorResponse);
        } catch (JSONException x) {
            errorBody = null;
        }

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

    private boolean isLockedFundsError() {
        JSONObject errors = getErrors();
        return (errors != null) && errors.has(LOCKED_FUNDS_ERROR);
    }

    public boolean isValidationFailed(HashMap<String, String> arguments) {
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
                        arguments.put("code", "sufficient_funds");
                        return true;
                    } else if (firstAmount.has("code") && firstAmount.get("code").equals("less_than_or_equal_to")) {
                        JSONObject args = (JSONObject) firstAmount.get("args");
                        arguments.put("code", "less_than_or_equal_to");
                        arguments.put("threshold", args.getString("threshold"));
                        return true;
                    } else if (firstAmount.has("code") && firstAmount.get("code").equals("greater_than_or_equal_to")) {
                        JSONObject args = (JSONObject) firstAmount.get("args");
                        arguments.put("code", "greater_than_or_equal_to");
                        arguments.put("threshold", args.getString("threshold"));
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
                        if (user.has("args")) {
                            JSONObject args = (JSONObject) user.get("args");
                            Date date = convertISO8601Date(args.getString("recentAuthenticationRestrictionEndDate"));
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

    //  403
    //  {
    //       "capability":"crypto_withdrawals",
    //       "code":"forbidden",
    //       "message":"Quote not allowed due to capability constraints",
    //       "requirements":[],
    //       "restrictions":["user-status-not-valid"]
    //  }

    public boolean isForbiddenError(HashMap<String, String> arguments) {
        JSONObject errors = errorBody;
        try {
            if (errors != null && errors.has("code")) {
                if (errors.get("code").equals(FORBIDDEN_ERROR)) {
                    JSONArray requirements = errors.getJSONArray("requirements");
                    if (requirements.length() != 0) {
                        // get the first requirement only
                        arguments.put("requirements", (String) requirements.get(0));
                    } else {
                        // if there are no requirements, specify null
                        arguments.put("requirements", null);
                    }
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
        HashMap<String, String> arguments = new HashMap<>(4);
        if (isIdentityError()) {
            return context.getString(R.string.uphold_api_error_identity);
        } else if (httpStatusCode == 400) {
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
                    case "greater_than_or_equal_to":
                        return context.getString(R.string.uphold_api_error_400_greater_than_or_equal_to, arguments.get("threshold"));
                    case "invalid_beneficiary":
                        return context.getString(R.string.uphold_api_error_400_invalid_beneficiary);
                    case "required":
                        return context.getString(R.string.uphold_api_error_400_required);
                    case "password_reset_restriction":
                        return context.getString(R.string.uphold_api_error_400_password_reset, arguments.get("recentPasswordRestrictionEndDate"));
                    case "restricted_by_authentication_method_reset":
                        return context.getString(R.string.uphold_api_error_400_authentication_change, arguments.get("recentAuthenticationRestrictionEndDate"));
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
            if (isForbiddenError(arguments)) {
                String requirements = arguments.get("requirements");
                // if requirements == null, then use the generic more info message
                int moreInfoId = R.string.uphold_api_error_403_generic;
                Map<String, Integer> map = ForbiddenError.INSTANCE.getErrorToMessageMap();

                if (requirements != null && map.containsKey(requirements)) {
                    moreInfoId = map.get(requirements);
                }
                return context.getString(R.string.uphold_api_error_403_description, context.getString(moreInfoId));
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
