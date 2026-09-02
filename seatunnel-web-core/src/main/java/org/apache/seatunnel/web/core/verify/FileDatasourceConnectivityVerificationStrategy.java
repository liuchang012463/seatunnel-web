package org.apache.seatunnel.web.core.verify;

import org.apache.commons.lang3.StringUtils;
import org.apache.seatunnel.plugin.datasource.api.datasource.ConnectivityVerifier;
import org.apache.seatunnel.plugin.datasource.api.jdbc.DataSourceProcessor;
import org.apache.seatunnel.plugin.datasource.api.utils.DataSourceUtils;
import org.apache.seatunnel.web.core.verify.modal.DatasourceVerifyContext;
import org.apache.seatunnel.web.spi.bean.vo.ClientDatasourceVerifyItemVO;
import org.apache.seatunnel.web.spi.bean.vo.ClientDatasourceVerifyVO;
import org.apache.seatunnel.web.spi.datasource.ConnectionParam;
import org.apache.seatunnel.web.spi.enums.DbType;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * Verifies file-style datasources (FTP/SFTP/S3/MINIO/LOCAL_FILE) directly from
 * the web server via the datasource plugin connectivity verifiers.
 *
 * <p>File connectors do not run a SeaTunnel test job: the engine reads files from
 * the configured endpoint directly, so a plugin-level reachability check is
 * sufficient for the client link step.</p>
 */
@Component
public class FileDatasourceConnectivityVerificationStrategy
        implements DatasourceConnectivityVerificationStrategy {

    private static final List<String> SUPPORTED_DB_TYPES = Arrays.asList(
            "FTP", "SFTP", "S3", "MINIO", "LOCAL_FILE");

    @Override
    public boolean supports(DatasourceVerifyContext context) {
        return context != null
                && context.getDbType() != null
                && SUPPORTED_DB_TYPES.contains(context.getDbType().name().toUpperCase(Locale.ROOT));
    }

    @Override
    public ClientDatasourceVerifyVO verify(DatasourceVerifyContext context) {
        org.apache.seatunnel.web.dao.entity.DataSource datasource = context.getDatasource();
        String dbType = String.valueOf(context.getDbType());

        ClientDatasourceVerifyVO vo = new ClientDatasourceVerifyVO();
        vo.setSuccess(false);

        String target = describeTarget(datasource);
        try {
            ConnectionParam connectionParam = DataSourceUtils.buildConnectionParams(
                    context.getDbType(), datasource.getConnectionParams());
            DataSourceProcessor processor = DataSourceUtils.getDatasourceProcessor(context.getDbType());
            ConnectivityVerifier verifier = processor.getConnectivityVerifier();
            boolean connected = verifier.checkDataSourceConnectivity(connectionParam);

            if (connected) {
                vo.setSuccess(true);
                vo.setMessage(dbType + " 数据源连接成功");
                vo.addItem(ClientDatasourceVerifyItemVO.success(
                        "FILE_CONNECTIVITY", dbType + " 连通性",
                        "数据源可达" + (context.getDbType() == DbType.LOCAL_FILE ? "，本机目录可读取" : ""),
                        "数据源可访问",
                        target + " 连接成功"));
            } else {
                vo.setMessage(dbType + " 数据源连接失败");
                vo.addItem(ClientDatasourceVerifyItemVO.fail(
                        "FILE_CONNECTIVITY", dbType + " 连通性",
                        "数据源不可达或不可读",
                        "数据源可访问",
                        target + " 连接失败，请检查配置或服务状态"));
            }
        } catch (Exception e) {
            vo.setMessage(StringUtils.defaultIfBlank(e.getMessage(), dbType + " 数据源连接失败"));
            vo.addItem(ClientDatasourceVerifyItemVO.fail(
                    "FILE_CONNECTIVITY", dbType + " 连通性",
                    "连通性检查异常",
                    "数据源可访问",
                    StringUtils.defaultIfBlank(e.getMessage(), target + " 连接失败")));
        }
        return vo;
    }

    private String describeTarget(org.apache.seatunnel.web.dao.entity.DataSource datasource) {
        if (datasource == null) {
            return "数据源";
        }
        return StringUtils.defaultIfBlank(datasource.getName(), "数据源");
    }
}
