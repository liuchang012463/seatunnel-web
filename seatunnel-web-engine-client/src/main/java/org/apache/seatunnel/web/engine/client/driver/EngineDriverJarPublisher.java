package org.apache.seatunnel.web.engine.client.driver;

import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.apache.seatunnel.web.engine.client.exceptions.SeaTunnelClientException;
import org.apache.seatunnel.web.engine.client.rest.SeaTunnelRestClient;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Publishes the JDBC driver jars a job declares to the engine it is submitted to.
 *
 * <p>The Web resolves {@code driver_location} against its own driver directory, which the engine
 * does not necessarily share. Before a job is submitted, every jar that exists locally is uploaded
 * to the engine, which stores it and pushes it to every cluster member, and the job config is
 * rewritten to the path the engine stored it at. A value that is not a local file is left alone:
 * it is either already an engine path or a path the deployment provides on the nodes.
 */
@Slf4j
@Component
public class EngineDriverJarPublisher {

    /**
     * Matches a driver location in both the rendered form ({@code driver_location = "..."}) and the
     * form a user may write by hand in a script job ({@code driver_location = /path/x.jar}).
     */
    private static final Pattern DRIVER_LOCATION =
            Pattern.compile("driver_location\\s*[=:]\\s*(?:\"([^\"]*)\"|([^\\s#}]+))");

    private static final String JAR_SEPARATOR = ";";

    private final SeaTunnelRestClient restClient;

    public EngineDriverJarPublisher(SeaTunnelRestClient restClient) {
        this.restClient = restClient;
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
            if (!jar.isFile()) {
                publishedPaths.add(path);
                continue;
            }

            publishedPaths.add(upload(clientId, jar));
            changed = true;
        }

        return changed ? String.join(JAR_SEPARATOR, publishedPaths) : null;
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
