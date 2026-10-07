package org.apache.seatunnel.web.api.utils;

import com.typesafe.config.*;

import java.util.*;
import java.util.regex.Pattern;

public final class HoconSensitiveMaskUtil {

    private static final String MASK = "******";

    /**
     * 已知敏感字段名。isSensitiveKey 还会匹配包含凭证词根的变体，例如 api_key_encoded。
     */
    private static final Set<String> SENSITIVE_KEYS = new HashSet<>(Arrays.asList(
            "password",
            "passwd",
            "pwd",
            "secret",
            "secretkey",
            "accesskey",
            "accesskeysecret",
            "token",
            "credential",
            "credentials",
            "authorization",
            "signature"
    ));

    private static final Pattern URL_USER_INFO = Pattern.compile("(?i)(://)[^/?#]+@");
    private static final Pattern URL_CREDENTIAL_PARAMETER = Pattern.compile(
            "(?i)([?&;,](?:[a-z0-9_.-]*(?:password|passwd|pwd|secret|token|credential"
                    + "|access[_-]?key|api[_-]?key|private[_-]?key|authorization|user(?:name)?|signature|key|auth)"
                    + "[a-z0-9_.-]*)=)(\\{[^}]*\\}|\"[^\"]*\"|'(?:''|[^'])*'|[^&#;,]*)");

    private static final String INVALID_HOCON_MASKED_MESSAGE =
            "# HOCON parsing failed; configuration values were fully masked.";

    /**
     * SeaTunnel HOCON 顶层固定顺序
     */
    private static final List<String> ROOT_ORDER = Arrays.asList(
            "env",
            "source",
            "transform",
            "sink"
    );

    private static final ConfigRenderOptions RENDER_OPTIONS = ConfigRenderOptions.defaults()
            .setComments(false)
            .setFormatted(true)
            .setJson(false)
            .setOriginComments(false);

    private HoconSensitiveMaskUtil() {
    }

    /**
     * 脱敏 HOCON 字符串中的敏感字段，并按固定顶层顺序输出
     */
    public static String maskSensitiveInfo(String hoconText) {
        if (hoconText == null || hoconText.trim().isEmpty()) {
            return hoconText;
        }

        try {
            Config config = ConfigFactory.parseString(hoconText);
            ConfigValue maskedRoot = maskValue(config.root());

            if (maskedRoot != null && maskedRoot.valueType() == ConfigValueType.OBJECT) {
                return renderRootInOrder((ConfigObject) maskedRoot);
            }

            return renderValue(maskedRoot);
        } catch (Exception e) {
            // 无法解析时无法识别任意自定义请求头等凭证，失败时隐藏整段配置。
            return INVALID_HOCON_MASKED_MESSAGE;
        }
    }

    /**
     * 递归脱敏
     */
    private static ConfigValue maskValue(ConfigValue value) {
        if (value == null) {
            return null;
        }

        switch (value.valueType()) {
            case OBJECT:
                return maskObject((ConfigObject) value);
            case LIST:
                return maskList((ConfigList) value);
            case STRING:
                String original = (String) value.unwrapped();
                String masked = maskInlineCredentials(original);
                return original.equals(masked) ? value : ConfigValueFactory.fromAnyRef(masked);
            default:
                return value;
        }
    }

    /**
     * 脱敏对象
     */
    private static ConfigValue maskObject(ConfigObject obj) {
        ConfigObject result = obj;

        for (Map.Entry<String, ConfigValue> entry : obj.entrySet()) {
            String key = entry.getKey();
            ConfigValue childValue = entry.getValue();

            if (isSensitiveContainerKey(key) || isSensitiveKey(key)) {
                result = result.withValue(key, ConfigValueFactory.fromAnyRef(MASK));
            } else {
                ConfigValue maskedChild = maskValue(childValue);
                if (maskedChild != childValue) {
                    result = result.withValue(key, maskedChild);
                }
            }
        }

        return result;
    }

    /**
     * 脱敏列表
     */
    private static ConfigValue maskList(ConfigList list) {
        List<Object> newList = new ArrayList<>(list.size());
        boolean changed = false;

        for (ConfigValue item : list) {
            ConfigValue maskedItem = maskValue(item);
            newList.add(maskedItem == null ? null : maskedItem.unwrapped());
            if (maskedItem != item) {
                changed = true;
            }
        }

        return changed ? ConfigValueFactory.fromIterable(newList) : list;
    }

    /**
     * 顶层按固定顺序渲染，避免 root.render() 打乱 env/source/transform/sink 顺序
     */
    private static String renderRootInOrder(ConfigObject root) {
        StringBuilder sb = new StringBuilder();
        Set<String> renderedKeys = new HashSet<>();

        // 先输出固定顺序的顶层字段
        for (String key : ROOT_ORDER) {
            if (root.containsKey(key)) {
                appendKeyValue(sb, key, root.get(key));
                renderedKeys.add(key);
            }
        }

        // 再输出剩余字段
        for (Map.Entry<String, ConfigValue> entry : root.entrySet()) {
            String key = entry.getKey();
            if (!renderedKeys.contains(key)) {
                appendKeyValue(sb, key, entry.getValue());
            }
        }

        return sb.toString().trim();
    }

    /**
     * 追加单个顶层 key-value
     * <p>
     * 关键点：
     * 不能直接 key + value.render()
     * 否则 object 渲染时可能省略最外层 {}，导致结构变成：
     * env job { ... }
     * parallelism=1
     * <p>
     * 正确做法：包装成单键 Config 再整体 render
     */
    private static void appendKeyValue(StringBuilder sb, String key, ConfigValue value) {
        if (sb.length() > 0) {
            sb.append("\n");
        }

        Config single = ConfigFactory.empty().withValue(key, value);
        sb.append(single.root().render(RENDER_OPTIONS).trim());
        sb.append("\n");
    }

    /**
     * 渲染单个 ConfigValue
     */
    private static String renderValue(ConfigValue value) {
        return value == null ? "null" : value.render(RENDER_OPTIONS);
    }

    /**
     * 是否敏感字段：忽略大小写
     */
    private static boolean isSensitiveKey(String key) {
        if (key == null) {
            return false;
        }
        String normalized = normalizeKey(key);
        if (SENSITIVE_KEYS.contains(normalized)) {
            return true;
        }
        return normalized.contains("password")
                || normalized.contains("passwd")
                || normalized.contains("secret")
                || normalized.contains("credential")
                || normalized.contains("apikey")
                || normalized.contains("accesskey")
                || normalized.contains("privatekey")
                || normalized.contains("jaas")
                || normalized.contains("signature")
                || normalized.endsWith("authorization")
                || normalized.endsWith("token");
    }

    private static String maskInlineCredentials(String value) {
        String masked = URL_USER_INFO.matcher(value).replaceAll("$1" + MASK + "@");
        masked = maskOracleThinCredentials(masked);
        return URL_CREDENTIAL_PARAMETER.matcher(masked).replaceAll("$1" + MASK);
    }

    private static String maskOracleThinCredentials(String value) {
        String prefix = "jdbc:oracle:thin:";
        int prefixStart = value.toLowerCase(Locale.ROOT).indexOf(prefix);
        if (prefixStart < 0) {
            return value;
        }
        int credentialStart = prefixStart + prefix.length();
        int passwordSeparator = value.indexOf('/', credentialStart);
        int credentialsEnd = value.lastIndexOf('@');
        if (passwordSeparator <= credentialStart || credentialsEnd <= passwordSeparator + 1) {
            return value;
        }
        return value.substring(0, credentialStart) + MASK + value.substring(credentialsEnd);
    }

    /**
     * 任意 HTTP 请求头都可能携带认证信息，因此隐藏整个 headers 对象，包括自定义头。
     */
    private static boolean isSensitiveContainerKey(String key) {
        return key != null && normalizeKey(key).endsWith("headers");
    }

    /**
     * key 标准化并忽略大小写及分隔符，兼容 camelCase、snake_case、kebab-case 和点分路径。
     */
    private static String normalizeKey(String key) {
        return key.trim().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }
}
