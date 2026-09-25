package org.apache.seatunnel.web.core.verify;

import jakarta.annotation.Resource;
import org.apache.commons.lang3.StringUtils;
import org.apache.seatunnel.web.core.verify.executor.JobExecutionResult;
import org.apache.seatunnel.web.core.verify.executor.SeaTunnelTestJobExecutor;
import org.apache.seatunnel.web.core.verify.job.ConnectivityTestJob;
import org.apache.seatunnel.web.core.verify.job.ConnectivityTestJobFactory;
import org.apache.seatunnel.web.core.verify.modal.DatasourceVerifyContext;
import org.apache.seatunnel.web.core.verify.support.ConnectivityVerifyResultAssembler;
import org.apache.seatunnel.web.spi.bean.vo.ClientDatasourceVerifyItemVO;
import org.apache.seatunnel.web.spi.bean.vo.ClientDatasourceVerifyVO;
import org.apache.seatunnel.web.spi.enums.DbType;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

@Component
public class JdbcDatasourceConnectivityVerificationStrategy
        implements DatasourceConnectivityVerificationStrategy {

    private static final Set<DbType> SUPPORTED = new HashSet<>(Arrays.asList(
            DbType.JDBC,
            DbType.MYSQL,
            DbType.POSTGRE_SQL,
            DbType.ZEONEDB,
            DbType.ORACLE,
            DbType.DORIS,
            DbType.KINGBASE,
            DbType.DAMENG,
            DbType.VASTBASE
    ));

    @Resource
    private ConnectivityTestJobFactory connectivityTestJobFactory;

    @Resource
    private SeaTunnelTestJobExecutor seaTunnelTestJobExecutor;

    @Resource
    private ConnectivityVerifyResultAssembler connectivityVerifyResultAssembler;

    @Override
    public boolean supports(DatasourceVerifyContext context) {
        if (context == null || context.getDbType() == null) {
            return false;
        }

        return SUPPORTED.contains(context.getDbType())
                && !isCdcPlugin(context.getPluginName());
    }

    @Override
    public ClientDatasourceVerifyVO verify(DatasourceVerifyContext context) {
        return doVerify(context);
    }

    /**
     * 给 CDC 策略复用。
     *
     * CDC 场景也需要先确认客户端能通过 SeaTunnel 访问该数据源。
     */
    public ClientDatasourceVerifyVO doVerify(DatasourceVerifyContext context) {
        ConnectivityTestJob testJob = connectivityTestJobFactory.build(
                context.getClient(),
                context.getDatasource(),
                context.getRole(),
                context.getScope(),
                context.getTopic()
        );

        JobExecutionResult executionResult = seaTunnelTestJobExecutor.executeAndWait(
                context.getClient(),
                testJob,
                context.getTimeoutMs(),
                context.getPollIntervalMs()
        );

        ClientDatasourceVerifyVO vo = connectivityVerifyResultAssembler.toVO(
                context.getClient(),
                context.getDatasource(),
                testJob,
                executionResult
        );

        if (vo.getItems() == null || vo.getItems().isEmpty()) {
            vo.addItem(buildJdbcItem(vo));
        }

        return vo;
    }

    private ClientDatasourceVerifyItemVO buildJdbcItem(ClientDatasourceVerifyVO vo) {
        boolean success = Boolean.TRUE.equals(vo.getSuccess());

        if (success) {
            return ClientDatasourceVerifyItemVO.success(
                    "JDBC_DATABASE_ACCESS",
                    "客户端 JDBC/数据库访问",
                    "SeaTunnel 客户端 JDBC/数据库访问验证成功",
                    "SeaTunnel 客户端 JDBC/数据库访问验证成功",
                    "客户端可以通过 SeaTunnel 访问该数据源；该结果不代表已执行真实 Stream Load 写入"
            );
        }

        return ClientDatasourceVerifyItemVO.fail(
                "JDBC_DATABASE_ACCESS",
                "客户端 JDBC/数据库访问",
                StringUtils.defaultIfBlank(vo.getErrorMessage(), vo.getMessage()),
                "SeaTunnel 客户端 JDBC/数据库访问验证成功",
                "客户端无法通过 SeaTunnel 访问该数据源"
        );
    }

    private boolean isCdcPlugin(String pluginName) {
        return StringUtils.containsIgnoreCase(pluginName, "CDC");
    }
}
