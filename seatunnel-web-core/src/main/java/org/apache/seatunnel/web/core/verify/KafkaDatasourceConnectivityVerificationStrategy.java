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

@Component
public class KafkaDatasourceConnectivityVerificationStrategy implements DatasourceConnectivityVerificationStrategy {

    @Resource private ConnectivityTestJobFactory connectivityTestJobFactory;
    @Resource private SeaTunnelTestJobExecutor seaTunnelTestJobExecutor;
    @Resource private ConnectivityVerifyResultAssembler resultAssembler;

    @Override
    public boolean supports(DatasourceVerifyContext context) {
        return context != null && context.getDbType() == DbType.KAFKA;
    }

    @Override
    public ClientDatasourceVerifyVO verify(DatasourceVerifyContext context) {
        ConnectivityTestJob testJob = connectivityTestJobFactory.build(
                context.getClient(), context.getDatasource(), context.getTopic());
        JobExecutionResult execution = seaTunnelTestJobExecutor.executeAndWait(
                context.getClient(), testJob, context.getTimeoutMs(), context.getPollIntervalMs());
        ClientDatasourceVerifyVO vo = resultAssembler.toVO(
                context.getClient(), context.getDatasource(), testJob, execution);
        if (vo.getItems() == null || vo.getItems().isEmpty()) {
            boolean success = Boolean.TRUE.equals(vo.getSuccess());
            vo.addItem(success
                    ? ClientDatasourceVerifyItemVO.success(
                            "KAFKA_HOCON_CONNECTIVITY", "Kafka Engine 连通性",
                            "SeaTunnel Kafka Source 测试任务执行成功", "任务成功", "Engine 可访问该 Topic")
                    : ClientDatasourceVerifyItemVO.fail(
                            "KAFKA_HOCON_CONNECTIVITY", "Kafka Engine 连通性",
                            StringUtils.defaultIfBlank(vo.getErrorMessage(), vo.getMessage()),
                            "任务成功", "Engine 无法访问该 Topic"));
        }
        return vo;
    }
}
