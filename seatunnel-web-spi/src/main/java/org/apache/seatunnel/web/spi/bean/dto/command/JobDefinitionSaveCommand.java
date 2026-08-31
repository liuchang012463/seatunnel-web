package org.apache.seatunnel.web.spi.bean.dto.command;


import org.apache.seatunnel.web.common.enums.JobDefinitionMode;
import org.apache.seatunnel.web.spi.bean.dto.config.JobBasicConfig;
import org.apache.seatunnel.web.spi.bean.dto.config.JobEnvConfig;
import org.apache.seatunnel.web.spi.enums.JobRuntimeType;

public interface JobDefinitionSaveCommand {

    Long getId();

    JobDefinitionMode getMode();

    JobRuntimeType getRuntimeType();

    JobBasicConfig getBasic();

    JobEnvConfig getEnv();

    /**
     * Server-side lake bridge selection.  Structured lake jobs identify the
     * target ODS database by binding id; the binding is resolved by the
     * server when the HOCON is built.  A default keeps legacy script/file
     * commands source compatible and makes the field optional for ordinary
     * jobs.
     */
    default Long getOdsDatabaseBindingId() {
        return null;
    }

    /**
     * Allows edit/copy resolvers to restore the binding from the durable job
     * relation without coupling every legacy command implementation to the
     * lake module.
     */
    default void setOdsDatabaseBindingId(Long odsDatabaseBindingId) {
        // Legacy commands do not carry lake state.
    }
}
