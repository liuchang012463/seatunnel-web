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
 * through the PGPASSWORD environment variable instead of the command line.</p>
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
        command.add("USERID=" + user + "/" + password + "@" + host + ":" + port);
        command.add("FILE=" + dmpFile.getFileName());
        command.add("LOG=" + logFile.getFileName());
        command.add("DIRECTORY=" + dmpFile.getParent());
        command.add("IGNORE=" + (ignoreErrors ? "Y" : "N"));

        String effectiveSource = StringUtils.trimToNull(sourceSchema);
        String effectiveTarget = StringUtils.trimToNull(targetSchema);
        if (effectiveSource != null && effectiveTarget != null && !effectiveSource.equals(effectiveTarget)) {
            command.add("SCHEMAS=" + effectiveSource);
            command.add("REMAP_SCHEMA=" + effectiveSource + ":" + effectiveTarget);
        } else if (effectiveTarget != null) {
            command.add("SCHEMAS=" + effectiveTarget);
        }
        return new DmpCommand(command, Map.of());
    }

    public static DmpCommand kingbase(
            String sysRestorePath,
            String host,
            String port,
            String database,
            String user,
            String password,
            Path dmpFile,
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
        String schema = StringUtils.trimToNull(targetSchema);
        if (schema != null) {
            command.add("--schema=" + schema);
        }
        command.add(dmpFile.toAbsolutePath().toString());

        Map<String, String> environment = new LinkedHashMap<>();
        environment.put("PGPASSWORD", password);
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
            int hostAt = token.indexOf('@', userEnd);
            String hostPart = hostAt > 0 ? token.substring(hostAt) : "";
            return "USERID=" + token.substring("USERID=".length(), userEnd) + "/******" + hostPart;
        }
        return token.replace(password, "******");
    }
}
