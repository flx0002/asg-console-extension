package com.asg.console.extension.support;

import java.lang.reflect.Field;

import com.alibaba.higress.sdk.service.kubernetes.KubernetesClientService;

import io.kubernetes.client.openapi.ApiClient;

/**
 * Reflective accessor for the private {@code client} field of the upstream
 * {@link KubernetesClientService}.
 *
 * <p>ASG's audit log collector needs direct K8s API access (pod log reading).
 * Instead of patching the upstream class with a {@code getApiClient()} method
 * (fork divergence), the extension module reads the field reflectively so the
 * upstream file keeps zero diff. The field is assigned in the constructor and
 * the service is a plain object (no CGLIB proxy), so this is stable at runtime.
 */
public final class UpstreamApiClientAccessor {

    private static final Field CLIENT_FIELD = resolveClientField();

    private UpstreamApiClientAccessor() {
    }

    /**
     * Returns the ApiClient held by the given upstream KubernetesClientService.
     */
    public static ApiClient getApiClient(KubernetesClientService service) {
        try {
            return (ApiClient) CLIENT_FIELD.get(service);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException("Failed to access KubernetesClientService.client field", e);
        }
    }

    private static Field resolveClientField() {
        try {
            Field field = KubernetesClientService.class.getDeclaredField("client");
            field.setAccessible(true);
            return field;
        } catch (NoSuchFieldException e) {
            throw new IllegalStateException(
                "Upstream KubernetesClientService no longer has a 'client' field; "
                    + "re-check compatibility before upgrading higress-admin-sdk", e);
        }
    }
}
