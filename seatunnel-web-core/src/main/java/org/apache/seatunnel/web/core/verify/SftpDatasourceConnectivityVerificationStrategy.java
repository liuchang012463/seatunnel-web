package org.apache.seatunnel.web.core.verify;

import org.apache.commons.lang3.StringUtils;
import org.apache.seatunnel.plugin.datasource.api.jdbc.DataSourceProcessor;
import org.apache.seatunnel.plugin.datasource.api.jdbc.JdbcConnectionProvider;
import org.apache.seatunnel.plugin.datasource.api.plugin.DataSourceProcessorProvider;
import org.apache.seatunnel.plugin.datasource.api.utils.DataSourceUtils;
import org.apache.seatunnel.web.core.verify.modal.DatasourceVerifyContext;
import org.apache.seatunnel.web.spi.bean.vo.ClientDatasourceVerifyItemVO;
import org.apache.seatunnel.web.spi.bean.vo.ClientDatasourceVerifyVO;
import org.apache.seatunnel.web.spi.datasource.BaseConnectionParam;
import org.apache.seatunnel.web.spi.enums.DbType;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

@Component
public class SftpDatasourceConnectivityVerificationStrategy
        implements DatasourceConnectivityVerificationStrategy {

    private static final Set<DbType> SUPPORTED = new HashSet<>(Arrays.asList(
            DbType.SFTP
    ));

    @Override
    public boolean supports(DatasourceVerifyContext context) {
        if (context == null || context.getDbType() == null) {
            return false;
        }
        return SUPPORTED.contains(context.getDbType());
    }

    @Override
    public ClientDatasourceVerifyVO verify(DatasourceVerifyContext context) {
        ClientDatasourceVerifyVO vo = new ClientDatasourceVerifyVO();
        vo.setSuccess(false);

        try {
            DataSourceProcessor processor = DataSourceProcessorProvider.getDataSourceProcessor(DbType.SFTP);
            if (processor == null) {
                vo.setSuccess(false);
                vo.setMessage("SFTP data source processor not found");
                vo.addItem(buildSftpItem(false, "SFTP data source processor not found"));
                return vo;
            }

            JdbcConnectionProvider connectionProvider = processor.getConnectionManager();
            if (connectionProvider == null) {
                vo.setSuccess(false);
                vo.setMessage("SFTP connection provider not found");
                vo.addItem(buildSftpItem(false, "SFTP connection provider not found"));
                return vo;
            }

            BaseConnectionParam connectionParam = DataSourceUtils.buildConnectionParams(
                    context.getDatasource().getDbType(),
                    context.getDatasource().getConnectionParams()
            );

            boolean connected = connectionProvider.checkDataSourceConnectivity(connectionParam);

            vo.setSuccess(connected);
            if (connected) {
                vo.setMessage("SFTP 连接成功");
                vo.addItem(buildSftpItem(true, "SFTP 连接成功"));
            } else {
                vo.setMessage("SFTP 连接失败");
                vo.addItem(buildSftpItem(false, "SFTP 连接失败"));
            }

        } catch (Exception e) {
            vo.setSuccess(false);
            vo.setMessage("SFTP 连接异常: " + e.getMessage());
            vo.addItem(buildSftpItem(false, "SFTP 连接异常: " + e.getMessage()));
        }

        return vo;
    }

    private ClientDatasourceVerifyItemVO buildSftpItem(boolean success, String errorMessage) {
        if (success) {
            return ClientDatasourceVerifyItemVO.success(
                    "SFTP_CONNECTIVITY",
                    "SFTP 连通性",
                    "SFTP 连接成功",
                    "SFTP 连接成功",
                    "可以连接到 SFTP 服务器"
            );
        }
        return ClientDatasourceVerifyItemVO.fail(
                "SFTP_CONNECTIVITY",
                "SFTP 连通性",
                StringUtils.defaultIfBlank(errorMessage, "SFTP 连接失败"),
                "SFTP 连接成功",
                "无法连接到 SFTP 服务器"
        );
    }
}
