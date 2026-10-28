package org.apache.seatunnel.web.api.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.apache.seatunnel.plugin.datasource.api.jdbc.DataSourceProcessor;
import org.apache.seatunnel.plugin.datasource.api.utils.DataSourceUtils;
import org.apache.seatunnel.web.dao.entity.DataSource;
import org.apache.seatunnel.web.spi.bean.vo.DataSourceVO;
import org.apache.seatunnel.web.spi.enums.DbType;
import org.apache.seatunnel.web.spi.form.FieldType;
import org.apache.seatunnel.web.spi.form.FormFieldConfig;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;

/** Credential-aware handling for the saved datasource JSON payloads. */
public final class DataSourceSecretSupport {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String OTHER_FIELD = "other";
    private static final Set<String> KNOWN_SECRET_FIELD_NAMES = Set.of(
            "password", "passwd", "pwd", "secret", "secretKey", "apiKey", "apiKeyEncoded",
            "tlsKeystorePassword", "tlsTruststorePassword", "keystorePassword", "truststorePassword",
            "token", "clientSecret", "privateKey", "accessKeySecret");

    private DataSourceSecretSupport() {
    }

    /**
     * Merges omitted or blank credentials from the saved row into the edited
     * JSON. All non-secret values continue to come from the current request.
     */
    public static String mergeSecrets(
            DbType dbType,
            String editedJson,
            String savedOriginalJson,
            String savedConnectionParams) {
        ObjectNode edited = readObject(editedJson);
        if (edited == null) {
            throw new IllegalArgumentException("Datasource connection parameters must be a JSON object");
        }

        ObjectNode savedOriginal = readObject(savedOriginalJson);
        ObjectNode savedConnection = readObject(savedConnectionParams);
        Set<String> secretNames = secretFieldNames(dbType);

        mergeObjectStorageCredentials(dbType, edited, savedOriginal, savedConnection);
        mergeDirectSecrets(edited, savedOriginal, savedConnection, secretNames);
        mergeOtherSecrets(edited, savedOriginal, savedConnection, secretNames);
        removeObjectStorageAliases(dbType, edited);
        return writeJson(edited);
    }

    /** Redacts saved credentials while retaining all editable non-secret settings. */
    public static void redact(DataSource dataSource) {
        if (dataSource == null) {
            return;
        }
        if (Boolean.TRUE.equals(dataSource.getSystemManaged())) {
            // Lake-managed projections have an intentionally stricter response contract.
            dataSource.setConnectionParams(null);
            dataSource.setOriginalJson(null);
            dataSource.setConfiguredSecretFields(null);
            dataSource.setSavedSecretFields(null);
            return;
        }

        Set<String> configured = configuredSecretFields(
                dataSource.getDbType(), dataSource.getOriginalJson(), dataSource.getConnectionParams());
        dataSource.setConfiguredSecretFields(new ArrayList<>(configured));
        dataSource.setSavedSecretFields(new ArrayList<>(savedSecretFields(
                dataSource.getDbType(), dataSource.getOriginalJson(), dataSource.getConnectionParams())));
        dataSource.setConnectionParams(redactJson(dataSource.getDbType(), dataSource.getConnectionParams()));
        dataSource.setOriginalJson(redactJson(dataSource.getDbType(), dataSource.getOriginalJson()));
    }

    /** Redacts a list/detail projection with the same rules as the entity response. */
    public static void redact(DataSourceVO dataSource) {
        if (dataSource == null) {
            return;
        }
        if (Boolean.TRUE.equals(dataSource.getSystemManaged())) {
            dataSource.setConnectionParams(null);
            dataSource.setOriginalJson(null);
            dataSource.setConfiguredSecretFields(null);
            dataSource.setSavedSecretFields(null);
            return;
        }

        Set<String> configured = configuredSecretFields(
                dataSource.getDbType(), dataSource.getOriginalJson(), dataSource.getConnectionParams());
        dataSource.setConfiguredSecretFields(new ArrayList<>(configured));
        dataSource.setSavedSecretFields(new ArrayList<>(savedSecretFields(
                dataSource.getDbType(), dataSource.getOriginalJson(), dataSource.getConnectionParams())));
        dataSource.setConnectionParams(redactJson(dataSource.getDbType(), dataSource.getConnectionParams()));
        dataSource.setOriginalJson(redactJson(dataSource.getDbType(), dataSource.getOriginalJson()));
    }

    /** Returns the field identifiers the editor can use to show configured-but-empty secrets. */
    public static Set<String> configuredSecretFields(DbType dbType, String... jsonSources) {
        Set<String> secretNames = secretFieldNames(dbType);
        Set<String> configured = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        if (jsonSources == null) {
            return configured;
        }
        for (String source : jsonSources) {
            ObjectNode node = readObject(source);
            if (node != null) {
                collectConfiguredSecrets(node, secretNames, configured);
                addObjectStorageAliases(node, dbType, configured, true);
            }
        }
        if (isObjectStorage(dbType)) {
            canonicalizeObjectStorageAlias(configured, "user", "accessKey");
            canonicalizeObjectStorageAlias(configured, "password", "secretKey");
        }
        return configured;
    }

    /** Returns password fields present in persisted form, even when they were intentionally empty. */
    public static Set<String> savedSecretFields(DbType dbType, String... jsonSources) {
        Set<String> secretNames = secretFieldNames(dbType);
        Set<String> saved = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        if (jsonSources != null) {
            for (String source : jsonSources) {
                ObjectNode node = readObject(source);
                if (node != null) {
                    collectSavedSecrets(node, secretNames, saved);
                    addObjectStorageAliases(node, dbType, saved, false);
                }
            }
        }
        if (isObjectStorage(dbType)) {
            canonicalizeObjectStorageAlias(saved, "user", "accessKey");
            canonicalizeObjectStorageAlias(saved, "password", "secretKey");
        }
        return saved;
    }

    /** Redacts any plugin-declared password field and credential-like key/value pair. */
    public static String redactJson(DbType dbType, String json) {
        if (json == null || json.isBlank()) {
            return json;
        }
        ObjectNode root = readObject(json);
        if (root == null) {
            // Fail closed for malformed persisted JSON instead of echoing a possible credential.
            return null;
        }
        redactNode(root, secretFieldNames(dbType));
        if (isObjectStorage(dbType)) {
            root.remove("user");
        }
        return writeJson(root);
    }

    private static void mergeObjectStorageCredentials(
            DbType dbType,
            ObjectNode edited,
            JsonNode savedOriginal,
            JsonNode savedConnection) {
        if (!isObjectStorage(dbType)) {
            return;
        }
        mergeObjectStorageCredential(edited, "accessKey", "user", savedOriginal, savedConnection);
        mergeObjectStorageCredential(edited, "secretKey", "password", savedOriginal, savedConnection);
    }

    private static void mergeObjectStorageCredential(
            ObjectNode edited,
            String canonicalName,
            String aliasName,
            JsonNode savedOriginal,
            JsonNode savedConnection) {
        JsonNode canonicalValue = findMatchingField(edited, canonicalName);
        if (hasSecretValue(canonicalValue)) {
            return;
        }

        JsonNode aliasValue = findMatchingField(edited, aliasName);
        if (hasSecretValue(aliasValue)) {
            edited.set(canonicalName, aliasValue.deepCopy());
            return;
        }

        JsonNode savedValue = firstExistingField(savedOriginal, savedConnection, canonicalName, Set.of());
        if (!hasSecretValue(savedValue)) {
            savedValue = firstExistingField(savedOriginal, savedConnection, aliasName, Set.of());
        }
        if (hasSecretValue(savedValue)) {
            edited.set(canonicalName, savedValue.deepCopy());
        }
    }

    private static void removeObjectStorageAliases(DbType dbType, ObjectNode edited) {
        if (isObjectStorage(dbType)) {
            // ObjectStorageConnectionParam serializes these aliases from accessKey/secretKey.
            // Keeping an old alias beside a newly edited canonical field can make Jackson
            // apply setUser/setPassword after the new value and silently restore the old key.
            edited.remove("user");
            edited.remove("password");
        }
    }

    private static void canonicalizeObjectStorageAlias(Set<String> configured, String alias, String canonical) {
        String match = configured.stream()
                .filter(name -> normalize(name).equals(normalize(alias)))
                .findFirst()
                .orElse(null);
        if (match != null) {
            configured.remove(match);
            configured.add(canonical);
        }
    }

    private static void addObjectStorageAliases(
            JsonNode node,
            DbType dbType,
            Set<String> fields,
            boolean requireValue) {
        if (!isObjectStorage(dbType) || node == null || !node.isObject()) {
            return;
        }
        addObjectStorageAlias(node, "user", "accessKey", fields, requireValue);
        addObjectStorageAlias(node, "password", "secretKey", fields, requireValue);
    }

    private static void addObjectStorageAlias(
            JsonNode node,
            String alias,
            String canonical,
            Set<String> fields,
            boolean requireValue) {
        JsonNode value = node.get(alias);
        if (value != null && (!requireValue || hasSecretValue(value))) {
            fields.add(canonical);
        }
    }

    private static boolean isObjectStorage(DbType dbType) {
        return dbType == DbType.S3 || dbType == DbType.MINIO;
    }

    private static Set<String> secretFieldNames(DbType dbType) {
        Set<String> names = new LinkedHashSet<>();
        names.addAll(KNOWN_SECRET_FIELD_NAMES);
        if (dbType == null) {
            return names;
        }
        try {
            DataSourceProcessor processor = DataSourceUtils.getDatasourceProcessor(dbType);
            for (FormFieldConfig field : processor.generateFormFields()) {
                if (field != null && field.getType() == FieldType.PASSWORD && hasText(field.getKey())) {
                    names.add(field.getKey());
                }
            }
        } catch (RuntimeException ignored) {
            // The alias matcher below still protects persisted credentials when a plugin is absent.
        }
        return names;
    }

    private static void collectConfiguredSecrets(
            JsonNode node,
            Set<String> secretNames,
            Set<String> configured) {
        if (node.isObject()) {
            node.fields().forEachRemaining(entry -> {
                String key = entry.getKey();
                JsonNode value = entry.getValue();
                if (isSecretName(key, secretNames) && hasSecretValue(value)) {
                    configured.add(canonicalFieldName(key, secretNames));
                }
                if (OTHER_FIELD.equalsIgnoreCase(key)) {
                    collectConfiguredOtherSecrets(value, secretNames, configured);
                }
                collectConfiguredSecrets(value, secretNames, configured);
            });
        } else if (node.isArray()) {
            for (JsonNode child : node) {
                collectConfiguredSecrets(child, secretNames, configured);
            }
        }
    }

    private static void collectSavedSecrets(JsonNode node, Set<String> secretNames, Set<String> saved) {
        if (node.isObject()) {
            node.fields().forEachRemaining(entry -> {
                String key = entry.getKey();
                JsonNode value = entry.getValue();
                if (isSecretName(key, secretNames) && value != null) {
                    saved.add(canonicalFieldName(key, secretNames));
                }
                if (OTHER_FIELD.equalsIgnoreCase(key)) {
                    collectSavedOtherSecrets(value, secretNames, saved);
                }
                collectSavedSecrets(value, secretNames, saved);
            });
        } else if (node.isArray()) {
            for (JsonNode child : node) {
                collectSavedSecrets(child, secretNames, saved);
            }
        }
    }

    private static void collectSavedOtherSecrets(JsonNode other, Set<String> secretNames, Set<String> saved) {
        if (other.isArray()) {
            for (JsonNode pair : other) {
                if (pair.isObject()) {
                    JsonNode key = pair.get("key");
                    if (key != null && isSecretName(key.asText(), secretNames) && pair.has("value")) {
                        saved.add("other:" + key.asText());
                    }
                }
            }
        } else if (other.isObject()) {
            other.fieldNames().forEachRemaining(key -> {
                if (isSecretName(key, secretNames)) {
                    saved.add("other:" + key);
                }
            });
        }
    }

    private static void collectConfiguredOtherSecrets(
            JsonNode other,
            Set<String> secretNames,
            Set<String> configured) {
        if (other.isArray()) {
            for (JsonNode pair : other) {
                if (pair.isObject()) {
                    JsonNode key = pair.get("key");
                    JsonNode value = pair.get("value");
                    if (key != null && isSecretName(key.asText(), secretNames) && hasSecretValue(value)) {
                        configured.add("other:" + key.asText());
                    }
                }
            }
        } else if (other.isObject()) {
            other.fields().forEachRemaining(entry -> {
                if (isSecretName(entry.getKey(), secretNames) && hasSecretValue(entry.getValue())) {
                    configured.add("other:" + entry.getKey());
                }
            });
        }
    }

    private static void redactNode(JsonNode node, Set<String> secretNames) {
        if (node.isObject()) {
            ObjectNode object = (ObjectNode) node;
            List<String> fields = new ArrayList<>();
            object.fieldNames().forEachRemaining(fields::add);
            for (String key : fields) {
                JsonNode value = object.get(key);
                if (isSecretName(key, secretNames)) {
                    object.remove(key);
                    continue;
                }
                if (OTHER_FIELD.equalsIgnoreCase(key)) {
                    redactOtherSecrets(value, secretNames);
                }
                redactNode(value, secretNames);
            }
        } else if (node.isArray()) {
            for (JsonNode child : node) {
                redactNode(child, secretNames);
            }
        }
    }

    private static void redactOtherSecrets(JsonNode other, Set<String> secretNames) {
        if (other.isArray()) {
            for (JsonNode pair : other) {
                if (pair.isObject()) {
                    JsonNode key = pair.get("key");
                    if (key != null && isSecretName(key.asText(), secretNames)) {
                        ((ObjectNode) pair).remove("value");
                    }
                }
            }
        } else if (other.isObject()) {
            ObjectNode values = (ObjectNode) other;
            List<String> keys = new ArrayList<>();
            values.fieldNames().forEachRemaining(keys::add);
            for (String key : keys) {
                if (isSecretName(key, secretNames)) {
                    values.remove(key);
                }
            }
        }
    }

    private static void mergeDirectSecrets(
            ObjectNode edited,
            JsonNode savedOriginal,
            JsonNode savedConnection,
            Set<String> secretNames) {
        for (String secretName : secretNames) {
            JsonNode editedValue = findMatchingField(edited, secretName);
            if (hasSecretValue(editedValue)) {
                continue;
            }
            JsonNode savedValue = firstExistingField(savedOriginal, savedConnection, secretName, secretNames);
            if (hasSecretValue(savedValue)) {
                edited.set(secretName, savedValue.deepCopy());
            }
        }
        mergeNestedDirectSecrets(edited, savedOriginal, savedConnection, secretNames);
    }

    private static void mergeNestedDirectSecrets(
            ObjectNode edited,
            JsonNode savedOriginal,
            JsonNode savedConnection,
            Set<String> secretNames) {
        List<String> fields = new ArrayList<>();
        edited.fieldNames().forEachRemaining(fields::add);
        for (String field : fields) {
            JsonNode editedValue = edited.get(field);
            if (!editedValue.isObject()) {
                continue;
            }
            ObjectNode editedObject = (ObjectNode) editedValue;
            for (String secretName : secretNames) {
                JsonNode value = findMatchingField(editedObject, secretName);
                if (hasSecretValue(value)) {
                    continue;
                }
                JsonNode oldValue = firstExistingNestedField(
                        savedOriginal, savedConnection, field, secretName, secretNames);
                if (hasSecretValue(oldValue)) {
                    editedObject.set(secretName, oldValue.deepCopy());
                }
            }
            mergeNestedDirectSecrets(editedObject, savedOriginal == null ? null : savedOriginal.get(field),
                    savedConnection == null ? null : savedConnection.get(field), secretNames);
        }
    }

    private static void mergeOtherSecrets(
            ObjectNode edited,
            JsonNode savedOriginal,
            JsonNode savedConnection,
            Set<String> secretNames) {
        JsonNode editedOther = edited.get(OTHER_FIELD);
        List<JsonNode> savedPairs = collectSecretPairs(savedOriginal, secretNames);
        savedPairs.addAll(collectSecretPairs(savedConnection, secretNames));

        if (editedOther == null || editedOther.isNull()) {
            if (!savedPairs.isEmpty()) {
                ArrayNode restored = MAPPER.createArrayNode();
                Set<String> seenKeys = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
                for (JsonNode pair : savedPairs) {
                    JsonNode key = pair.get("key");
                    if (hasValuePair(pair) && key != null && seenKeys.add(normalize(key.asText()))) {
                        restored.add(pair.deepCopy());
                    }
                }
                edited.set(OTHER_FIELD, restored);
            }
            return;
        }

        if (editedOther.isArray()) {
            for (JsonNode pair : editedOther) {
                if (!pair.isObject()) {
                    continue;
                }
                JsonNode keyNode = pair.get("key");
                JsonNode value = pair.get("value");
                if (keyNode == null || !isSecretName(keyNode.asText(), secretNames) || hasSecretValue(value)) {
                    continue;
                }
                JsonNode savedValue = savedPairValue(savedPairs, keyNode.asText());
                if (hasSecretValue(savedValue)) {
                    ((ObjectNode) pair).set("value", savedValue.deepCopy());
                }
            }
        } else if (editedOther.isObject()) {
            ObjectNode otherMap = (ObjectNode) editedOther;
            List<String> keys = new ArrayList<>();
            otherMap.fieldNames().forEachRemaining(keys::add);
            for (String key : keys) {
                JsonNode value = otherMap.get(key);
                if (isSecretName(key, secretNames) && !hasSecretValue(value)) {
                    JsonNode savedValue = savedPairValue(savedPairs, key);
                    if (hasSecretValue(savedValue)) {
                        otherMap.set(key, savedValue.deepCopy());
                    }
                }
            }
        }
    }

    private static List<JsonNode> collectSecretPairs(JsonNode root, Set<String> secretNames) {
        List<JsonNode> pairs = new ArrayList<>();
        collectSecretPairs(root, secretNames, pairs);
        return pairs;
    }

    private static void collectSecretPairs(JsonNode node, Set<String> secretNames, List<JsonNode> pairs) {
        if (node == null) {
            return;
        }
        if (node.isObject()) {
            node.fields().forEachRemaining(entry -> {
                if (OTHER_FIELD.equalsIgnoreCase(entry.getKey())) {
                    JsonNode other = entry.getValue();
                    if (other.isArray()) {
                        for (JsonNode pair : other) {
                            JsonNode key = pair.get("key");
                            if (key != null && isSecretName(key.asText(), secretNames)
                                    && hasSecretValue(pair.get("value"))) {
                                pairs.add(pair.deepCopy());
                            }
                        }
                    } else if (other.isObject()) {
                        other.fields().forEachRemaining(item -> {
                            if (isSecretName(item.getKey(), secretNames) && hasSecretValue(item.getValue())) {
                                ObjectNode pair = MAPPER.createObjectNode();
                                pair.put("key", item.getKey());
                                pair.set("value", item.getValue().deepCopy());
                                pairs.add(pair);
                            }
                        });
                    }
                }
                collectSecretPairs(entry.getValue(), secretNames, pairs);
            });
        } else if (node.isArray()) {
            node.forEach(child -> collectSecretPairs(child, secretNames, pairs));
        }
    }

    private static boolean hasValuePair(JsonNode pair) {
        return pair != null && hasSecretValue(pair.get("value"));
    }

    private static JsonNode savedPairValue(Collection<JsonNode> pairs, String key) {
        String normalizedKey = normalize(key);
        for (JsonNode pair : pairs) {
            JsonNode oldKey = pair.get("key");
            if (oldKey != null && normalize(oldKey.asText()).equals(normalizedKey)) {
                JsonNode value = pair.get("value");
                if (hasSecretValue(value)) {
                    return value;
                }
            }
        }
        return null;
    }

    private static JsonNode firstExistingField(
            JsonNode original,
            JsonNode connection,
            String name,
            Set<String> secretNames) {
        JsonNode value = findMatchingField(original, name);
        if (hasSecretValue(value)) {
            return value;
        }
        value = findMatchingField(connection, name);
        if (hasSecretValue(value)) {
            return value;
        }
        return null;
    }

    private static JsonNode firstExistingNestedField(
            JsonNode original,
            JsonNode connection,
            String parent,
            String name,
            Set<String> secretNames) {
        JsonNode originalParent = original == null ? null : original.get(parent);
        JsonNode connectionParent = connection == null ? null : connection.get(parent);
        JsonNode value = findMatchingField(originalParent, name);
        if (hasSecretValue(value)) {
            return value;
        }
        value = findMatchingField(connectionParent, name);
        if (hasSecretValue(value)) {
            return value;
        }
        return firstExistingField(originalParent, connectionParent, name, secretNames);
    }

    private static JsonNode findMatchingField(JsonNode node, String name) {
        if (node == null || !node.isObject()) {
            return null;
        }
        String normalizedName = normalize(name);
        var fields = node.fields();
        while (fields.hasNext()) {
            var entry = fields.next();
            if (normalize(entry.getKey()).equals(normalizedName)) {
                return entry.getValue();
            }
        }
        return null;
    }

    private static boolean isSecretName(String key, Set<String> secretNames) {
        if (!hasText(key)) {
            return false;
        }
        String normalized = normalize(key);
        if (secretNames.stream().anyMatch(name -> normalize(name).equals(normalized))) {
            return true;
        }
        return normalized.contains("password")
                || normalized.contains("passwd")
                || normalized.equals("pwd")
                || normalized.contains("secret")
                || normalized.contains("token")
                || normalized.equals("apikey")
                || normalized.equals("apikeyencoded")
                || normalized.equals("accesskey")
                || normalized.contains("accesskeysecret")
                || normalized.contains("privatekey")
                || normalized.contains("keystorepassword")
                || normalized.contains("truststorepassword")
                || normalized.contains("credential");
    }

    private static String canonicalFieldName(String key, Set<String> secretNames) {
        return secretNames.stream()
                .filter(name -> normalize(name).equals(normalize(key)))
                .min(Comparator.comparing(String::toLowerCase))
                .orElse(key);
    }

    private static String normalize(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }

    private static boolean hasSecretValue(JsonNode value) {
        return value != null && !value.isNull() && (!value.isTextual() || !value.asText().isEmpty());
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private static ObjectNode readObject(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            JsonNode node = MAPPER.readTree(json);
            return node != null && node.isObject() ? (ObjectNode) node : null;
        } catch (Exception ignored) {
            return null;
        }
    }

    private static String writeJson(JsonNode node) {
        try {
            return MAPPER.writeValueAsString(node);
        } catch (Exception ignored) {
            return null;
        }
    }
}
