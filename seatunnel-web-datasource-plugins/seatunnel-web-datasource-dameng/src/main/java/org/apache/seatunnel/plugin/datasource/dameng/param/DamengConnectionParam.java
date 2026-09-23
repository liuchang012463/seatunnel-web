package org.apache.seatunnel.plugin.datasource.dameng.param;

import lombok.Data;
import lombok.EqualsAndHashCode;
import org.apache.commons.collections4.MapUtils;
import org.apache.seatunnel.web.common.KeyValuePair;
import org.apache.seatunnel.web.spi.datasource.BaseConnectionParam;
import org.apache.seatunnel.web.spi.form.FieldType;
import org.apache.seatunnel.web.spi.form.FormField;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Dameng connection parameters.
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class DamengConnectionParam extends BaseConnectionParam {
    /**
     * Dameng JDBC connects to an instance, while SeaTunnel still needs the
     * physical database name as the catalog part of a table path.  Keep this
     * field local to the Dameng form so the UI does not present the generic
     * JDBC "database" label without that distinction.
     */
    @FormField(
            label = "数据库实例",
            required = true,
            order = 3,
            description = "填写 V$DATABASE.NAME，例如 DAMENG；不要填写表 Owner"
    )
    protected String database;

    /**
     * Database Port
     */
    @FormField(label = "端口号", defaultValue = "5236", required = true, order = 2, type = FieldType.NUMBER, placeholder = "Please enter the port")
    protected String port;

    /**
     * Schema Name
     */
    @FormField(
            label = "模式/Owner",
            order = 4,
            required = false,
            defaultValue = "SYSDBA",
            placeholder = "例如 XXTX",
            description = "填写表的 Schema/Owner；登录用户 SYSDBA 不等于表 Owner"
    )
    protected String schemaName;

    @FormField(
            label = "驱动Jar包",
            order = 6,
            defaultValue = "DmJdbcDriver18-8.1.2.141.jar"
    )
    protected String driverLocation;

    /**
     * Connection parameters
     */
    @FormField(label = "连接参数", order = 7, type = FieldType.CUSTOM_SELECT,
            defaultValue = "[{\"key\":\"ssl\",\"value\":\"false\"}]")
    protected List<KeyValuePair> other;

    /**
     * Convert other parameters to Map
     */
    public Map<String, String> getOtherAsMap() {
        Map<String, String> result = new HashMap<>();
        if (other != null && !other.isEmpty()) {
            for (KeyValuePair kv : other) {
                if (kv != null && kv.getKey() != null && kv.getValue() != null) {
                    result.put(kv.getKey(), kv.getValue());
                }
            }
        }
        return result;
    }

    /**
     * Set other parameters from Map
     */
    public void setOtherFromMap(Map<String, String> map) {
        List<KeyValuePair> list = new ArrayList<>();
        if (MapUtils.isNotEmpty(map)) {
            map.forEach((k, v) -> list.add(new KeyValuePair(k, v)));
        }
        this.other = list;
    }

    @Override
    public String toString() {
        return "DamengConnectionParam{" +
                "user='" + user + '\'' +
                ", password='" + password + '\'' +
                ", database='" + database + '\'' +
                ", schemaName='" + schemaName + '\'' +
                ", url='" + url + '\'' +
                ", driverLocation='" + driverLocation + '\'' +
                ", driver='" + driver + '\'' +
                ", dbType=" + dbType +
                '}';
    }
}
