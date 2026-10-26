package org.apache.seatunnel.web.api.offlineimport;

import org.apache.commons.lang3.StringUtils;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds the vendor native-tool command line for one DMP restore.
 *
 * <p>Dameng uses {@code dimp} with USERID as the first keyword; KingbaseES
 * uses the pg_restore-compatible {@code sys_restore} with the password fed
 * through the Kingbase client environment instead of the command line.</p>
 */
public final class DmpCommandBuilder {

    private DmpCommandBuilder() {
    }

    /** Holds the executable command plus its process environment. */
    public record DmpCommand(List<String> command, Map<String, String> environment) {
    }

    public static DmpCommand dameng(
            String dimpPath,
            String host,
            String port,
            String user,
            String password,
            Path dmpFile,
            Path logFile,
            String sourceSchema,
            String targetSchema,
            boolean ignoreErrors) {
        List<String> command = new ArrayList<>();
        command.add(dimpPath);
        command.add("USERID=" + user + "/" + encodeDamengPassword(password) + "@" + host + ":" + port);
        command.add("FILE=" + dmpFile.getFileName());
        command.add("LOG=" + logFile.getFileName());
        command.add("DIRECTORY=" + dmpFile.getParent());
        command.add("IGNORE=" + (ignoreErrors ? "Y" : "N"));

        String effectiveSource = StringUtils.trimToNull(sourceSchema);
        String effectiveTarget = StringUtils.trimToNull(targetSchema);
        String selectedSource = effectiveSource != null ? effectiveSource : effectiveTarget;
        if (selectedSource != null) {
            command.add("SCHEMAS=" + selectedSource);
        }
        if (effectiveSource != null && effectiveTarget != null && !effectiveSource.equals(effectiveTarget)) {
            command.add("REMAP_SCHEMA=" + effectiveSource + ":" + effectiveTarget);
        }
        return new DmpCommand(command, Map.of());
    }

    /**
     * DIMP parses its own USERID syntax even when ProcessBuilder bypasses a shell.
     * Quote passwords containing delimiters or other punctuation, and double
     * embedded quotes according to the DIMP connection-string rules.
     */
    private static String encodeDamengPassword(String password) {
        if (password == null || password.chars().allMatch(
                ch -> Character.isLetterOrDigit(ch) || ch == '_' || ch == '-' || ch == '.')) {
            return password;
        }
        return "\"" + password.replace("\"", "\"\"") + "\"";
    }

    public static DmpCommand kingbase(
            String sysRestorePath,
            String host,
            String port,
            String database,
            String user,
            String password,
            Path dmpFile,
            String sourceSchema,
            String targetSchema) {
        List<String> command = new ArrayList<>();
        command.add(sysRestorePath);
        command.add("-h");
        command.add(host);
        command.add("-p");
        command.add(port);
        command.add("-U");
        command.add(user);
        command.add("-d");
        command.add(database);
        command.add("--no-password");
        command.add("--no-owner");
        command.add("--no-privileges");
        String effectiveSource = StringUtils.trimToNull(sourceSchema);
        String effectiveTarget = StringUtils.trimToNull(targetSchema);
        String selectedSource = effectiveSource != null ? effectiveSource : effectiveTarget;
        String selectedTarget = effectiveTarget != null ? effectiveTarget : effectiveSource;
        if (selectedSource != null) {
            command.add("--fromSchema=" + selectedSource);
            command.add("--targetSchema=" + selectedTarget);
        }
        command.add(dmpFile.toAbsolutePath().toString());

        Map<String, String> environment = new LinkedHashMap<>();
        environment.put("KINGBASE_PASSWORD", password);
        return new DmpCommand(command, environment);
    }

    /** Command rendering with the password masked, safe for persisted logs. */
    public static String mask(DmpCommand dmpCommand, String password) {
        List<String> masked = dmpCommand.command().stream()
                .map(token -> maskToken(token, password))
                .toList();
        return String.join(" ", masked);
    }

    private static String maskToken(String token, String password) {
        if (StringUtils.isEmpty(password)) {
            return token;
        }
        if (token.startsWith("USERID=") && token.contains("/")) {
            int userEnd = token.indexOf('/');
            // The password may itself contain '@', while the host part never
            // does, so only the last '@' can be the USERID delimiter.
            int hostAt = token.lastIndexOf('@');
            String hostPart = hostAt > userEnd ? token.substring(hostAt) : "";
            return "USERID=" + token.substring("USERID=".length(), userEnd) + "/******" + hostPart;
        }
        return token.replace(password, "******");
    }
}
