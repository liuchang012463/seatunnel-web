package org.apache.seatunnel.plugin.datasource.http.catalog;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.commons.lang3.StringUtils;
import org.apache.seatunnel.web.spi.bean.vo.OptionVO;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Converts the operations in an OpenAPI 3 or Swagger 2 document into catalog options.
 *
 * <p>Both specifications describe operations using the same {@code paths} shape, so no
 * specification-specific model is needed for catalog browsing.</p>
 */
public final class HttpOpenApiCatalogParser {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final List<String> HTTP_METHODS = List.of(
            "get", "post", "put", "delete", "patch", "head", "options", "trace");

    private HttpOpenApiCatalogParser() {
    }

    /**
     * Parse a JSON OpenAPI 3 or Swagger 2 document.
     *
     * @param specification JSON document text
     * @return one option for each HTTP operation under {@code paths}
     */
    public static List<OptionVO> parse(String specification) {
        if (StringUtils.isBlank(specification)) {
            return List.of();
        }
        try {
            return parse(OBJECT_MAPPER.readTree(specification));
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("HTTP OpenAPI document is invalid JSON", e);
        }
    }

    /**
     * Parse an already decoded OpenAPI/Swagger JSON tree.
     *
     * @param document JSON object containing an optional {@code paths} object
     * @return one option for each HTTP operation under {@code paths}
     */
    public static List<OptionVO> parse(JsonNode document) {
        if (document == null || !document.isObject()) {
            return List.of();
        }

        JsonNode paths = document.get("paths");
        if (paths == null || !paths.isObject()) {
            return List.of();
        }

        List<OptionVO> options = new ArrayList<>();
        Iterator<Map.Entry<String, JsonNode>> pathIterator = paths.fields();
        while (pathIterator.hasNext()) {
            Map.Entry<String, JsonNode> pathEntry = pathIterator.next();
            JsonNode pathItem = pathEntry.getValue();
            if (pathItem == null || !pathItem.isObject()) {
                continue;
            }
            for (String method : HTTP_METHODS) {
                JsonNode operation = pathItem.get(method);
                if (operation == null || !operation.isObject()) {
                    continue;
                }

                String httpMethod = method.toUpperCase(Locale.ROOT);
                String path = pathEntry.getKey();
                String operationValue = httpMethod + " " + path;

                OptionVO option = new OptionVO();
                option.setLabel(operationValue);
                option.setValue(operationValue);
                option.setDescription(firstText(operation, "summary", "operationId", "description"));
                options.add(option);
            }
        }
        return options;
    }

    private static String firstText(JsonNode operation, String... names) {
        for (String name : names) {
            JsonNode value = operation.get(name);
            if (value != null && value.isValueNode() && StringUtils.isNotBlank(value.asText())) {
                return value.asText();
            }
        }
        return null;
    }
}
