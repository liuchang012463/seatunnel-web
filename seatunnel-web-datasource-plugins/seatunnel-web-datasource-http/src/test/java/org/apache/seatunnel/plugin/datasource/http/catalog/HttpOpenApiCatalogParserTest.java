package org.apache.seatunnel.plugin.datasource.http.catalog;

import org.apache.seatunnel.web.spi.bean.vo.OptionVO;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HttpOpenApiCatalogParserTest {

    @Test
    void parsesOpenApi3OperationsWithStableValuesAndDescriptions() {
        String document = """
                {
                  "openapi": "3.0.3",
                  "paths": {
                    "/pets": {
                      "get": {"summary": "List pets", "operationId": "listPets"},
                      "post": {"operationId": "createPet"}
                    },
                    "/pets/{petId}": {
                      "parameters": [],
                      "delete": {"description": "Remove one pet"},
                      "x-vendor-extension": {"get": {}}
                    }
                  }
                }
                """;

        List<OptionVO> options = HttpOpenApiCatalogParser.parse(document);

        assertEquals(3, options.size());
        assertOption(options.get(0), "GET /pets", "List pets");
        assertOption(options.get(1), "POST /pets", "createPet");
        assertOption(options.get(2), "DELETE /pets/{petId}", "Remove one pet");
    }

    @Test
    void parsesSwagger2AndIgnoresPathParametersAndExtensions() {
        String document = """
                {
                  "swagger": "2.0",
                  "paths": {
                    "/health": {
                      "parameters": [{"name": "trace", "in": "header"}],
                      "head": {},
                      "options": {"summary": "CORS metadata"},
                      "x-custom": "ignored"
                    }
                  }
                }
                """;

        List<OptionVO> options = HttpOpenApiCatalogParser.parse(document);

        assertEquals(2, options.size());
        assertOption(options.get(0), "HEAD /health", null);
        assertOption(options.get(1), "OPTIONS /health", "CORS metadata");
    }

    @Test
    void returnsEmptyOptionsWhenPathsAreMissing() {
        assertTrue(HttpOpenApiCatalogParser.parse("{\"openapi\":\"3.0.0\"}").isEmpty());
    }

    private void assertOption(OptionVO option, String value, String description) {
        assertEquals(value, option.getValue());
        assertEquals(value, option.getLabel());
        assertEquals(description, option.getDescription());
    }
}
