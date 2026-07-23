package org.apache.seatunnel.plugin.datasource.sftp.param;

import com.google.auto.service.AutoService;
import lombok.extern.slf4j.Slf4j;
import org.apache.seatunnel.plugin.datasource.api.analysis.JobDefinitionAnalyzer;
import org.apache.seatunnel.plugin.datasource.api.hocon.DataSourceHoconBuilder;
import org.apache.seatunnel.plugin.datasource.api.hocon.DataSourceHoconBuilderFactory;
import org.apache.seatunnel.plugin.datasource.api.jdbc.AbstractDataSourceProcessor;
import org.apache.seatunnel.plugin.datasource.api.jdbc.DataSourceProcessor;
import org.apache.seatunnel.plugin.datasource.api.jdbc.JdbcCatalog;
import org.apache.seatunnel.plugin.datasource.api.jdbc.JdbcConnectionProvider;
import org.apache.seatunnel.plugin.datasource.api.jdbc.JdbcParamConverter;
import org.apache.seatunnel.plugin.datasource.sftp.analysis.SftpJobDefinitionAnalyzer;
import org.apache.seatunnel.plugin.datasource.sftp.connection.SftpConnectionProvider;
import org.apache.seatunnel.web.common.config.OptionRule;
import org.apache.seatunnel.web.common.config.Options;
import org.apache.seatunnel.web.spi.datasource.BaseConnectionParam;
import org.apache.seatunnel.web.spi.enums.DbType;
import org.apache.seatunnel.web.spi.form.FormFieldConfig;

import java.util.List;
import java.util.Set;

@AutoService(DataSourceProcessor.class)
@Slf4j
public class SftpDataSourceProcessor extends AbstractDataSourceProcessor {

    private static final Set<String> CONNECTION_FORM_FIELDS = Set.of(
            "host",
            "port",
            "user",
            "password",
            "strictHostKeyChecking",
            "knownHostsPath");

    private final JdbcConnectionProvider connectionProvider = new SftpConnectionProvider();
    private final JdbcParamConverter paramConverter = new SftpParamConverter();
    private final JobDefinitionAnalyzer jobDefinitionAnalyzer = new SftpJobDefinitionAnalyzer();

    @Override
    public DataSourceHoconBuilder getQueryBuilder(String pluginName) {
        return DataSourceHoconBuilderFactory.getBuilder(pluginName);
    }

    @Override
    public JdbcConnectionProvider getConnectionManager() {
        return connectionProvider;
    }

    @Override
    public JdbcParamConverter getParamConverter() {
        return paramConverter;
    }

    @Override
    public JdbcCatalog getMetadataService(BaseConnectionParam connectionParam) {
        return null;
    }

    @Override
    public DbType getDbType() {
        return DbType.SFTP;
    }

    @Override
    public JobDefinitionAnalyzer getJobDefinitionAnalyzer() {
        return jobDefinitionAnalyzer;
    }

    @Override
    public OptionRule sinkOptionRule() {
        return OptionRule.builder()
                .required(
                        Options.key("host").stringType().noDefaultValue().withDescription("SFTP host address"),
                        Options.key("port").intType().defaultValue(22).withDescription("SFTP port"),
                        Options.key("user").stringType().noDefaultValue().withDescription("SFTP username"),
                        Options.key("password").stringType().noDefaultValue().withDescription("SFTP password"),
                        Options.key("path").stringType().noDefaultValue().withDescription("File path or directory"),
                        Options.key("file_format_type").stringType().noDefaultValue().withDescription("File format type"))
                .optional(
                        Options.key("file_name_expression")
                                .stringType()
                                .defaultValue("${transactionId}_${now}")
                                .withDescription("File name expression"),
                        Options.key("behavior_when_file_exists")
                                .stringType()
                                .defaultValue("DEFAULT")
                                .withDescription("Behavior when file exists"),
                        Options.key("field_delimiter")
                                .stringType()
                                .defaultValue(",")
                                .withDescription("Field delimiter for text/csv"))
                .build();
    }

    @Override
    public DataSourceProcessor create() {
        return new SftpDataSourceProcessor();
    }

    @Override
    public List<FormFieldConfig> generateFormFields() {
        List<FormFieldConfig> fields = super.generateFormFields().stream()
                .filter(field -> CONNECTION_FORM_FIELDS.contains(field.getKey()))
                .toList();

        fields.forEach(field -> {
            switch (field.getKey()) {
                case "host" -> {
                    field.setLabel("SFTP 主机");
                    field.setPlaceholder("例如：sftp.example.com");
                }
                case "port" -> {
                    field.setLabel("端口");
                    field.setPlaceholder("22");
                    field.setDefaultValue("22");
                }
                case "user" -> {
                    field.setLabel("用户名");
                    field.setPlaceholder("SFTP 用户名");
                }
                case "password" -> field.setLabel("密码");
                default -> {
                    // Labels for the SFTP-specific fields come from their annotations.
                }
            }
        });
        return fields;
    }
}
