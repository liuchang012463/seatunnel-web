package org.apache.seatunnel.web.api.security;

/**
 * Authentication modes supported by the web application boundary.
 *
 * <p>{@link #DEV_BYPASS} is the development mode used by the current
 * application. The other values reserve the configuration contract for the
 * legacy password flow and the future SSO adapter.</p>
 */
public enum AuthenticationMode {
    DEV_BYPASS,
    PASSWORD,
    SSO
}
