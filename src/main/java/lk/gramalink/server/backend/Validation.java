package lk.gramalink.server.backend;

import java.util.UUID;

public final class Validation {
    private Validation() { }
    public static String text(String value, int max, String field) {
        if (value == null || value.isBlank() || value.length() > max
                || value.chars().anyMatch(c -> Character.isISOControl(c) && c != '\n' && c != '\r' && c != '\t')
                || value.codePoints().anyMatch(c -> c >= 0xD800 && c <= 0xDFFF))
            throw ServiceException.invalid(field + " is required and must contain at most " + max + " characters.");
        return value.strip();
    }
    public static UUID id(String value) {
        try { return UUID.fromString(value); } catch (RuntimeException e) { throw ServiceException.invalid("Invalid identifier."); }
    }
    public static void password(String value) {
        if (value == null || value.length() < 10 || value.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 72)
            throw ServiceException.invalid("Password must contain at least 10 characters and at most 72 UTF-8 bytes.");
    }
}
