package com.databridge.core.validation;

import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/** Named formats for Kenyan identifiers and contact details, usable by FORMAT rules. */
public final class Patterns {

    public static final Map<String, String> NAMED = Map.of(
            "KRA_PIN", "^[AP]\\d{9}[A-Z]$",
            "KE_PHONE", "^\\+254[17]\\d{8}$",
            "EMAIL", "^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$",
            "KE_ID", "^\\d{6,9}$");

    static final Map<String, String> DESCRIBED = Map.of(
            "KRA_PIN", "a KRA PIN: a letter, 9 digits and a letter",
            "KE_PHONE", "a phone number like +2547XXXXXXXX or +2541XXXXXXXX",
            "EMAIL", "an email address",
            "KE_ID", "a national ID number of 6 to 9 digits");

    private Patterns() { }

    public static Pattern compile(String nameOrRegex) {
        String regex = NAMED.getOrDefault(nameOrRegex.toUpperCase(Locale.ROOT), nameOrRegex);
        try {
            return Pattern.compile(regex);
        } catch (PatternSyntaxException e) {
            throw new IllegalArgumentException("'" + nameOrRegex + "' is neither a named format ("
                    + String.join(", ", NAMED.keySet()) + ") nor a valid regular expression");
        }
    }

    static String describe(String nameOrRegex) {
        return DESCRIBED.getOrDefault(nameOrRegex.toUpperCase(Locale.ROOT), "the format " + nameOrRegex);
    }
}
