package org.apache.seatunnel.plugin.datasource.localfile.param;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Getter;
import lombok.Setter;
import org.apache.seatunnel.web.spi.datasource.ConnectionParam;
import org.apache.seatunnel.web.spi.enums.DbType;
import org.apache.seatunnel.web.spi.form.FieldType;
import org.apache.seatunnel.web.spi.form.FormField;

@Getter
@Setter
@JsonInclude(JsonInclude.Include.NON_NULL)
public class LocalFileConnectionParam implements ConnectionParam {

    @FormField(
            label = "根目录",
            required = true,
            order = 1,
            defaultValue = "/data/seatunnel/localfile",
            placeholder = "/data/seatunnel/localfile",
            description = "SeaTunnel 引擎与 Web 服务都能访问的本地目录，上传的文件保存在该目录下")
    private String basePath;

    @FormField(
            label = "描述",
            required = false,
            type = FieldType.TEXTAREA,
            order = 20)
    private String remark;

    private DbType dbType;

    @Override
    public String toString() {
        return getClass().getSimpleName() + "{basePath='" + basePath + "', dbType=" + dbType + "}";
    }
}
