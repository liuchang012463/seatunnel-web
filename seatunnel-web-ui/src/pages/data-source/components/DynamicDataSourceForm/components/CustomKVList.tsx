import { MinusCircleOutlined, PlusOutlined } from "@ant-design/icons";
import { Button, Form, Input } from "antd";

const isSensitiveCredentialKey = (key: unknown) => {
  if (typeof key !== "string") return false;
  const normalized = key.toLowerCase().replace(/[^a-z0-9]/g, "");
  if (normalized === "apikeyid") return false;
  return (
    normalized.includes("password") ||
    normalized.includes("passwd") ||
    normalized === "pwd" ||
    normalized.includes("secret") ||
    normalized.includes("token") ||
    normalized === "apikey" ||
    normalized === "apikeyencoded" ||
    normalized === "accesskey" ||
    normalized.includes("privatekey") ||
    normalized.includes("keystorepassword") ||
    normalized.includes("truststorepassword") ||
    normalized.includes("credential")
  );
};

export default function CustomKVList(props: {
  intl: any;
  field: any;
  configForm: any;
  configuredSecretFields?: string[];
  savedSecretFields?: string[];
}) {
  const { intl, field, configForm, configuredSecretFields = [], savedSecretFields = [] } = props;
  const otherValues = Form.useWatch(field.key, configForm) as Array<{ key?: string }> | undefined;

  return (
    <Form.Item label={field.label} style={{ marginBottom: 18 }}>
      <Form.List name={field.key}>
        {(fields, { add, remove }) => (
          <div style={{ width: "100%" }}>
            <div
              style={{
                display: "flex",
                flexDirection: "column",
                gap: 10,
              }}
            >
              {fields.map(({ key, name, ...restField }) => {
                const pairKey = otherValues?.[name]?.key ?? configForm.getFieldValue([field.key, name, "key"]);
                const fieldId = `${field.key}:${pairKey}`;
                const configuredSecret = configuredSecretFields.includes(fieldId);
                const savedSecret = savedSecretFields.includes(fieldId);
                const sensitiveValue = configuredSecret || savedSecret || isSensitiveCredentialKey(pairKey);
                const ValueInput = sensitiveValue ? Input.Password : Input;

                return (
                  <div
                    key={key}
                    style={{
                      display: "grid",
                      gridTemplateColumns: "1fr 1fr 28px",
                      gap: 10,
                      alignItems: "center",
                    }}
                  >
                    <Form.Item
                      {...restField}
                      name={[name, "key"]}
                      style={{ marginBottom: 0 }}
                      rules={[
                        {
                          required: true,
                          message: intl.formatMessage({
                            id: "pages.datasource.form.other.keyRequired",
                            defaultMessage: "key can not be null",
                          }),
                        },
                      ]}
                    >
                      <Input
                        placeholder={intl.formatMessage({
                          id: "pages.datasource.form.other.keyPlaceholder",
                          defaultMessage: "key",
                        })}
                      />
                    </Form.Item>

                    <Form.Item
                      {...restField}
                      name={[name, "value"]}
                      style={{ marginBottom: 0 }}
                      rules={configuredSecret || savedSecret ? [] : [
                        {
                          required: true,
                          message: intl.formatMessage({
                            id: "pages.datasource.form.other.valueRequired",
                            defaultMessage: "value can not be null",
                          }),
                        },
                      ]}
                    >
                      <ValueInput
                        placeholder={configuredSecret ? "已设置，留空保持不变" : intl.formatMessage({
                          id: "pages.datasource.form.other.valuePlaceholder",
                          defaultMessage: "value",
                        })}
                      />
                    </Form.Item>

                    <div
                      style={{
                        display: "flex",
                        justifyContent: "center",
                        alignItems: "center",
                        color: "var(--st-color-text-muted)",
                        cursor: "pointer",
                        fontSize: 16,
                      }}
                      onClick={() => remove(name)}
                    >
                      <MinusCircleOutlined />
                    </div>
                  </div>
                );
              })}
            </div>

            <Form.Item style={{ marginBottom: 0, marginTop: 12 }}>
              <Button
                type="dashed"
                onClick={() => add({ key: "", value: "" })}
                block
                icon={<PlusOutlined />}
                className="datasource-form-add-setting"
              >
                {intl.formatMessage({
                  id: "pages.datasource.form.other.addConnSetting",
                  defaultMessage: "Add Database Connection Settings",
                })}
              </Button>
            </Form.Item>
          </div>
        )}
      </Form.List>
    </Form.Item>
  );
}
