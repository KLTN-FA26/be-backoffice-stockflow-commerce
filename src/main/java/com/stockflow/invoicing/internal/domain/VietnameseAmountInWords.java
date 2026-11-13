package com.stockflow.invoicing.internal.domain;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * An amount of dong written out in Vietnamese: the total an invoice must state "bằng số và bằng chữ" (NĐ 254/2026
 * Phụ lục điểm 5.d).
 *
 * <p>Read in groups of three digits - nghìn, triệu, tỷ, and tỷ again above that ("một nghìn tỷ") - with the turns the
 * spoken number takes: <i>mười</i> for a ten, <i>mươi</i> for the tens above it, <i>mốt</i> for a one after them,
 * <i>lăm</i> for a five after any ten, <i>lẻ</i> for a missing ten, and <i>không trăm</i> for a missing hundred inside
 * a number that goes on (1 005 000 is "một triệu không trăm lẻ năm nghìn"). Four stays <i>bốn</i> everywhere: "tư" is
 * as correct after a ten, and one spelling keeps two invoices of the same total reading the same.</p>
 */
public final class VietnameseAmountInWords {

    private static final String[] DIGITS = {"không", "một", "hai", "ba", "bốn", "năm", "sáu", "bảy", "tám", "chín"};
    private static final String[] GROUPS = {"", "nghìn", "triệu"};
    private static final BigInteger THOUSAND = BigInteger.valueOf(1000);
    private static final BigInteger BILLION = BigInteger.valueOf(1_000_000_000);

    private VietnameseAmountInWords() {
    }

    /**
     * {@code amount} in words, with a capital first letter and "đồng" after it: 1 250 000 is "Một triệu hai trăm năm
     * mươi nghìn đồng". Below zero (an adjustment that reduces) it starts with "Âm".
     *
     * @throws IllegalArgumentException for an amount that is not whole dong
     */
    public static String of(BigDecimal amount) {
        BigInteger whole;
        try {
            whole = amount.toBigIntegerExact();
        } catch (ArithmeticException fraction) {
            throw new IllegalArgumentException("An invoice total is whole dong, not " + amount, fraction);
        }
        String words = whole.signum() == 0 ? DIGITS[0] : number(whole.abs());
        if (whole.signum() < 0) {
            words = "âm " + words;
        }
        return words.substring(0, 1).toUpperCase(Locale.forLanguageTag("vi")) + words.substring(1) + " đồng";
    }

    /** A positive number: what is above a billion read as a number of its own, then "tỷ", then the rest. */
    private static String number(BigInteger value) {
        BigInteger[] parts = value.divideAndRemainder(BILLION);
        List<String> words = new ArrayList<>();
        if (parts[0].signum() > 0) {
            words.add(number(parts[0]));
            words.add("tỷ");
        }
        String below = belowBillion(parts[1].intValueExact(), parts[0].signum() > 0);
        if (!below.isEmpty()) {
            words.add(below);
        }
        return String.join(" ", words);
    }

    /** 0 to 999 999 999; {@code continued} when something was read before it, so its leading group is read in full. */
    private static String belowBillion(int value, boolean continued) {
        List<String> words = new ArrayList<>();
        boolean started = continued;
        for (int group = GROUPS.length - 1; group >= 0; group--) {
            int three = BigInteger.valueOf(value).divide(THOUSAND.pow(group)).mod(THOUSAND).intValueExact();
            if (three == 0) {
                continue;
            }
            words.add(threeDigits(three, started));
            if (!GROUPS[group].isEmpty()) {
                words.add(GROUPS[group]);
            }
            started = true;
        }
        return String.join(" ", words);
    }

    /** 1 to 999; {@code full} reads a missing hundred ("không trăm") and a missing ten ("lẻ"). */
    private static String threeDigits(int value, boolean full) {
        int hundreds = value / 100;
        int tens = value / 10 % 10;
        int ones = value % 10;
        List<String> words = new ArrayList<>();
        if (hundreds > 0 || full) {
            words.add(DIGITS[hundreds]);
            words.add("trăm");
        }
        if (tens == 0) {
            if (ones > 0) {
                if (hundreds > 0 || full) {
                    words.add("lẻ");
                }
                words.add(DIGITS[ones]);
            }
            return String.join(" ", words);
        }
        words.add(tens == 1 ? "mười" : DIGITS[tens] + " mươi");
        if (ones == 1 && tens > 1) {
            words.add("mốt");
        } else if (ones == 5) {
            words.add("lăm");
        } else if (ones > 0) {
            words.add(DIGITS[ones]);
        }
        return String.join(" ", words);
    }
}
