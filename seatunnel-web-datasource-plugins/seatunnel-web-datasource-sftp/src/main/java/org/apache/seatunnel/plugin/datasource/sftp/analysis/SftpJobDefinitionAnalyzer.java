package org.apache.seatunnel.plugin.datasource.sftp.analysis;

import com.typesafe.config.Config;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.apache.seatunnel.plugin.datasource.api.analysis.DatasourceAnalysisContext;
import org.apache.seatunnel.plugin.datasource.api.analysis.DatasourceAnalysisRole;
import org.apache.seatunnel.plugin.datasource.api.analysis.JobDefinitionAnalyzer;
import org.apache.seatunnel.web.common.enums.JobDefinitionMode;
import org.apache.seatunnel.web.common.modal.JobDefinitionAnalysisResult;
import org.apache.seatunnel.web.spi.enums.DbType;

import java.util.Collections;
import java.util.List;

@Slf4j
public class SftpJobDefinitionAnalyzer implements JobDefinitionAnalyzer {

    @Override
    public boolean supports(DatasourceAnalysisContext context) {
        return context != null && context.getDbType() == DbType.SFTP;
    }

    @Override
    public JobDefinitionAnalysisResult analyze(DatasourceAnalysisContext context) {
        if (context == null) {
            return JobDefinitionAnalysisResult.builder().build();
        }

        if (context.getMode() == JobDefinitionMode.GUIDE_MULTI) {
            return buildResult(
                    context.getRole(),
                    context.getDatasourceId(),
                    resolveMultiTables(context)
            );
        }

        String table = context.getRole() == DatasourceAnalysisRole.SOURCE
                ? resolveSourceTable(context)
                : resolveSinkTable(context);

        return buildResult(
                context.getRole(),
                context.getDatasourceId(),
                table
        );
    }

    private JobDefinitionAnalysisResult buildResult(DatasourceAnalysisRole role,
                                                    Long datasourceId,
                                                    String table) {
        if (role == DatasourceAnalysisRole.SOURCE) {
            return JobDefinitionAnalysisResult.builder()
                    .sourceType(DbType.SFTP.name())
                    .sourceDatasourceId(datasourceId)
                    .sourceTable(StringUtils.trimToEmpty(table))
                    .build();
        }

        return JobDefinitionAnalysisResult.builder()
                .sinkType(DbType.SFTP.name())
                .sinkDatasourceId(datasourceId)
                .sinkTable(StringUtils.trimToEmpty(table))
                .build();
    }

    private String resolveSourceTable(DatasourceAnalysisContext context) {
        return firstNonBlank(
                safeGetString(context.getPluginConfig(), "filePath"),
                safeGetString(context.getPluginConfig(), "path"),
                safeGetString(context.getPluginConfig(), "table"),
                safeGetString(context.getPluginConfig(), "table_path"),
                safeGetString(context.getPluginConfig(), "table_name")
        );
    }

    private String resolveSinkTable(DatasourceAnalysisContext context) {
        return firstNonBlank(
                safeGetString(context.getPluginConfig(), "filePath"),
                safeGetString(context.getPluginConfig(), "path"),
                safeGetString(context.getPluginConfig(), "targetTableName"),
                safeGetString(context.getPluginConfig(), "table"),
                safeGetString(context.getPluginConfig(), "table_path")
        );
    }

    private String resolveMultiTables(DatasourceAnalysisContext context) {
        List<String> result = safeGetStringList(context.getPluginConfig(), "table_list");
        result.addAll(safeGetStringList(context.getPluginConfig(), "tableList"));
        return String.join(",", result);
    }

    private String safeGetString(Config config, String path) {
        try {
            if (config != null && config.hasPath(path)) {
                return StringUtils.trimToEmpty(config.getString(path));
            }
        } catch (Exception e) {
            log.debug("Read SFTP job definition config failed, path={}", path, e);
        }
        return "";
    }

    private List<String> safeGetStringList(Config config, String path) {
        try {
            if (config != null && config.hasPath(path)) {
                return config.getStringList(path);
            }
        } catch (Exception e) {
            log.debug("Read SFTP job definition table list failed, path={}", path, e);
        }
        return Collections.emptyList();
    }

    private String firstNonBlank(String... values) {
        if (values == null) {
            return "";
        }
        for (String value : values) {
            if (StringUtils.isNotBlank(value)) {
                return value.trim();
            }
        }
        return "";
    }
}
