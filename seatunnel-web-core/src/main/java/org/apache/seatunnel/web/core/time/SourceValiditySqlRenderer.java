package org.apache.seatunnel.web.core.time;

import com.typesafe.config.Config;
import com.typesafe.config.ConfigValueFactory;
import lombok.extern.slf4j.Slf4j;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.Parenthesis;
import net.sf.jsqlparser.expression.operators.conditional.AndExpression;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.statement.select.PlainSelect;
import org.apache.seatunnel.plugin.datasource.api.hocon.DataSourceHoconBuilder;
import org.apache.seatunnel.web.spi.bean.dto.TimeVariableRenderReq;
import org.springframework.stereotype.Service;

/** Optional JDBC time predicate, applied after existing SQL variable rendering. */
@Slf4j
@Service
public class SourceValiditySqlRenderer {
    private final TimeVariableRenderService variables;

    public SourceValiditySqlRenderer(TimeVariableRenderService variables) {
        this.variables = variables;
    }

    public Config apply(Config source, Config node, DataSourceHoconBuilder dialect) {
        if (!node.hasPath("dataValidity.enabled") || !node.getBoolean("dataValidity.enabled")) {
            return source;
        }
        String plugin = node.hasPath("pluginName") ? node.getString("pluginName") : "";
        if (!plugin.toUpperCase(java.util.Locale.ROOT).startsWith("JDBC")) {
            throw new IllegalArgumentException("按时间筛选目前只支持 JDBC 读取，请在来源节点关闭该选项");
        }
        String field = node.hasPath("dataValidity.fieldName") ? node.getString("dataValidity.fieldName").trim() : "";
        String name = node.hasPath("dataValidity.timeVariable") ? node.getString("dataValidity.timeVariable").trim() : "";
        if (!field.matches("[\\p{L}_][\\p{L}\\p{N}_]*(?:\\.[\\p{L}_][\\p{L}\\p{N}_]*)*")
                || !name.matches("[A-Za-z][A-Za-z0-9_]*")) {
            throw new IllegalArgumentException("请选择有效的时间字段和时间变量");
        }
        var variable = variables.getAllEnabledVariables().stream()
                .filter(item -> name.equals(item.getParamName())).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("所选时间变量不存在或已停用，请重新选择"));
        TimeVariableRenderReq request = new TimeVariableRenderReq();
        request.setContent("${var:" + name + "}");
        var rendered = variables.render(request);
        if (rendered == null || rendered.getUnresolvedVariables() == null
                || !rendered.getUnresolvedVariables().isEmpty() || rendered.getVariables() == null) {
            throw new IllegalArgumentException("时间变量无法解析，请检查变量配置");
        }
        String value = rendered.getVariables().stream().filter(item -> name.equals(item.getName()))
                .map(item -> item.getValue()).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("时间变量没有返回可用值"));
        String literal = dialect.renderTimeLiteral(value, variable.getTimeFormat());
        String query = source.hasPath("query") ? source.getString("query") : null;
        if (query == null || query.isBlank()) {
            if (!source.hasPath("table_path")) {
                throw new IllegalArgumentException("请先选择来源表或填写查询 SQL");
            }
            query = "SELECT * FROM " + source.getString("table_path");
        }
        String existing = source.hasPath("where_condition") ? source.getString("where_condition") : null;
        String result = append(query, existing, field + " >= " + literal);
        log.info("Applied JDBC source validity filter field={} timeVariable={}", field, name);
        return source.withValue("query", ConfigValueFactory.fromAnyRef(result))
                .withoutPath("table_path").withoutPath("where_condition");
    }

    /** AST insertion keeps OR groups and places the predicate before ORDER/LIMIT. */
    static String append(String sql, String existing, String predicate) {
        try {
            var statements = CCJSqlParserUtil.parseStatements(sql).getStatements();
            if (statements.size() != 1 || !(statements.get(0) instanceof PlainSelect select)) {
                throw new IllegalArgumentException("按时间筛选支持单条普通 SELECT；复杂 SQL 请手动填写时间条件");
            }
            Expression where = select.getWhere();
            if (existing != null && !existing.isBlank()) {
                String condition = existing.trim().replaceFirst("(?i)^where\\b", "").trim();
                Expression extra = CCJSqlParserUtil.parseCondExpression(condition);
                where = where == null ? extra : new AndExpression(new Parenthesis(where), new Parenthesis(extra));
            }
            Expression filter = CCJSqlParserUtil.parseCondExpression(predicate);
            select.setWhere(where == null ? filter : new AndExpression(new Parenthesis(where), filter));
            return select.toString();
        } catch (IllegalArgumentException error) {
            throw error;
        } catch (Exception error) {
            // Parser messages can contain source SQL: return only an actionable message.
            throw new IllegalArgumentException("查询无法自动添加时间条件，请检查 SQL 或关闭按时间筛选");
        }
    }
}
