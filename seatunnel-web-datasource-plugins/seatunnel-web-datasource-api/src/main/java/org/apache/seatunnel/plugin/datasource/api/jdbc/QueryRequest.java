package org.apache.seatunnel.plugin.datasource.api.jdbc;

import lombok.Data;
import org.apache.commons.lang3.StringUtils;
import org.apache.seatunnel.plugin.datasource.api.enums.TaskExecutionTypeEnum;
import org.apache.seatunnel.web.spi.datasource.BaseConnectionParam;

import java.util.Map;

@Data
public class QueryRequest {

    private TaskExecutionTypeEnum taskExecuteType;
    private TablePath tablePath;
    private String query;
    private int limit = 20;

    public static QueryRequest from(Map<String, Object> body, BaseConnectionParam param) {
        QueryRequest req = new QueryRequest();
        req.taskExecuteType = TaskExecutionTypeEnum.valueOf(body.get("read_mode").toString().toUpperCase());
        switch (req.taskExecuteType) {
            case SQL:
                if (body.containsKey("query")) {
                    req.query = body.get("query").toString();
                } else {
                    throw new RuntimeException("query not exist");
                }
                break;
            case TABLE:
                if (body.containsKey("table_path")) {
                    // Prefer explicit schema_name from the caller (e.g. exploration OM
                    // schema) over the datasource connection schema. Do not infer
                    // schema from dotted table_path — MySQL uses database.table.
                    String schemaName = resolveSchemaName(body, param);
                    req.tablePath = TablePath.of(
                            param.getDatabase(),
                            schemaName,
                            body.get("table_path").toString()
                    );
                } else {
                    throw new RuntimeException("table_path not exist");
                }
                break;
            default:
                break;
        }
        return req;
    }

    private static String resolveSchemaName(Map<String, Object> body, BaseConnectionParam param) {
        Object schemaFromBody = body.get("schema_name");
        if (schemaFromBody != null && StringUtils.isNotBlank(schemaFromBody.toString())) {
            return schemaFromBody.toString();
        }
        return param.getSchemaName();
    }
}
