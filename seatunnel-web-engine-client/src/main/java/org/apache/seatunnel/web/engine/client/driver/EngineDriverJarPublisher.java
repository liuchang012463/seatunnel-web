package org.apache.seatunnel.web.engine.client.driver;

import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.apache.seatunnel.web.engine.client.exceptions.SeaTunnelClientException;
import org.apache.seatunnel.web.engine.client.rest.SeaTunnelRestClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Publishes the JDBC driver jars a job declares to the engine it is submitted to.
 *
 * <p>The Web resolves {@code driver_location} against its own driver directory, which the engine
 * does not necessarily share. Before a job is submitted, every jar that exists locally is uploaded
 * to the engine, which stores it and pushes it to every cluster member, and the job config is
 * rewritten to the path the engine stored it at. Deployments that preinstall a different JDBC
 * driver version on every Engine node can map a local jar name to that Engine-visible path. A value
 * that is not a local file is left alone: it is either already an engine path or a path the
 * deployment provides on the nodes.
 */
@Slf4j
@Component
public class EngineDriverJarPublisher {

    /**
     * Matches a driver location in rendered HOCON (which quotes map keys) and in the form a user
     * may write by hand in a script job.
     */
    private static final Pattern DRIVER_LOCATION =
            Pattern.compile("(?:\"driver_location\"|driver_location)\\s*[=:]\\s*(?:\"([^\"]*)\"|([^\\s#}]+))");

    private static final String JAR_SEPARATOR = ";";

    private final SeaTunnelRestClient restClient;
    private final Map<String, String> engineDriverLocationMappings;

    @Autowired
    public EngineDriverJarPublisher(
            SeaTunnelRestClient restClient,
            @Value("${seatunnel.web.engine.driver-location-mappings:}") String mappings) {
        this.restClient = restClient;
        this.engineDriverLocationMappings = parseMappings(mappings);
    }

    public EngineDriverJarPublisher(SeaTunnelRestClient restClient) {
        this(restClient, "");
    }

    /**
     * Rewrites the driver locations of a job config to engine paths, uploading the jars first.
     *
     * @param clientId the engine the job is submitted to
     * @param hoconConfig the job config
     * @return the job config to submit
     */
    public String publish(Long clientId, String hoconConfig) {
        if (StringUtils.isBlank(hoconConfig)) {
            return hoconConfig;
        }

        Matcher matcher = DRIVER_LOCATION.matcher(hoconConfig);
        StringBuilder publishedConfig = new StringBuilder(hoconConfig.length());
        int copiedUpTo = 0;
        boolean changed = false;

        while (matcher.find()) {
            publishedConfig.append(hoconConfig, copiedUpTo, matcher.start());

            int valueGroup = matcher.group(1) != null ? 1 : 2;
            String value = matcher.group(valueGroup);
            String publishedValue = publishValue(clientId, value);

            if (publishedValue == null) {
                publishedConfig.append(matcher.group());
            } else {
                // The matched text is copied around the value, so quoting and separators of both
                // the rendered and the hand written form are preserved as they are.
                int valueStart = matcher.start(valueGroup) - matcher.start();
                publishedConfig
                        .append(matcher.group(), 0, valueStart)
                        .append(publishedValue)
                        .append(
                                matcher.group(),
                                valueStart + value.length(),
                                matcher.group().length());
                changed = true;
            }
            copiedUpTo = matcher.end();
        }
        publishedConfig.append(hoconConfig, copiedUpTo, hoconConfig.length());

        return changed ? publishedConfig.toString() : hoconConfig;
    }

    /**
     * @return the value to use in the job config, or null when nothing has to be replaced
     */
    private String publishValue(Long clientId, String value) {
        List<String> publishedPaths = new ArrayList<>();
        boolean changed = false;

        for (String entry : value.split(JAR_SEPARATOR)) {
            String path = entry.trim();
            if (path.isEmpty()) {
                continue;
            }

            File jar = new File(path);
            String mappedPath = jar.isFile() ? engineDriverLocationMappings.get(jar.getName()) : null;
            if (StringUtils.isNotBlank(mappedPath)) {
                publishedPaths.add(mappedPath);
                changed = true;
                log.info("Using preinstalled JDBC driver {} as {}", jar.getName(), mappedPath);
                continue;
            }
            if (!jar.isFile()) {
                publishedPaths.add(path);
                continue;
            }

            publishedPaths.add(upload(clientId, jar));
            changed = true;
        }

        return changed ? String.join(JAR_SEPARATOR, publishedPaths) : null;
    }

    private static Map<String, String> parseMappings(String mappings) {
        if (StringUtils.isBlank(mappings)) {
            return Map.of();
        }

        Map<String, String> parsed = new LinkedHashMap<>();
        for (String entry : mappings.split(",")) {
            int separator = entry.indexOf('=');
            if (separator <= 0 || separator == entry.length() - 1) {
                throw new IllegalArgumentException(
                        "Engine driver mappings must use local-jar-name.jar=/absolute/engine/path.jar entries");
            }

            String localFileName = entry.substring(0, separator).trim();
            String enginePath = entry.substring(separator + 1).trim();
            if (!localFileName.equals(Path.of(localFileName).getFileName().toString())
                    || !localFileName.toLowerCase(Locale.ROOT).endsWith(".jar")) {
                throw new IllegalArgumentException(
                        "Engine driver mapping keys must be JDBC jar file names");
            }

            Path path;
            try {
                path = Path.of(enginePath);
            } catch (InvalidPathException e) {
                throw new IllegalArgumentException(
                        "Engine driver mapping values must be absolute JDBC jar paths", e);
            }
            if (!path.isAbsolute()
                    || path.getFileName() == null
                    || !path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".jar")) {
                throw new IllegalArgumentException(
                        "Engine driver mapping values must be absolute JDBC jar paths");
            }
            if (parsed.putIfAbsent(localFileName, path.normalize().toString()) != null) {
                throw new IllegalArgumentException(
                        "Engine driver mapping contains duplicate local jar name: " + localFileName);
            }
        }
        return Map.copyOf(parsed);
    }

    private String upload(Long clientId, File jar) {
        try {
            String enginePath =
                    restClient.uploadDriverJar(
                            clientId, Files.readAllBytes(jar.toPath()), jar.getName());
            log.info("Published JDBC driver {} to engine {} as {}", jar, clientId, enginePath);
            return enginePath;
        } catch (IOException e) {
            throw new SeaTunnelClientException(
                    "Cannot read the JDBC driver jar " + jar, -1, "", e);
        }
    }
}
