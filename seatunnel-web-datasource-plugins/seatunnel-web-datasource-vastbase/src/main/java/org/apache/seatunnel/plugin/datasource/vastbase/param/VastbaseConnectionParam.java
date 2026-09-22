package org.apache.seatunnel.plugin.datasource.vastbase.param;

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

/** Connection parameters for Vastbase G100. */
@Data
@EqualsAndHashCode(callSuper = true)
public class VastbaseConnectionParam extends BaseConnectionParam {

    @FormField(label = "端口号", defaultValue = "5432", required = true, order = 2,
            type = FieldType.NUMBER, placeholder = "Please enter the port")
    protected String port;

    @FormField(label = "模式", order = 4, required = false, defaultValue = "public",
            placeholder = "Please enter the schema name")
    protected String schemaName;

    @FormField(label = "驱动Jar包", order = 6,
            defaultValue = "Vastbase-G100-2.16_pg_2026062910.jar")
    protected String driverLocation;

    @FormField(label = "连接参数", order = 7, type = FieldType.CUSTOM_SELECT,
            defaultValue = "[{\"key\":\"ssl\",\"value\":\"false\"}]")
    protected List<KeyValuePair> other;

    public Map<String, String> getOtherAsMap() {
        Map<String, String> result = new HashMap<>();
        if (other != null) {
            for (KeyValuePair kv : other) {
                if (kv != null && kv.getKey() != null && kv.getValue() != null) {
                    result.put(kv.getKey(), kv.getValue());
                }
            }
        }
        return result;
    }

    public void setOtherFromMap(Map<String, String> map) {
        List<KeyValuePair> list = new ArrayList<>();
        if (MapUtils.isNotEmpty(map)) {
            map.forEach((key, value) -> list.add(new KeyValuePair(key, value)));
        }
        this.other = list;
    }

    @Override
    public String toString() {
        return "VastbaseConnectionParam{" +
                "user='" + user + '\'' +
                ", database='" + database + '\'' +
                ", schemaName='" + schemaName + '\'' +
                ", url='" + url + '\'' +
                ", driverLocation='" + driverLocation + '\'' +
                ", driver='" + driver + '\'' +
                ", dbType=" + dbType +
                '}';
    }
}
