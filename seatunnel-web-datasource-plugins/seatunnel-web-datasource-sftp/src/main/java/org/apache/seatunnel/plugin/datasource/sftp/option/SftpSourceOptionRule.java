package org.apache.seatunnel.plugin.datasource.sftp.option;

import com.google.auto.service.AutoService;
import org.apache.seatunnel.plugin.datasource.api.jdbc.AbstractSourceOptionRule;
import org.apache.seatunnel.plugin.datasource.api.jdbc.SourceOptionRule;
import org.apache.seatunnel.web.common.config.OptionRule;
import org.apache.seatunnel.web.common.config.Options;

@AutoService(SourceOptionRule.class)
public class SftpSourceOptionRule extends AbstractSourceOptionRule {

    @Override
    public String pluginName() {
        return "SFTP";
    }

    @Override
    public OptionRule sourceOptionRule() {
        return OptionRule.builder()
                .required(
                        Options.key("host").stringType().noDefaultValue().withDescription("SFTP host address"),
                        Options.key("port").intType().defaultValue(22).withDescription("SFTP port"),
                        Options.key("user").stringType().noDefaultValue().withDescription("SFTP username"),
                        Options.key("password").stringType().noDefaultValue().withDescription("SFTP password"),
                        Options.key("path").stringType().noDefaultValue().withDescription("File path or directory"),
                        Options.key("file_format_type").stringType().noDefaultValue().withDescription("File format type")
                )
                .optional(
                        Options.key("field_delimiter").stringType().defaultValue(",").withDescription("Field delimiter for text/csv"),
                        Options.key("csv_use_header_line").booleanType().defaultValue(false).withDescription("CSV has header row")
                )
                .build();
    }
}
