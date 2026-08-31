package org.apache.seatunnel.plugin.datasource.http.catalog;

import org.apache.commons.lang3.StringUtils;
import org.apache.seatunnel.plugin.datasource.api.datasource.DataSourceCatalog;
import org.apache.seatunnel.plugin.datasource.http.param.HttpConnectionParam;
import org.apache.seatunnel.web.spi.bean.vo.OptionVO;

import java.util.List;

/** Catalog of HTTP operations described by an optional OpenAPI/Swagger document. */
public class HttpCatalog implements DataSourceCatalog {

    private final HttpConnectionParam param;
    private final HttpOpenApiDocumentClient documentClient;

    public HttpCatalog(HttpConnectionParam param) {
        this(param, new HttpOpenApiDocumentClient());
    }

    public HttpCatalog(HttpConnectionParam param, HttpOpenApiDocumentClient documentClient) {
        if (param == null) {
            throw new IllegalArgumentException("HTTP connection param must not be null");
        }
        if (documentClient == null) {
            throw new IllegalArgumentException("HTTP OpenAPI document client must not be null");
        }
        this.param = param;
        this.documentClient = documentClient;
    }

    @Override
    public List<OptionVO> listOptions() {
        if (StringUtils.isBlank(param.getOpenApiSpecUrl())) {
            return List.of();
        }
        return HttpOpenApiCatalogParser.parse(documentClient.fetch(param));
    }
}
