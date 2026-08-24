package com.asg.console.extension.constant;

/**
 * ASG-specific plugin name and configuration constants.
 *
 * <p>These used to be added to the upstream {@code BuiltInPluginName} and
 * {@code KeyAuthConfig} classes in the AISecGw-console fork. They now live in
 * the extension module so the upstream files keep zero diff.
 */
public final class AsgPluginConstants {

    private AsgPluginConstants() {
    }

    // ---- ASG built-in plugin names (additions over upstream BuiltInPluginName) ----

    public static final String AI_PII_GUARD = "ai-pii-guard";

    public static final String AI_PROMPT_GUARD = "ai-prompt-guard";

    public static final String SHADOW_AI_DETECT = "shadow-ai-detect";

    // ---- ASG key-auth configuration keys (additions over upstream KeyAuthConfig) ----

    /**
     * identify_only configuration: identification-only mode.
     * When true, the key-auth plugin identifies valid consumers and sets the
     * x-mse-consumer header but does not reject unauthenticated requests.
     * Used in shadow AI monitoring mode so ai-statistics can record consumer identity.
     */
    public static final String IDENTIFY_ONLY = "identify_only";
}
