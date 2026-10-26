package org.apache.seatunnel.web.api.offlineimport;

import java.util.regex.Pattern;

/** Masks credentials that can appear in SQL dumps and native tool output. */
public final class OfflineImportLogRedactor {

    private static final Pattern USERID_WITH_HOST =
            Pattern.compile("(?i)(USERID=[^/\\s]+/)(.+)(@[^\\s]+)$");
    private static final Pattern USERID_WITHOUT_HOST =
            Pattern.compile("(?i)(USERID=[^/\\s]+/)(.+)$");
    private static final Pattern IDENTIFIED_BY =
            Pattern.compile("(?i)(IDENTIFIED\\s+BY\\s+)(?:'(?:[^']|'')*'|\"[^\"]*\"|\\S+)");
    private static final Pattern NAMED_SECRET =
            Pattern.compile("(?i)(\\b(?:PASSWORD|PASSWD|PWD|SECRET|TOKEN)\\s*[=:]\\s*)"
                    + "(?:'(?:[^']|'')*'|\"[^\"]*\"|\\S+)");
    private static final Pattern PG_PASSWORD =
            Pattern.compile("(?i)(\\bPGPASSWORD\\s*=\\s*)(?:'(?:[^']|'')*'|\"[^\"]*\"|\\S+)");
    private static final Pattern PASSWORD_KEYWORD =
            Pattern.compile("(?i)(\\bPASSWORD\\s+)(?:'(?:[^']|'')*'|\"[^\"]*\"|\\S+)");

    private OfflineImportLogRedactor() {
    }

    public static String mask(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        String masked = USERID_WITH_HOST.matcher(text).replaceAll("$1******$3");
        masked = USERID_WITHOUT_HOST.matcher(masked).replaceAll("$1******");
        masked = PG_PASSWORD.matcher(masked).replaceAll("$1******");
        masked = IDENTIFIED_BY.matcher(masked).replaceAll("$1******");
        masked = PASSWORD_KEYWORD.matcher(masked).replaceAll("$1******");
        return NAMED_SECRET.matcher(masked).replaceAll("$1******");
    }
}
