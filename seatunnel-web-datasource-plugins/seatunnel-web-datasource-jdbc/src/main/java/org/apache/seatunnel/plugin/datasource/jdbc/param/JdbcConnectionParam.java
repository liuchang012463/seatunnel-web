package org.apache.seatunnel.plugin.datasource.jdbc.param;

import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.ToString;
import org.apache.seatunnel.web.spi.datasource.BaseConnectionParam;
import org.apache.seatunnel.web.spi.form.FieldType;
import org.apache.seatunnel.web.spi.form.FormField;

@Data
@EqualsAndHashCode(callSuper = true)
@ToString(exclude = "password")
public class JdbcConnectionParam extends BaseConnectionParam {

    @FormField(
            label = "JDBC URL",
            required = true,
            order = 1,
            placeholder = "jdbc:vendor://host:port/database")
    protected String url;

    @FormField(
            label = "驱动类",
            required = true,
            order = 2,
            placeholder = "com.vendor.jdbc.Driver")
    protected String driver;

    @FormField(
            label = "驱动 Jar 包",
            required = true,
            order = 3,
            placeholder = "vendor-jdbc-driver.jar")
    protected String driverLocation;

    @FormField(label = "用户名", required = true, order = 4)
    protected String user;

    @FormField(label = "密码", required = true, type = FieldType.PASSWORD, order = 5)
    protected String password;

    @FormField(label = "数据库/目录", order = 6)
    protected String database;

    @FormField(label = "模式", order = 7)
    protected String schemaName;

    /* Hide the host/port fields inherited from BaseConnectionParam. */
    protected String host;
    protected String port;
}
