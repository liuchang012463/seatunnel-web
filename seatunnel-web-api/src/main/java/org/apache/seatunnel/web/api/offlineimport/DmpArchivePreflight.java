package org.apache.seatunnel.web.api.offlineimport;

import org.apache.commons.lang3.StringUtils;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Helpers for checking DMP archive contents before opening a target for writes. */
public final class DmpArchivePreflight {

    private static final Pattern DAMENG_SCHEMA_SUMMARY = Pattern.compile(
            "^模式\\s*(?:\"([^\"]+)\"|([\\p{L}\\p{N}_$#]+))\\s*含有\\s*\\d+\\s*个表\\s*$");
    private static final Pattern DAMENG_SCHEMA_SUMMARY_EN = Pattern.compile(
            "^schema\\s+(?:\"([^\"]+)\"|([\\p{L}\\p{N}_$#]+))\\s+(?:contains|has)\\s+\\d+\\s+tables?\\s*$",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    private DmpArchivePreflight() {
    }

    /**
     * DIMP SHOW=Y emits a summary line for each schema in the archive. Match
     * only the complete, vendor-formatted summary so command echoes, errors,
     * comments, and table names cannot satisfy the source filter.
     */
    public static boolean damengSchemaEntry(String line, String expectedSchema) {
        if (StringUtils.isBlank(line) || StringUtils.isBlank(expectedSchema)) {
            return false;
        }
        String normalized = line.trim();
        Matcher chinese = DAMENG_SCHEMA_SUMMARY.matcher(normalized);
        if (chinese.matches() && equalsSchema(chinese.group(1), chinese.group(2), expectedSchema, true)) {
            return true;
        }
        Matcher english = DAMENG_SCHEMA_SUMMARY_EN.matcher(normalized);
        return english.matches() && equalsSchema(english.group(1), english.group(2), expectedSchema, true);
    }

    /** Mirrors the schema selected by the restore command when one side is omitted. */
    public static String selectedSourceSchema(String sourceSchema, String targetSchema) {
        String source = StringUtils.trimToNull(sourceSchema);
        return source != null ? source : StringUtils.trimToNull(targetSchema);
    }

    /**
     * Includes the common representations emitted by client tools and
     * exception/log encoders, while leaving empty passwords unregistered.
     */
    public static List<String> secretVariants(String password) {
        if (StringUtils.isEmpty(password)) {
            return List.of();
        }
        LinkedHashSet<String> variants = new LinkedHashSet<>();
        variants.add(password);
        variants.add(password.replace("\\", "\\\\"));
        variants.add(password.replace("\"", "\"\""));
        variants.add(password.replace("\\", "\\\\").replace("\"", "\\\""));
        String formEncoded = URLEncoder.encode(password, StandardCharsets.UTF_8);
        variants.add(formEncoded);
        variants.add(formEncoded.replace("+", "%20"));
        variants.remove("");
        return new ArrayList<>(variants);
    }

    private static boolean equalsSchema(String quoted, String unquoted, String expectedSchema, boolean ignoreCase) {
        String actual = quoted != null ? quoted : unquoted;
        return actual != null && (ignoreCase ? actual.equalsIgnoreCase(expectedSchema) : actual.equals(expectedSchema));
    }
}
