package org.apache.seatunnel.plugin.datasource.api.analysis;

import lombok.extern.slf4j.Slf4j;
import org.apache.seatunnel.web.common.modal.JobDefinitionAnalysisResult;
import org.springframework.stereotype.Component;

import java.util.regex.Pattern;

@Slf4j
@Component
public class DefaultDatasourceJobDefinitionAnalyzer implements DatasourceJobDefinitionAnalyzer {

    @Override
    public String type() {
        return "DEFAULT";
    }

    private static final Pattern FROM_TABLE_PATTERN = Pattern.compile(
            "(?i)\\bfrom\\s+([`\"\\[]?[a-zA-Z0-9_.$-]+[`\"\\]]?)"
    );

    private static final Pattern INTO_TABLE_PATTERN = Pattern.compile(
            "(?i)\\binto\\s+([`\"\\[]?[a-zA-Z0-9_.$-]+[`\"\\]]?)"
    );

    private static final Pattern UPDATE_TABLE_PATTERN = Pattern.compile(
            "(?i)\\bupdate\\s+([`\"\\[]?[a-zA-Z0-9_.$-]+[`\"\\]]?)"
    );

    @Override
    public boolean supports(DatasourceAnalysisContext context) {
        return true;
    }

    @Override
    public JobDefinitionAnalysisResult analyze(DatasourceAnalysisContext context) {
        return JobDefinitionAnalysisResult.builder()
                .build();
    }


}
