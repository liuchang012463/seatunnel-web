import { MinusCircleOutlined, PlusOutlined } from "@ant-design/icons";
import { Button, Form, Input } from "antd";

export default function CustomKVList(props: { intl: any; field: any }) {
  const { intl, field } = props;
  const label = /[：:]$/.test(String(field.label ?? '')) ? field.label : `${field.label}：`;

  return (
    <Form.Item label={label} className="datasource-custom-kv-field" style={{ marginBottom: 18 }}>
      <Form.List name={field.key}>
        {(fields, { add, remove }) => (
          <div className="datasource-custom-kv-list" style={{ width: "100%" }}>
            <div
              className="datasource-custom-kv-rows"
              style={{
                display: "flex",
                flexDirection: "column",
                gap: 10,
              }}
            >
              {fields.map(({ key, name, ...restField }) => (
                <div
                  key={key}
                  className="datasource-custom-kv-row"
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
                    rules={[
                      {
                        required: true,
                        message: intl.formatMessage({
                          id: "pages.datasource.form.other.valueRequired",
                          defaultMessage: "value can not be null",
                        }),
                      },
                    ]}
                  >
                    <Input
                      placeholder={intl.formatMessage({
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
              ))}
            </div>

            <Form.Item className="datasource-custom-kv-add" style={{ marginBottom: 0, marginTop: 12 }}>
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
