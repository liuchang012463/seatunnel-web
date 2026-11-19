package org.apache.seatunnel.web.core.job.handler.multi;

import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.seatunnel.web.spi.bean.dto.config.GuideMultiJobContent;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Slf4j
@Component
public class GuideMultiJobValidator {

    public void validate(GuideMultiJobContent content) {
        if (content == null) {
            throw new IllegalArgumentException("content can not be null");
        }

        validateSource(content.getSource());
        validateTarget(content.getTarget());
        if (!isKafka(content.getSource().getDbType())
                && !isKafka(content.getTarget().getDbType())) {
            validateTableMatch(content.getTableMatch());
            validatePostgreSqlCdcTargetNames(content.getSource(), content.getTableMatch().getTables());
        }
    }

    private void validateSource(GuideMultiJobContent.WorkflowSourceConfig source) {
        if (source == null) {
            throw new IllegalArgumentException("source can not be null");
        }

        if (StringUtils.isBlank(source.getDatasourceId())) {
            throw new IllegalArgumentException("source.datasourceId can not be blank");
        }

        if (StringUtils.isBlank(source.getDbType())) {
            throw new IllegalArgumentException("source.dbType can not be blank");
        }

        if (StringUtils.isBlank(source.getPluginName())) {
            throw new IllegalArgumentException("source.pluginName can not be blank");
        }
    }

    private void validateTarget(GuideMultiJobContent.WorkflowTargetConfig target) {
        if (target == null) {
            throw new IllegalArgumentException("target can not be null");
        }

        if (StringUtils.isBlank(target.getDatasourceId())) {
            throw new IllegalArgumentException("target.datasourceId can not be blank");
        }

        if (StringUtils.isBlank(target.getDbType())) {
            throw new IllegalArgumentException("target.dbType can not be blank");
        }

        if (StringUtils.isBlank(target.getPluginName())) {
            throw new IllegalArgumentException("target.pluginName can not be blank");
        }
    }

    private void validateTableMatch(GuideMultiJobContent.TableMatchConfig tableMatch) {
        if (tableMatch == null) {
            throw new IllegalArgumentException("tableMatch can not be null");
        }

        if (StringUtils.isBlank(tableMatch.getMode())) {
            throw new IllegalArgumentException("tableMatch.mode can not be blank");
        }

        String mode = tableMatch.getMode();

        if (!"1".equals(mode) && !"2".equals(mode) && !"3".equals(mode) && !"4".equals(mode)) {
            throw new IllegalArgumentException("Unsupported tableMatch.mode: " + mode);
        }

        if ("1".equals(mode) || "4".equals(mode)) {
            if (CollectionUtils.isEmpty(tableMatch.getTables())) {
                throw new IllegalArgumentException("tableMatch.tables can not be empty");
            }
        }

        if ("2".equals(mode) || "3".equals(mode)) {
            if (StringUtils.isBlank(tableMatch.getKeyword())) {
                throw new IllegalArgumentException("tableMatch.keyword can not be blank");
            }
        }
    }

    private void validatePostgreSqlCdcTargetNames(
            GuideMultiJobContent.WorkflowSourceConfig source, List<String> sourceTables) {
        if (!"POSTGRESQL-CDC".equalsIgnoreCase(source.getPluginName()) || CollectionUtils.isEmpty(sourceTables)) {
            return;
        }

        // 整库向导把源表写入同一个目标命名空间，动态表名只使用源表的末段。
        Map<String, String> sourceByTargetName = new HashMap<>();
        for (String table : sourceTables) {
            if (StringUtils.isBlank(table)) {
                continue;
            }
            String sourcePath = table.trim();
            String targetName = sourcePath.substring(sourcePath.lastIndexOf('.') + 1);
            String previous = sourceByTargetName.putIfAbsent(targetName.toLowerCase(Locale.ROOT), sourcePath);
            if (previous != null && !schemaTablePath(previous).equals(schemaTablePath(sourcePath))) {
                log.warn("PostgreSQL CDC target name conflict, datasourceId={}, sourceTables=[{}, {}], targetTable={}",
                        source.getDatasourceId(), previous, sourcePath, targetName);
                throw new IllegalArgumentException(
                        "PostgreSQL CDC 源表 %s 和 %s 会写入同一个目标表 %s，请调整源表范围或分别同步到不同目标库或 Schema"
                                .formatted(previous, sourcePath, targetName));
            }
        }
    }

    private String schemaTablePath(String path) {
        // PostgreSQL CDC 每个数据源只连接一个库，接受 schema.table 和 database.schema.table 两种写法。
        int firstDot = path.indexOf('.');
        return firstDot == path.lastIndexOf('.') ? path : path.substring(firstDot + 1);
    }

    private boolean isKafka(String dbType) {
        return "KAFKA".equalsIgnoreCase(StringUtils.trimToEmpty(dbType));
    }
}
