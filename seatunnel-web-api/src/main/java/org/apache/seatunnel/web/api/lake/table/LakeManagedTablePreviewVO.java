package org.apache.seatunnel.web.api.lake.table;

import lombok.Data;
import org.apache.seatunnel.web.api.lake.contract.TargetContract;

import java.util.ArrayList;
import java.util.List;

/** Server-generated preview, including DDL for display only. */
@Data
public class LakeManagedTablePreviewVO {

    private boolean valid;

    private String previewToken;

    private Long sourceDataSourceId;

    private String omEntityId;

    private Long odsDatabaseBindingId;

    private String targetTableName;

    private String sourceSchemaHash;

    private String targetContractHash;

    private TargetContract targetContract;

    private List<LakeManagedTableFieldMapping> fieldMappings = new ArrayList<>();

    private String ddl;

    private List<String> warnings = new ArrayList<>();

    private List<String> errors = new ArrayList<>();
}
