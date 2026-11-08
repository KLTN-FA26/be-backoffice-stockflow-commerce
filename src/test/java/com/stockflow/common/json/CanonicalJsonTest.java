package com.stockflow.common.json;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Pins {@link CanonicalJson} to RFC 8785: its worked examples (section 3.2) and the number samples of appendix B, which
 * are exactly the cases where a hand-written ECMAScript number formatter goes wrong.
 */
class CanonicalJsonTest {

    @Test
    void rfcExampleIsCanonicalizedByteForByte() {
        String input = """
                {
                  "numbers": [333333333.33333329, 1E30, 4.50, 2e-3, 0.000000000000000000000000001],
                  "string": "\\u20ac$\\u000F\\u000aA'\\u0042\\u0022\\u005c\\\\\\"\\/",
                  "literals": [null, true, false]
                }""";

        assertThat(CanonicalJson.canonicalize(input)).isEqualTo(
                "{\"literals\":[null,true,false],\"numbers\":[333333333.3333333,1e+30,4.5,0.002,1e-27],"
                        + "\"string\":\"\u20ac$\\u000f\\nA'B\\\"\\\\\\\\\\\"/\"}");
    }

    @Test
    void membersAreSortedByUtf16CodeUnitsNotCodePoints() {
        String input = """
                {
                  "\\u20ac": "Euro Sign",
                  "\\r": "Carriage Return",
                  "\\ufb33": "Hebrew Letter Dalet With Dagesh",
                  "1": "One",
                  "\\ud83d\\ude00": "Emoji: Grinning Face",
                  "\\u0080": "Control",
                  "\\u00f6": "Latin Small Letter O With Diaeresis"
                }""";

        // The emoji's high surrogate (U+D83D) sorts before U+FB33 although its code point (U+1F600) is larger.
        assertThat(CanonicalJson.canonicalize(input)).isEqualTo("{\"\\r\":\"Carriage Return\",\"1\":\"One\","
                + "\"\u0080\":\"Control\",\"\u00f6\":\"Latin Small Letter O With Diaeresis\","
                + "\"\u20ac\":\"Euro Sign\",\"\ud83d\ude00\":\"Emoji: Grinning Face\","
                + "\"\ufb33\":\"Hebrew Letter Dalet With Dagesh\"}");
    }

    @ParameterizedTest
    @CsvSource({
            "0000000000000000, 0",
            "8000000000000000, 0",
            "0000000000000001, 5e-324",
            "8000000000000001, -5e-324",
            "7fefffffffffffff, 1.7976931348623157e+308",
            "ffefffffffffffff, -1.7976931348623157e+308",
            "4340000000000000, 9007199254740992",
            "c340000000000000, -9007199254740992",
            "4430000000000000, 295147905179352830000",
            "44b52d02c7e14af5, 9.999999999999997e+22",
            "44b52d02c7e14af6, 1e+23",
            "44b52d02c7e14af7, 1.0000000000000001e+23",
            "444b1ae4d6e2ef4e, 999999999999999700000",
            "444b1ae4d6e2ef4f, 999999999999999900000",
            "444b1ae4d6e2ef50, 1e+21",
            "3eb0c6f7a0b5ed8c, 9.999999999999997e-7",
            "3eb0c6f7a0b5ed8d, 0.000001",
            "41b3de4355555553, 333333333.3333332",
            "41b3de4355555554, 333333333.33333325",
            "41b3de4355555555, 333333333.3333333",
            "41b3de4355555556, 333333333.3333334",
            "41b3de4355555557, 333333333.33333343",
            "becbf647612f3696, -0.0000033333333333333333",
            "43143ff3c1cb0959, 1424953923781206.2"
    })
    void numbersAreWrittenAsEcmaScriptWritesThem(String bits, String expected) {
        double value = Double.longBitsToDouble(Long.parseUnsignedLong(bits, 16));

        assertThat(CanonicalJson.number(value)).isEqualTo(expected);
        // And through the parser: the canonical text of the canonical text is itself.
        assertThat(CanonicalJson.canonicalize(expected)).isEqualTo(expected);
    }

    @Test
    void integersBeyondTwoToTheFiftyThreeLoseWhatTheBrowserLoses() {
        assertThat(CanonicalJson.canonicalize("9007199254740993")).isEqualTo("9007199254740992");
        assertThat(CanonicalJson.canonicalize("[1.0, 100, -0.0, 1e2]")).isEqualTo("[1,100,0,100]");
    }

    @Test
    void stringsAreEscapedAsJsonStringifyEscapesThem() {
        assertThat(CanonicalJson.canonicalize("\"a\\u0001b\\u001fc\\u2028d\\u00e9\""))
                .isEqualTo("\"a\\u0001b\\u001fc\u2028d\u00e9\"");
        assertThat(CanonicalJson.canonicalize("\"\\b\\f\\n\\r\\t\"")).isEqualTo("\"\\b\\f\\n\\r\\t\"");
    }

    @Test
    void whitespaceAndKeyOrderDoNotChangeTheChecksum() {
        String one = CanonicalJson.canonicalize("{\"b\": [1, {\"y\": 2, \"x\": 1}], \"a\": \"t\"}");
        String other = CanonicalJson.canonicalize("{ \"a\":\"t\",\n \"b\":[1.0,{\"x\":1,\"y\":2.0}] }");

        assertThat(one).isEqualTo(other).isEqualTo("{\"a\":\"t\",\"b\":[1,{\"x\":1,\"y\":2}]}");
        assertThat(CanonicalJson.sha256Hex(one)).isEqualTo(CanonicalJson.sha256Hex(other)).hasSize(64);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"a\": 1, \"a\": 2}",
            "\"\\ud800\"",
            "\"\\udc00x\"",
            "{\"\\ud83d\": 1}",
            "1e400",
            "",
            "   ",
            "{\"a\": 1} {}",
            "{\"a\": }",
            "[1, 2"
    })
    void textTheRfcLeavesUndefinedIsRefused(String input) {
        assertThatThrownBy(() -> CanonicalJson.canonicalize(input))
                .isInstanceOf(CanonicalJson.InvalidJsonException.class);
    }

    @Test
    void nestingIsBounded() {
        String deep = "[".repeat(CanonicalJson.MAX_DEPTH + 1) + "]".repeat(CanonicalJson.MAX_DEPTH + 1);
        String allowed = "[".repeat(CanonicalJson.MAX_DEPTH) + "]".repeat(CanonicalJson.MAX_DEPTH);

        assertThatThrownBy(() -> CanonicalJson.canonicalize(deep))
                .isInstanceOf(CanonicalJson.InvalidJsonException.class);
        assertThat(CanonicalJson.canonicalize(allowed)).isEqualTo(allowed);
    }
}
