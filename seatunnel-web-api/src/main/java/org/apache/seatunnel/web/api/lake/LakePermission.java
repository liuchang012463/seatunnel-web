package org.apache.seatunnel.web.api.lake;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Permission names consumed by the existing authorization integration.
 *
 * <p>This class is only a vocabulary.  It intentionally does not introduce a
 * second RBAC implementation or infer permissions from catalog scope.</p>
 */
public final class LakePermission {

    public static final String VIEW = "lake:view";
    public static final String PHYSICAL_MANAGE = "lake:physical:manage";
    public static final String LIFECYCLE_MANAGE = "lake:lifecycle:manage";
    public static final String LOGICAL_MANAGE = "lake:logical:manage";
    public static final String LOGICAL_QUERY = "lake:logical:query";

    private static final Set<String> ALL;

    static {
        LinkedHashSet<String> permissions = new LinkedHashSet<>();
        permissions.add(VIEW);
        permissions.add(PHYSICAL_MANAGE);
        permissions.add(LIFECYCLE_MANAGE);
        permissions.add(LOGICAL_MANAGE);
        permissions.add(LOGICAL_QUERY);
        ALL = Collections.unmodifiableSet(permissions);
    }

    private LakePermission() {
    }

    public static Set<String> all() {
        return ALL;
    }

    public static boolean isKnown(String permission) {
        return permission != null && ALL.contains(permission);
    }
}
