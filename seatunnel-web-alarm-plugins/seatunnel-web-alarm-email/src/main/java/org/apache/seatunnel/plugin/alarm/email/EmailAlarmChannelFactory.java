package org.apache.seatunnel.plugin.alarm.email;

import com.google.auto.service.AutoService;
import org.apache.seatunnel.plugin.alarm.api.AlarmChannel;
import org.apache.seatunnel.plugin.alarm.api.AlarmChannelFactory;
import org.apache.seatunnel.web.spi.form.FieldType;
import org.apache.seatunnel.web.spi.form.FormFieldConfig;
import org.apache.seatunnel.web.spi.form.Option;
import org.apache.seatunnel.web.spi.form.Rule;

import java.util.List;

/**
 * Factory for the SMTP email alarm channel.
 */
@AutoService(AlarmChannelFactory.class)
public class EmailAlarmChannelFactory implements AlarmChannelFactory {

    public static final String EMAIL = "EMAIL";

    @Override
    public String name() {
        return EMAIL;
    }

    @Override
    public String displayName() {
        return "邮件 (Email)";
    }

    @Override
    public AlarmChannel create() {
        return new EmailAlarmChannel();
    }

    @Override
    public List<FormFieldConfig> params() {
        return List.of(
                buildField("host", "SMTP 主机", FieldType.INPUT,
                        "smtp.example.com", null, 1, true),
                buildField("port", "SMTP 端口", FieldType.NUMBER,
                        "587", "587", 2, true),
                buildField("username", "SMTP 用户名", FieldType.INPUT,
                        "alert@example.com", null, 3, false),
                buildField("password", "SMTP 密码", FieldType.PASSWORD,
                        "请输入 SMTP 密码", null, 4, false),
                buildField("from", "发件人地址", FieldType.INPUT,
                        "alert@example.com", null, 5, false),
                buildField("to", "收件人地址", FieldType.TEXTAREA,
                        "ops@example.com, oncall@example.com", null, 6, true),
                buildField("subjectTemplate", "邮件主题模板", FieldType.INPUT,
                        "[SeaTunnel] ${title}", "[SeaTunnel] ${title}", 7, true),
                buildField("bodyTemplate", "邮件正文模板", FieldType.TEXTAREA,
                        "${content}\n\n日志：\n${log}",
                        "告警标题：${title}\n严重级别：${severity}\n\n${content}\n\n日志：\n${log}",
                        8, true),
                buildContentTypeField(),
                buildSwitchField("starttls", "启用 STARTTLS", true, 10),
                buildSwitchField("ssl", "启用 SSL", false, 11),
                buildField("timeoutMs", "超时 (ms)", FieldType.NUMBER,
                        "10000", "10000", 12, true)
        );
    }

    private FormFieldConfig buildContentTypeField() {
        FormFieldConfig field = buildField(
                "contentType",
                "正文类型",
                FieldType.SELECT,
                null,
                "text/plain",
                9,
                true);

        Option textOption = new Option();
        textOption.setLabel("纯文本 (text/plain)");
        textOption.setValue("text/plain");

        Option htmlOption = new Option();
        htmlOption.setLabel("HTML (text/html)");
        htmlOption.setValue("text/html");

        field.setOptions(List.of(textOption, htmlOption));
        return field;
    }

    private FormFieldConfig buildSwitchField(
            String key,
            String label,
            boolean defaultValue,
            int order) {
        FormFieldConfig field = buildField(
                key,
                label,
                FieldType.SWITCH,
                null,
                defaultValue,
                order,
                false);
        return field;
    }

    private FormFieldConfig buildField(
            String key,
            String label,
            FieldType type,
            String placeholder,
            Object defaultValue,
            int order,
            boolean required) {
        FormFieldConfig field = new FormFieldConfig();
        field.setKey(key);
        field.setLabel(label);
        field.setType(type);
        field.setPlaceholder(placeholder);
        field.setDefaultValue(defaultValue);
        field.setOrder(order);

        if (required) {
            Rule rule = new Rule();
            rule.setRequired(true);
            rule.setMessage(label + "不能为空");
            field.setRules(List.of(rule));
        }
        return field;
    }
}
