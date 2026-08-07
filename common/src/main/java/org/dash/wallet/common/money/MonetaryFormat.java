/*
 * Copyright 2014 Andreas Schildbach
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.dash.wallet.common.money;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkState;

import com.google.common.math.LongMath;

import java.math.RoundingMode;
import java.text.DecimalFormatSymbols;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Utility for formatting and parsing coin values to and from human readable form.
 *
 * <p>Self-contained port of {@code org.bitcoinj.utils.MonetaryFormat} (dashj 22.0.3, including
 * the dashj-specific grouping-separator support and DASH currency codes) so that modules
 * depending on {@code common} need no dashj on their classpath. Formatting output and parsing
 * behavior are byte-identical — see the parity unit tests in the wallet module.</p>
 *
 * <p>MonetaryFormat instances are immutable. Invoking a configuration method has no effect on the receiving instance;
 * you must store and use the new instance it returns, instead. Instances are thread safe.</p>
 */
public final class MonetaryFormat {

    /** Standard format for the DASH denomination. */
    public static final MonetaryFormat BTC = new MonetaryFormat().shift(0).minDecimals(2).repeatOptionalDecimals(2, 3);
    /** Standard format for the mDASH denomination. */
    public static final MonetaryFormat MBTC = new MonetaryFormat().shift(3).minDecimals(2).optionalDecimals(2);
    /** Standard format for the µDASH denomination. */
    public static final MonetaryFormat UBTC = new MonetaryFormat().shift(6).minDecimals(0).optionalDecimals(2);
    /** Standard format for fiat amounts. */
    public static final MonetaryFormat FIAT = new MonetaryFormat().shift(0).minDecimals(2).repeatOptionalDecimals(2, 1);
    /** Currency code for base 1 Dash. */
    public static final String CODE_BTC = "DASH";
    /** Currency code for base 1/1000 Dash. */
    public static final String CODE_MBTC = "mDASH";
    /** Currency code for base 1/1000000 Dash. */
    public static final String CODE_UBTC = "µDASH";
    /** Currency symbol for base 1 Dash. */
    public static final String SYMBOL_BTC = "Ð";
    /** Currency symbol for base 1/1000 Dash. */
    public static final String SYMBOL_MBTC = "mÐ";
    /** Currency symbol for base 1/1000000 Dash. */
    public static final String SYMBOL_UBTC = "µÐ";

    public static final int MAX_DECIMALS = 8;

    private final Locale locale;
    private final char negativeSign;
    private final char positiveSign;
    private final char zeroDigit;
    private final char decimalMark;
    private final boolean showGroupingSeparator;
    private final int minDecimals;
    private final List<Integer> decimalGroups;
    private final int shift;
    private final RoundingMode roundingMode;
    private final String[] codes;
    private final char codeSeparator;
    private final boolean codePrefixed;

    private static final String DECIMALS_PADDING = "0000000000000000"; // a few more than necessary for Dash

    /**
     * Set character to prefix negative values.
     */
    public MonetaryFormat negativeSign(char negativeSign) {
        checkArgument(!Character.isDigit(negativeSign));
        checkArgument(negativeSign > 0);
        if (negativeSign == this.negativeSign)
            return this;
        else
            return new MonetaryFormat(locale, negativeSign, positiveSign, zeroDigit, decimalMark, showGroupingSeparator,
                    minDecimals, decimalGroups, shift, roundingMode, codes, codeSeparator, codePrefixed);
    }

    /**
     * Set character to prefix positive values. A zero value means no sign is used in this case. For parsing, a missing
     * sign will always be interpreted as if the positive sign was used.
     */
    public MonetaryFormat positiveSign(char positiveSign) {
        checkArgument(!Character.isDigit(positiveSign));
        if (positiveSign == this.positiveSign)
            return this;
        else
            return new MonetaryFormat(locale, negativeSign, positiveSign, zeroDigit, decimalMark, showGroupingSeparator,
                    minDecimals, decimalGroups, shift, roundingMode, codes, codeSeparator, codePrefixed);
    }

    /**
     * Set character range to use for representing digits. It starts with the specified character representing zero.
     */
    public MonetaryFormat digits(char zeroDigit) {
        if (zeroDigit == this.zeroDigit)
            return this;
        else
            return new MonetaryFormat(locale, negativeSign, positiveSign, zeroDigit, decimalMark, showGroupingSeparator,
                    minDecimals, decimalGroups, shift, roundingMode, codes, codeSeparator, codePrefixed);
    }

    /**
     * Set character to use as the decimal mark. If the formatted value does not have any decimals, no decimal mark is
     * used either.
     */
    public MonetaryFormat decimalMark(char decimalMark) {
        checkArgument(!Character.isDigit(decimalMark));
        checkArgument(decimalMark > 0);
        if (decimalMark == this.decimalMark)
            return this;
        else
            return new MonetaryFormat(locale, negativeSign, positiveSign, zeroDigit, decimalMark, showGroupingSeparator,
                    minDecimals, decimalGroups, shift, roundingMode, codes, codeSeparator, codePrefixed);
    }

    /**
     * Set minimum number of decimals to use for formatting. If the value precision exceeds all decimals specified
     * (including additional decimals specified by {@link #optionalDecimals(int...)} or
     * {@link #repeatOptionalDecimals(int, int)}), the value will be rounded. This configuration is not relevant for
     * parsing.
     */
    public MonetaryFormat minDecimals(int minDecimals) {
        if (minDecimals == this.minDecimals)
            return this;
        else
            return new MonetaryFormat(locale, negativeSign, positiveSign, zeroDigit, decimalMark, showGroupingSeparator,
                    minDecimals, decimalGroups, shift, roundingMode, codes, codeSeparator, codePrefixed);
    }

    /**
     * <p>
     * Set additional groups of decimals to use for formatting, e.g. 2 - 2 - 2 for 1.00 00 00. If the value precision
     * exceeds all decimals specified (including minimum decimals), the value will be rounded. This configuration is not
     * relevant for parsing.
     * </p>
     *
     * <p>
     * For example, if you pass {@code 4,2} it will add four decimals to your formatted string if needed, and then add
     * another two decimals if needed. At this point, rather than adding further decimals the value will be rounded.
     * </p>
     *
     * @param groups
     *            any number numbers of decimals, one for each group
     */
    public MonetaryFormat optionalDecimals(int... groups) {
        List<Integer> decimalGroups = new ArrayList<>(groups.length);
        for (int group : groups)
            decimalGroups.add(group);
        return new MonetaryFormat(locale, negativeSign, positiveSign, zeroDigit, decimalMark, showGroupingSeparator,
                minDecimals, decimalGroups, shift, roundingMode, codes, codeSeparator, codePrefixed);
    }

    /**
     * <p>
     * Set repeated additional groups of decimals to use for formatting, e.g. 1 - 1 - 1 for 1.0 0 0. If the value
     * precision exceeds all decimals specified (including minimum decimals), the value will be rounded. This
     * configuration is not relevant for parsing.
     * </p>
     *
     * <p>
     * For example, if you pass {@code 1,8} it will up to eight decimals to your formatted string if needed. After
     * these have been used up, rather than adding further decimals the value will be rounded.
     * </p>
     *
     * @param decimals
     *            value of the group to be repeated
     * @param repetitions
     *            number of repetitions
     */
    public MonetaryFormat repeatOptionalDecimals(int decimals, int repetitions) {
        checkArgument(repetitions >= 0);
        List<Integer> decimalGroups = new ArrayList<>(repetitions);
        for (int i = 0; i < repetitions; i++)
            decimalGroups.add(decimals);
        return new MonetaryFormat(locale, negativeSign, positiveSign, zeroDigit, decimalMark, showGroupingSeparator,
                minDecimals, decimalGroups, shift, roundingMode, codes, codeSeparator, codePrefixed);
    }

    /**
     * Set number of digits to shift the decimal separator to the right, coming from the standard DASH notation that
     * was common pre-2014. Note this will change the currency code if enabled.
     */
    public MonetaryFormat shift(int shift) {
        if (shift == this.shift)
            return this;
        else
            return new MonetaryFormat(locale, negativeSign, positiveSign, zeroDigit, decimalMark, showGroupingSeparator,
                    minDecimals, decimalGroups, shift, roundingMode, codes, codeSeparator, codePrefixed);
    }

    /**
     * Set rounding mode to use when it becomes necessary.
     */
    public MonetaryFormat roundingMode(RoundingMode roundingMode) {
        if (roundingMode == this.roundingMode)
            return this;
        else
            return new MonetaryFormat(locale, negativeSign, positiveSign, zeroDigit, decimalMark, showGroupingSeparator,
                    minDecimals, decimalGroups, shift, roundingMode, codes, codeSeparator, codePrefixed);
    }

    /**
     * Don't display currency code when formatting. This configuration is not relevant for parsing.
     */
    public MonetaryFormat noCode() {
        if (codes == null)
            return this;
        else
            return new MonetaryFormat(locale, negativeSign, positiveSign, zeroDigit, decimalMark, showGroupingSeparator,
                    minDecimals, decimalGroups, shift, roundingMode, null, codeSeparator, codePrefixed);
    }

    /**
     * Configure currency code for given decimal separator shift. This configuration is not relevant for parsing.
     *
     * @param codeShift
     *            decimal separator shift, see {@link #shift}
     * @param code
     *            currency code
     */
    public MonetaryFormat code(int codeShift, String code) {
        checkArgument(codeShift >= 0);
        final String[] codes = null == this.codes
            ? new String[MAX_DECIMALS]
            : Arrays.copyOf(this.codes, this.codes.length);

        codes[codeShift] = code;
        return new MonetaryFormat(locale, negativeSign, positiveSign, zeroDigit, decimalMark, showGroupingSeparator,
                minDecimals, decimalGroups, shift, roundingMode, codes, codeSeparator, codePrefixed);
    }

    /**
     * Separator between currency code and formatted value. This configuration is not relevant for parsing.
     */
    public MonetaryFormat codeSeparator(char codeSeparator) {
        checkArgument(!Character.isDigit(codeSeparator));
        checkArgument(codeSeparator > 0);
        if (codeSeparator == this.codeSeparator)
            return this;
        else
            return new MonetaryFormat(locale, negativeSign, positiveSign, zeroDigit, decimalMark, showGroupingSeparator,
                    minDecimals, decimalGroups, shift, roundingMode, codes, codeSeparator, codePrefixed);
    }

    /**
     * Prefix formatted output by currency code. This configuration is not relevant for parsing.
     */
    public MonetaryFormat prefixCode() {
        if (codePrefixed)
            return this;
        else
            return new MonetaryFormat(locale, negativeSign, positiveSign, zeroDigit, decimalMark, showGroupingSeparator,
                    minDecimals, decimalGroups, shift, roundingMode, codes, codeSeparator, true);
    }

    /**
     * Postfix formatted output with currency code. This configuration is not relevant for parsing.
     */
    public MonetaryFormat postfixCode() {
        if (!codePrefixed)
            return this;
        else
            return new MonetaryFormat(locale, negativeSign, positiveSign, zeroDigit, decimalMark, showGroupingSeparator,
                    minDecimals, decimalGroups, shift, roundingMode, codes, codeSeparator, false);
    }

    /**
     * Configure this instance with values from a {@link Locale}.
     */
    public MonetaryFormat withLocale(Locale locale) {
        DecimalFormatSymbols dfs = new DecimalFormatSymbols(locale);
        char negativeSign = dfs.getMinusSign();
        char zeroDigit = dfs.getZeroDigit();
        char decimalMark = dfs.getMonetaryDecimalSeparator();
        return new MonetaryFormat(locale, negativeSign, positiveSign, zeroDigit, decimalMark, showGroupingSeparator,
                minDecimals, decimalGroups, shift, roundingMode, codes, codeSeparator, codePrefixed);
    }

    /**
     * Group integer part of the formatted value using the grouping separator of the configured locale.
     */
    public MonetaryFormat withGroupingSeparator() {
        return new MonetaryFormat(locale, negativeSign, positiveSign, zeroDigit, decimalMark, true,
                minDecimals, decimalGroups, shift, roundingMode, codes, codeSeparator, codePrefixed);
    }

    public MonetaryFormat() {
        this(false);
    }

    public MonetaryFormat(boolean useSymbol) {
        // defaults
        this.locale = Locale.US;
        this.negativeSign = '-';
        this.positiveSign = 0; // none
        this.zeroDigit = '0';
        this.decimalMark = '.';
        this.showGroupingSeparator = false;
        this.minDecimals = 2;
        this.decimalGroups = null;
        this.shift = 0;
        this.roundingMode = RoundingMode.HALF_UP;
        this.codes = new String[MAX_DECIMALS];
        this.codes[0] = useSymbol ? SYMBOL_BTC : CODE_BTC;
        this.codes[3] = useSymbol ? SYMBOL_MBTC : CODE_MBTC;
        this.codes[6] = useSymbol ? SYMBOL_UBTC : CODE_UBTC;
        this.codeSeparator = ' ';
        this.codePrefixed = true;
    }

    private MonetaryFormat(Locale locale, char negativeSign, char positiveSign, char zeroDigit, char decimalMark,
            boolean showGroupingSeparator, int minDecimals, List<Integer> decimalGroups, int shift,
            RoundingMode roundingMode, String[] codes, char codeSeparator, boolean codePrefixed) {
        this.locale = locale;
        this.negativeSign = negativeSign;
        this.positiveSign = positiveSign;
        this.zeroDigit = zeroDigit;
        this.decimalMark = decimalMark;
        this.showGroupingSeparator = showGroupingSeparator;
        this.minDecimals = minDecimals;
        this.decimalGroups = decimalGroups;
        this.shift = shift;
        this.roundingMode = roundingMode;
        this.codes = codes;
        this.codeSeparator = codeSeparator;
        this.codePrefixed = codePrefixed;
    }

    /**
     * Format the given monetary value to a human readable form.
     */
    public CharSequence format(Monetary monetary) {
        // determine maximum number of decimals that can be visible in the formatted string
        // (if all decimal groups were to be used)
        int max = minDecimals;
        if (decimalGroups != null)
            for (int group : decimalGroups)
                max += group;
        final int smallestUnitExponent = monetary.smallestUnitExponent();
        checkState(max <= smallestUnitExponent,
                "The maximum possible number of decimals (%s) cannot exceed %s.", max, smallestUnitExponent);

        // rounding
        long satoshis = Math.abs(monetary.getValue());
        long precisionDivisor = LongMath.checkedPow(10, smallestUnitExponent - shift - max);
        satoshis = LongMath.checkedMultiply(LongMath.divide(satoshis, precisionDivisor, roundingMode), precisionDivisor);

        // shifting
        long shiftDivisor = LongMath.checkedPow(10, smallestUnitExponent - shift);
        long numbers = satoshis / shiftDivisor;
        long decimals = satoshis % shiftDivisor;

        // formatting
        String decimalsStr = String.format(Locale.US, "%0" + (smallestUnitExponent - shift) + "d", decimals);
        StringBuilder str = new StringBuilder(decimalsStr);
        while (str.length() > minDecimals && str.charAt(str.length() - 1) == '0')
            str.setLength(str.length() - 1); // trim trailing zero
        int i = minDecimals;
        if (decimalGroups != null) {
            for (int group : decimalGroups) {
                if (str.length() > i && str.length() < i + group) {
                    while (str.length() < i + group)
                        str.append('0');
                    break;
                }
                i += group;
            }
        }
        if (str.length() > 0)
            str.insert(0, decimalMark);
        if (showGroupingSeparator) {
            String grouped = String.format(locale, "%,d", numbers);
            str.insert(0, grouped);
        } else {
            str.insert(0, numbers);
        }
        if (monetary.getValue() < 0)
            str.insert(0, negativeSign);
        else if (positiveSign != 0)
            str.insert(0, positiveSign);
        if (codes != null) {
            if (codePrefixed) {
                str.insert(0, codeSeparator);
                str.insert(0, code());
            } else {
                str.append(codeSeparator);
                str.append(code());
            }
        }

        // Convert to non-arabic digits.
        if (zeroDigit != '0') {
            int offset = zeroDigit - '0';
            for (int d = 0; d < str.length(); d++) {
                char c = str.charAt(d);
                if (Character.isDigit(c))
                    str.setCharAt(d, (char) (c + offset));
            }
        }
        return str;
    }

    /**
     * Parse a human readable coin value to a {@link Coin} instance.
     *
     * @throws NumberFormatException
     *             if the string cannot be parsed for some reason
     */
    public Coin parse(String str) throws NumberFormatException {
        return Coin.valueOf(parseValue(str, Coin.SMALLEST_UNIT_EXPONENT));
    }

    /**
     * Parse a human readable fiat value to a {@link Fiat} instance.
     *
     * @throws NumberFormatException
     *             if the string cannot be parsed for some reason
     */
    public Fiat parseFiat(String currencyCode, String str) throws NumberFormatException {
        return Fiat.valueOf(currencyCode, parseValue(str, Fiat.SMALLEST_UNIT_EXPONENT));
    }

    private long parseValue(String str, int smallestUnitExponent) {
        if (showGroupingSeparator) {
            DecimalFormatSymbols dfs = new DecimalFormatSymbols(locale);
            char groupingSeparator = dfs.getGroupingSeparator();
            str = str.replace(String.valueOf(groupingSeparator), "");
        }
        checkState(DECIMALS_PADDING.length() >= smallestUnitExponent);
        if (str.isEmpty())
            throw new NumberFormatException("empty string");
        char first = str.charAt(0);
        if (first == negativeSign || first == positiveSign)
            str = str.substring(1);
        String numbers;
        String decimals;
        int decimalMarkIndex = str.indexOf(decimalMark);
        if (decimalMarkIndex != -1) {
            numbers = str.substring(0, decimalMarkIndex);
            decimals = (str + DECIMALS_PADDING).substring(decimalMarkIndex + 1);
            if (decimals.indexOf(decimalMark) != -1)
                throw new NumberFormatException("more than one decimal mark");
        } else {
            numbers = str;
            decimals = DECIMALS_PADDING;
        }
        String satoshis = numbers + decimals.substring(0, smallestUnitExponent - shift);
        for (char c : satoshis.toCharArray())
            if (!Character.isDigit(c))
                throw new NumberFormatException("illegal character: " + c);
        long value = Long.parseLong(satoshis); // Non-arabic digits allowed here.
        if (first == negativeSign)
            value = -value;
        return value;
    }

    /**
     * Get currency code that will be used for current shift.
     */
    public String code() {
        if (codes == null)
            return null;
        if (codes[shift] == null)
            throw new NumberFormatException("missing code for shift: " + shift);
        return codes[shift];
    }

    @Override
    public boolean equals(Object o) {
        if (o == this)
            return true;
        if (o == null || o.getClass() != getClass())
            return false;
        final MonetaryFormat other = (MonetaryFormat) o;
        if (!Objects.equals(this.negativeSign, other.negativeSign))
            return false;
        if (!Objects.equals(this.positiveSign, other.positiveSign))
            return false;
        if (!Objects.equals(this.zeroDigit, other.zeroDigit))
            return false;
        if (!Objects.equals(this.decimalMark, other.decimalMark))
            return false;
        if (!Objects.equals(this.minDecimals, other.minDecimals))
            return false;
        if (!Objects.equals(this.decimalGroups, other.decimalGroups))
            return false;
        if (!Objects.equals(this.shift, other.shift))
            return false;
        if (!Objects.equals(this.roundingMode, other.roundingMode))
            return false;
        if (!Arrays.equals(this.codes, other.codes))
            return false;
        if (!Objects.equals(this.codeSeparator, other.codeSeparator))
            return false;
        if (!Objects.equals(this.codePrefixed, other.codePrefixed))
            return false;
        return true;
    }

    @Override
    public int hashCode() {
        return Objects.hash(negativeSign, positiveSign, zeroDigit, decimalMark, minDecimals, decimalGroups, shift,
                roundingMode, Arrays.hashCode(codes), codeSeparator, codePrefixed);
    }
}
