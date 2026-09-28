package org.apache.seatunnel.web.core.verify;

import org.apache.seatunnel.web.core.verify.modal.DatasourceVerifyContext;
import org.apache.seatunnel.web.spi.bean.vo.ClientDatasourceVerifyVO;

/**
 * Strategy interface for verifying datasource connectivity from a specific client.
 */
public interface DatasourceConnectivityVerificationStrategy {

    /**
     * Whether this strategy supports the given datasource type.
     */
    boolean supports(DatasourceVerifyContext context);

    /**
     * Verify connectivity between the client and datasource.
     *
     * @return verification result
     */
    ClientDatasourceVerifyVO verify(DatasourceVerifyContext context);
}
