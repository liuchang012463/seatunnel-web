package org.apache.seatunnel.web.api.lake.doris;

import org.apache.seatunnel.web.spi.datasource.BaseConnectionParam;

/** Keeps lake administration independent of any user-managed Doris database. */
public final class LakeDorisConnectionParams {

    private LakeDorisConnectionParams() {
    }

    public static BaseConnectionParam withoutDefaultDatabase(BaseConnectionParam param) {
        param.setUrl(withoutDefaultDatabase(param.getUrl()));
        param.setDatabase("");
        return param;
    }

    public static String withoutDefaultDatabase(String url) {
        int authority = url.indexOf("://");
        if (authority < 0) {
            throw new IllegalArgumentException("Lake Doris JDBC URL is invalid");
        }
        int query = url.indexOf('?', authority + 3);
        int path = url.indexOf('/', authority + 3);
        int authorityEnd = query < 0 ? url.length() : query;
        if (path >= 0 && path < authorityEnd) {
            authorityEnd = path;
        }
        // Keep hosts and driver options, without selecting a potentially deleted ODS database.
        return url.substring(0, authorityEnd) + '/' + (query < 0 ? "" : url.substring(query));
    }
}
