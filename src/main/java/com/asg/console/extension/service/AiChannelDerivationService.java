/*
 * Copyright (c) 2026 WntASG Team
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package com.asg.console.extension.service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;

import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;

import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;

import com.alibaba.higress.sdk.model.PaginatedResult;
import com.alibaba.higress.sdk.model.WasmPluginInstance;
import com.alibaba.higress.sdk.model.WasmPluginInstanceScope;
import com.alibaba.higress.sdk.model.ai.AiModelPredicate;
import com.alibaba.higress.sdk.model.ai.AiRoute;
import com.alibaba.higress.sdk.model.route.RoutePredicate;
import com.alibaba.higress.sdk.service.WasmPluginInstanceService;
import com.alibaba.higress.sdk.service.ai.AiRouteService;
import com.asg.console.extension.constant.AsgPluginConstants;
import com.asg.console.extension.model.AiChannelChangeEvent;
import com.asg.console.extension.model.AiShadowDnsPolicy;

import io.kubernetes.client.informer.ResourceEventHandler;
import io.kubernetes.client.informer.SharedIndexInformer;
import io.kubernetes.client.informer.SharedInformerFactory;
import io.kubernetes.client.openapi.ApiClient;
import io.kubernetes.client.openapi.ApiException;
import io.kubernetes.client.openapi.apis.CoreV1Api;
import io.kubernetes.client.openapi.apis.NetworkingV1Api;
import io.kubernetes.client.openapi.models.V1Ingress;
import io.kubernetes.client.openapi.models.V1IngressList;
import io.kubernetes.client.openapi.models.V1IngressRule;
import io.kubernetes.client.openapi.models.V1Service;
import io.kubernetes.client.openapi.models.V1ServiceList;
import io.kubernetes.client.util.CallGeneratorParams;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

/**
 * Derives the "registered AI channel" lists that {@code ai-shadow-detect} needs in order to tell a
 * legitimate call apart from a shadow one.
 *
 * <p>Every list here is <b>derived from an existing entity</b> (AI Route, authorized domain,
 * Ingress, cluster Service). Nothing is accepted as free-form input: a hand-maintained whitelist is
 * exactly what {@code trusted_hosts} was, and it silently drifted until it matched nothing.
 *
 * <p>Products:
 * <ul>
 *   <li>{@code ai_route_models} / {@code ai_route_model_exacts} — model header values of AI Routes,
 *       split by predicate type so the gateway replays the same match semantics (prefix vs exact)</li>
 *   <li>{@code authorized_proxy_hosts} — Ingress hosts already covered by an authorized domain</li>
 *   <li>unregistered egress / orphan authorized domain — reported for inspection, not pushed</li>
 * </ul>
 */
@Slf4j
public class AiChannelDerivationService {

    /** Plugin config key: model values matched with prefix semantics (higress "PRE" predicate). */
    public static final String KEY_AI_ROUTE_MODELS = "ai_route_models";

    /** Plugin config key: model values matched with exact semantics (higress "EQUAL" predicate). */
    public static final String KEY_AI_ROUTE_MODEL_EXACTS = "ai_route_model_exacts";

    /** Plugin config key: Host values of registered reverse-proxy channels. */
    public static final String KEY_AUTHORIZED_PROXY_HOSTS = "authorized_proxy_hosts";

    private static final String INGRESS_NS = "higress-system";
    private static final String ANN_DESTINATION = "higress.io/destination";
    private static final String ANN_INTERNAL = "higress.io/internal";
    private static final String SVC_FQDN_SUFFIX = ".svc.cluster.local";
    private static final String DNS_SUFFIX = ".dns";
    private static final String INTERNAL_SUFFIX = ".internal";
    private static final String AI_ROUTE_PREFIX = "ai-route-";

    /**
     * Minimum gap between two derivations.
     *
     * <p>The first trigger runs immediately, so a domain authorization takes effect without a
     * visible delay; this window then absorbs whatever arrives while that run is in flight — a
     * batch approving five domains, a helm upgrade rewriting several Ingresses — into at most one
     * follow-up run instead of one run per change.
     */
    private static final long DEBOUNCE_MILLIS = 1500L;

    /**
     * Delay before re-running a derivation that failed.
     *
     * <p>A failed push must not wait for the next unrelated Ingress or authorization change: one
     * write conflict would otherwise leave the gateway on stale lists until something else happens,
     * which looks identical to "the feature silently stopped working". Retries are self-driven
     * (each failure schedules the next attempt), so an idle system performs no work at all.
     */
    private static final long RETRY_DELAY_SECONDS = 15L;

    /** Cache of cluster service names, refreshed on each derivation (derivation is low-frequency). */
    private final ApiClient apiClient;
    private final CoreV1Api coreV1Api;
    private final NetworkingV1Api networkingV1Api;
    private final WasmPluginInstanceService wasmPluginInstanceService;
    private final AiRouteService aiRouteService;
    private final AiShadowDnsPolicyService dnsPolicyService;

    /** One derivation at a time; the trigger sources only ever set a flag and return. */
    private final ScheduledExecutorService executor =
        Executors.newScheduledThreadPool(1, runnable -> {
            Thread thread = new Thread(runnable, "ai-channel-derivation");
            thread.setDaemon(true);
            return thread;
        });

    /**
     * Informer threads, kept apart from {@link #executor} so a blocked watch never holds up a
     * derivation. Daemon threads because an informer must never keep the JVM alive.
     */
    private final ExecutorService informerExecutor = Executors.newCachedThreadPool(runnable -> {
        Thread thread = new Thread(runnable, "ai-channel-ingress-watch");
        thread.setDaemon(true);
        return thread;
    });

    /** Set by every trigger, cleared before a run. Two flags give "coalesce, never drop". */
    private final AtomicBoolean dirty = new AtomicBoolean(false);
    private final AtomicBoolean workerRunning = new AtomicBoolean(false);

    /**
     * Live view of Ingresses, maintained by the informer. Null when the watcher could not start, in
     * which case {@link #collectIngresses()} falls back to a direct list.
     */
    private volatile SharedIndexInformer<V1Ingress> ingressInformer;

    /** Held only so {@link #stopIngressWatcher()} can shut the watch down on context close. */
    private volatile SharedInformerFactory informerFactory;

    /** Last trigger reason, for logs only. */
    private volatile String lastReason = "startup";

    public AiChannelDerivationService(WasmPluginInstanceService wasmPluginInstanceService,
            AiRouteService aiRouteService, AiShadowDnsPolicyService dnsPolicyService, ApiClient apiClient) {
        this.wasmPluginInstanceService = wasmPluginInstanceService;
        this.aiRouteService = aiRouteService;
        this.dnsPolicyService = dnsPolicyService;
        this.apiClient = apiClient;
        this.coreV1Api = new CoreV1Api(apiClient);
        this.networkingV1Api = new NetworkingV1Api(apiClient);
    }

    /** Snapshot of one derivation run. */
    @Getter
    public static class DerivationResult {

        private final List<String> aiRouteModelPrefixes = new ArrayList<>();
        private final List<String> aiRouteModelExacts = new ArrayList<>();
        private final List<String> authorizedProxyHosts = new ArrayList<>();
        private final List<String> unregisteredAiEgress = new ArrayList<>();
        private final List<String> orphanAuthorizedDomains = new ArrayList<>();

        /** True only when the WasmPlugin config actually changed and was written. */
        private boolean gatewaySynced;

        /**
         * False when any input could not be read. An incomplete snapshot must never be pushed: an
         * empty {@code ai_route_models} or {@code authorized_proxy_hosts} would silently mark every
         * legitimate call as a shadow one, and in enforcement mode that is a cluster-wide 403.
         */
        private boolean complete = true;

        @Override
        public String toString() {
            return "DerivationResult{modelPrefixes=" + aiRouteModelPrefixes + ", modelExacts=" + aiRouteModelExacts
                + ", proxyHosts=" + authorizedProxyHosts + ", unregisteredEgress=" + unregisteredAiEgress
                + ", orphanDomains=" + orphanAuthorizedDomains + ", complete=" + complete + ", gatewaySynced="
                + gatewaySynced + '}';
        }
    }

    /**
     * Derives all lists and pushes the gateway-facing ones into the {@code ai-shadow-detect} global
     * config.
     *
     * <p>The write is skipped when content is unchanged. Console re-derives on every trigger
     * (domain authorization, Ingress watch event, startup); writing unconditionally would bump the
     * WasmPlugin revision and force a full xDS re-sync every time for nothing. Both Console replicas
     * derive the same content, so a concurrent write from the other replica converges rather than
     * conflicts — and the SDK already retries on CONFLICT internally.
     */
    public synchronized DerivationResult deriveAndSync() {
        DerivationResult result = derive();
        result.gatewaySynced = pushToGateway(result);
        log.info("AI channel derivation: {}", result);
        return result;
    }

    /**
     * Aligns the derived lists with reality on every Console start.
     *
     * <p>Startup alignment is not optional: it is the mechanism that keeps the gateway from
     * drifting when someone edits an Ingress or an AI Route through kubectl. It runs asynchronously
     * so a slow or failing Kubernetes API never delays console readiness; on failure the gateway
     * simply keeps the previous lists, which degrades detection quality but leaves the console
     * usable.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void alignOnStartup() {
        requestDerivation("startup");
    }

    /**
     * Reacts to a domain authorization change made through the Console.
     *
     * <p>Without this the only trigger is Console restart, and a user who just approved a domain
     * sees the UI confirm it while the gateway keeps reporting that domain as shadow traffic —
     * the feature looks broken precisely when it was just configured.
     */
    @EventListener(AiChannelChangeEvent.class)
    public void onChannelChanged(AiChannelChangeEvent event) {
        requestDerivation(event == null || event.getReason() == null ? "event" : event.getReason());
    }

    /**
     * Starts the Ingress watcher.
     *
     * <p>A watch, not a poll. {@code client-java} 17.0.0 ships a working
     * {@link SharedInformerFactory}: {@code DefaultSharedIndexInformer} owns the resource-version
     * continuation, the 410-Gone re-list and the reconnect-after-timeout loop, so none of that is
     * hand-written here. Measured on the demo cluster: initial sync 0.35s, and an Ingress edit is
     * delivered to the handler in well under 100ms.
     *
     * <p>The initial list delivers one add event per existing Ingress. Those are deliberately not
     * suppressed: {@link #requestDerivation(String)} coalesces them into a single run, and a run
     * whose inputs did not change writes nothing to the gateway, so the cost is one redundant
     * derivation at startup. Suppressing them instead needs a "has the first sync finished" gate,
     * and the gate is inherently racy — {@code hasSynced()} flips true before the add events are
     * dispatched to handlers, so a real change landing in that window would be dropped. An extra
     * read at startup is the cheaper mistake.
     *
     * <p>On failure the derivation triggers that do not depend on Ingress keep working
     * ({@link #collectIngresses()} falls back to a direct list), so a broken watch degrades
     * freshness, not detection.
     */
    @PostConstruct
    public void startIngressWatcher() {
        try {
            SharedInformerFactory factory = new SharedInformerFactory(apiClient, informerExecutor);
            SharedIndexInformer<V1Ingress> informer = factory.sharedIndexInformerFor(
                (CallGeneratorParams params) -> networkingV1Api.listIngressForAllNamespacesCall(null, null, null,
                    null, null, null, params.resourceVersion, null, params.timeoutSeconds, params.watch, null),
                V1Ingress.class, V1IngressList.class, 0L);
            informer.addEventHandler(new ResourceEventHandler<V1Ingress>() {

                @Override
                public void onAdd(V1Ingress ingress) {
                    requestDerivation("ingress-added");
                }

                @Override
                public void onUpdate(V1Ingress oldIngress, V1Ingress newIngress) {
                    requestDerivation("ingress-updated");
                }

                @Override
                public void onDelete(V1Ingress ingress, boolean deletedFinalStateUnknown) {
                    requestDerivation("ingress-deleted");
                }
            });
            this.ingressInformer = informer;
            this.informerFactory = factory;
            factory.startAllRegisteredInformers();
            log.info("AI channel ingress watcher started (watch)");
        } catch (Exception e) {
            // Never block startup: without the watch the lists still refresh on authorization
            // changes and on restart, they are just not live.
            log.error("AI channel ingress watch failed to start, falling back to trigger-only derivation", e);
        }
    }

    /**
     * Stops the watch before the pools go away.
     *
     * <p>{@code stopAllRegisteredInformers()} makes the reflector threads exit by interrupting
     * them, and client-java logs that interruption as ERROR with a stack trace
     * ({@code DefaultController#processLoop get interrupted}). Those two ERROR lines on shutdown are
     * expected and do not indicate a fault.
     */
    @PreDestroy
    public void stopIngressWatcher() {
        SharedInformerFactory factory = this.informerFactory;
        if (factory != null) {
            try {
                factory.stopAllRegisteredInformers();
            } catch (Exception e) {
                log.debug("Ingress watch stop reported an error", e);
            }
        }
        informerExecutor.shutdownNow();
        executor.shutdownNow();
    }

    /**
     * Asks for a derivation without blocking the caller: both triggers are on request paths (domain
     * authorization) or on a timer, and neither may wait for a Kubernetes round-trip.
     *
     * <p>Repeated calls coalesce. The flag pair means an event arriving while a run is in flight is
     * not lost — it sets {@code dirty} again and the loop runs once more afterwards.
     */
    public void requestDerivation(String reason) {
        lastReason = reason;
        dirty.set(true);
        scheduleWorkerIfIdle();
    }

    private void scheduleWorkerIfIdle() {
        // Shutdown is a normal path here: stopIngressWatcher() interrupts the worker, whose finally
        // block calls back into this method. Submitting to a shut-down pool would throw
        // RejectedExecutionException from a thread that has nothing left to do.
        if (executor.isShutdown()) {
            return;
        }
        if (workerRunning.compareAndSet(false, true)) {
            try {
                executor.execute(this::workerLoop);
            } catch (RejectedExecutionException e) {
                // Lost the race against shutdown: release the flag so a later trigger can retry.
                workerRunning.set(false);
            }
        }
    }

    private void workerLoop() {
        try {
            while (dirty.compareAndSet(true, false)) {
                runOnce();
                // Trailing debounce: changes that arrive during this window are merged into the
                // next iteration instead of producing one derivation each.
                if (!sleepQuietly()) {
                    return;
                }
            }
        } finally {
            workerRunning.set(false);
            // Close the race between the loop exit and the flag reset: an event that landed in
            // between would otherwise leave dirty=true with no worker scheduled.
            if (dirty.get()) {
                scheduleWorkerIfIdle();
            }
        }
    }

    private void runOnce() {
        try {
            DerivationResult result = deriveAndSync();
            if (!result.isComplete()) {
                // A read failure inside derive() is reported as an incomplete snapshot, not as a
                // thrown exception, and an incomplete snapshot is deliberately never pushed. Without
                // a retry here the gateway would keep the previous lists until some unrelated
                // Ingress or authorization change came along — indistinguishable from the feature
                // having silently stopped working.
                log.warn("AI channel derivation incomplete (trigger={}), retry in {}s", lastReason,
                    RETRY_DELAY_SECONDS);
                scheduleRetry();
            }
        } catch (Exception e) {
            log.error("AI channel derivation failed (trigger={}), retry in {}s", lastReason, RETRY_DELAY_SECONDS, e);
            scheduleRetry();
        }
    }

    /**
     * Schedules one follow-up attempt after a failure.
     *
     * <p>Self-driving rather than periodic: a successful run schedules nothing, so an idle console
     * performs no Kubernetes reads at all. Each failure re-arms the timer until a run succeeds.
     */
    private void scheduleRetry() {
        if (executor.isShutdown()) {
            return;
        }
        try {
            executor.schedule(() -> requestDerivation("retry"), RETRY_DELAY_SECONDS, TimeUnit.SECONDS);
        } catch (RejectedExecutionException e) {
            // Lost the race against shutdown; nothing left to retry for.
            log.debug("Retry not scheduled, executor already shut down");
        }
    }

    /** @return false when interrupted, so the worker stops instead of spinning on shutdown. */
    private boolean sleepQuietly() {
        try {
            Thread.sleep(DEBOUNCE_MILLIS);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /**
     * The current Ingress set.
     *
     * <p>Read from the informer cache once it has synced, so a derivation triggered by an Ingress
     * change does not re-fetch what the watch just delivered. Before the first sync — and when the
     * watch failed to start — it falls back to a direct list, because an empty cache must never be
     * mistaken for "the cluster has no Ingresses": that would push empty
     * {@code authorized_proxy_hosts} and read every legitimate call as shadow traffic.
     */
    private List<V1Ingress> collectIngresses() {
        SharedIndexInformer<V1Ingress> informer = this.ingressInformer;
        if (informer != null && informer.hasSynced()) {
            List<V1Ingress> cached = informer.getIndexer().list();
            return cached == null ? Collections.<V1Ingress>emptyList() : new ArrayList<>(cached);
        }
        return listIngresses();
    }

    /** Computes the lists without touching the gateway. Safe for read-only paths. */
    public DerivationResult derive() {
        DerivationResult result = new DerivationResult();

        List<V1Ingress> ingresses = collectIngresses();
        Set<String> clusterServices = listClusterServiceNames();
        List<String> authorizedDomains = loadAuthorizedDomains();
        if (ingresses == null || clusterServices == null || authorizedDomains == null) {
            result.complete = false;
        }
        List<V1Ingress> safeIngresses = ingresses == null ? Collections.emptyList() : ingresses;
        Set<String> safeServices = clusterServices == null ? Collections.emptySet() : clusterServices;
        List<String> safeDomains = authorizedDomains == null ? Collections.emptyList() : authorizedDomains;
        Set<String> allHosts = collectIngressHosts(safeIngresses);

        if (!collectAiRouteModels(result)) {
            result.complete = false;
        }
        if (!result.complete) {
            // Stop here rather than computing lists from a partial view: a half-seen cluster would
            // report every internal service as an unregistered egress, and the log noise would
            // teach users to ignore the inspection exactly when it matters.
            log.warn("AI channel derivation incomplete, skip proxy/egress derivation");
            return result;
        }

        // A proxy host counts as registered only when an Ingress actually serves it. Authorizing a
        // domain that no route answers is a silent no-op today, so orphans are surfaced, not dropped.
        for (String host : allHosts) {
            if (isCoveredByAuthorizedDomain(host, safeDomains)) {
                result.authorizedProxyHosts.add(host);
            }
        }
        for (String domain : safeDomains) {
            if (!isAnyHostCoveredBy(domain, allHosts)) {
                result.orphanAuthorizedDomains.add(domain);
            }
        }

        for (V1Ingress ingress : safeIngresses) {
            if (ingress.getMetadata() == null) {
                continue;
            }
            String name = ingress.getMetadata().getName();
            // AI Routes are excluded by name: they are created by Console and always use this
            // prefix. An operator who names a hand-made Ingress "ai-route-*" would be skipped here,
            // which is a deliberate trade — the prefix is how Console's own entities are recognised,
            // and guessing by destination instead would also skip legitimate proxy channels.
            if (name == null || name.startsWith(AI_ROUTE_PREFIX)) {
                continue;
            }
            Map<String, String> annotations = ingress.getMetadata().getAnnotations();
            if (annotations == null) {
                annotations = Collections.emptyMap();
            }
            if ("true".equalsIgnoreCase(StringUtils.trimToEmpty(annotations.get(ANN_INTERNAL)))) {
                // Higress internal ingress: reachable only from inside the cluster mesh.
                continue;
            }
            String destination = StringUtils.trimToEmpty(annotations.get(ANN_DESTINATION));
            if (!isEgressDestination(destination, safeServices)) {
                continue;
            }
            for (String host : hostsOf(ingress)) {
                if (!isCoveredByAuthorizedDomain(host, safeDomains)) {
                    result.unregisteredAiEgress.add(host + " -> " + destination);
                }
            }
        }
        return result;
    }

    private boolean collectAiRouteModels(DerivationResult result) {
        PaginatedResult<AiRoute> routes;
        try {
            routes = aiRouteService.list(null);
        } catch (Exception e) {
            log.error("Failed to list AI routes for channel derivation", e);
            return false;
        }
        if (routes == null || CollectionUtils.isEmpty(routes.getData())) {
            // No AI routes is a legitimate state, not a read failure.
            return true;
        }
        Set<String> prefixes = new LinkedHashSet<>();
        Set<String> exacts = new LinkedHashSet<>();
        for (AiRoute route : routes.getData()) {
            if (route == null || CollectionUtils.isEmpty(route.getModelPredicates())) {
                continue;
            }
            for (AiModelPredicate predicate : route.getModelPredicates()) {
                String value = predicate == null ? null : StringUtils.trimToNull(predicate.getMatchValue());
                if (value == null) {
                    continue;
                }
                // AiModelPredicate forbids REGULAR, so only EQUAL and PRE remain. Keeping them apart
                // matters: replaying an EQUAL predicate as a prefix would widen the whitelist and turn
                // a shadow call into a non-shadow one — a miss, not a harmless false alarm.
                if (isPrefixPredicate(predicate)) {
                    prefixes.add(value);
                } else {
                    exacts.add(value);
                }
            }
        }
        result.aiRouteModelPrefixes.addAll(prefixes);
        result.aiRouteModelExacts.addAll(exacts);
        return true;
    }

    private static boolean isPrefixPredicate(RoutePredicate predicate) {
        String matchType = predicate.getMatchType();
        if (StringUtils.isBlank(matchType)) {
            return false;
        }
        // RoutePredicateTypeEnum: EQUAL("exact"), PRE("prefix"), REGULAR("regex").
        String upper = matchType.trim().toUpperCase(Locale.ROOT);
        return "PRE".equals(upper) || "PREFIX".equals(upper);
    }

    private boolean pushToGateway(DerivationResult result) {
        if (!result.complete) {
            log.warn("AI channel derivation is incomplete (an input could not be read); "
                + "keeping the previous gateway config instead of writing a partial whitelist");
            return false;
        }
        WasmPluginInstance instance = wasmPluginInstanceService.query(WasmPluginInstanceScope.GLOBAL, null,
            AsgPluginConstants.AI_SHADOW_DETECT, false);
        if (instance == null) {
            log.warn("Global WasmPlugin '{}' not found, skip channel derivation sync",
                AsgPluginConstants.AI_SHADOW_DETECT);
            return false;
        }
        Map<String, Object> configurations = instance.getConfigurations();
        if (configurations == null) {
            configurations = new LinkedHashMap<>();
            instance.setConfigurations(configurations);
        }

        Map<String, Object> desired = new LinkedHashMap<>();
        desired.put(KEY_AI_ROUTE_MODELS, new ArrayList<>(result.aiRouteModelPrefixes));
        desired.put(KEY_AI_ROUTE_MODEL_EXACTS, new ArrayList<>(result.aiRouteModelExacts));
        desired.put(KEY_AUTHORIZED_PROXY_HOSTS, new ArrayList<>(result.authorizedProxyHosts));

        boolean changed = false;
        for (Map.Entry<String, Object> entry : desired.entrySet()) {
            if (!Objects.equals(configurations.get(entry.getKey()), entry.getValue())) {
                configurations.put(entry.getKey(), entry.getValue());
                changed = true;
            }
        }
        if (!changed) {
            log.debug("AI channel derivation unchanged, skip WasmPlugin write");
            return false;
        }
        wasmPluginInstanceService.addOrUpdate(instance);
        return true;
    }

    private List<V1Ingress> listIngresses() {
        try {
            V1IngressList list = networkingV1Api.listIngressForAllNamespaces(null, null, null, null, null, null, null,
                null, null, null);
            return list == null || list.getItems() == null ? Collections.emptyList() : list.getItems();
        } catch (ApiException e) {
            log.error("Failed to list Ingresses for channel derivation: code={} msg={}", e.getCode(), e.getMessage());
            return null;
        } catch (Exception e) {
            log.error("Failed to list Ingresses for channel derivation", e);
            return null;
        }
    }

    /**
     * Lists Service names of the ingress namespace, used to decide whether a destination stays inside
     * the cluster.
     *
     * <p>A destination such as {@code llm-nvidia.internal.dns:443} is an McpBridge registry name and
     * says nothing about direction by itself. Resolving it through the registry CRD would be exact,
     * but the registry convention is that a name pointing at an in-cluster Service reuses that
     * Service name ({@code mock-llm-backend.internal} -> {@code mock-llm-backend}), so the Service
     * list is enough and does not add a CRD dependency. The known gap: a registry deliberately named
     * after an existing Service while pointing outside would be treated as internal. That is a
     * configuration an operator would have to build on purpose, and it is recorded as a boundary in
     * the design doc.
     */
    private Set<String> listClusterServiceNames() {
        Set<String> names = new LinkedHashSet<>();
        try {
            V1ServiceList list = coreV1Api.listNamespacedService(INGRESS_NS, null, null, null, null, null, null, null,
                null, null, null);
            if (list == null || list.getItems() == null) {
                return names;
            }
            for (V1Service service : list.getItems()) {
                if (service.getMetadata() != null && service.getMetadata().getName() != null) {
                    names.add(service.getMetadata().getName().toLowerCase(Locale.ROOT));
                }
            }
        } catch (ApiException e) {
            log.warn("Failed to list services for egress classification: code={} msg={}", e.getCode(),
                e.getMessage());
            return null;
        } catch (Exception e) {
            log.warn("Failed to list services for egress classification", e);
            return null;
        }
        return names;
    }

    /**
     * Whether an Ingress destination leaves the cluster.
     *
     * <p>Blank destinations and destinations that cannot be resolved are excluded rather than
     * reported: a false "unregistered egress" alert teaches users to ignore the list, which costs
     * more than the missed case it invents.
     */
    static boolean isEgressDestination(String destination, Set<String> clusterServices) {
        if (StringUtils.isBlank(destination)) {
            return false;
        }
        String target = destination.trim();
        int colon = target.lastIndexOf(':');
        if (colon > 0) {
            target = target.substring(0, colon);
        }
        String lower = target.toLowerCase(Locale.ROOT);
        if (lower.contains(SVC_FQDN_SUFFIX)) {
            return false;
        }
        if (lower.endsWith(DNS_SUFFIX)) {
            lower = lower.substring(0, lower.length() - DNS_SUFFIX.length());
        }
        if (clusterServices.contains(lower)) {
            return false;
        }
        if (lower.endsWith(INTERNAL_SUFFIX)) {
            String shortName = lower.substring(0, lower.length() - INTERNAL_SUFFIX.length());
            return !clusterServices.contains(shortName);
        }
        // A bare domain, e.g. "chat.openai.com", is a direct outbound proxy target.
        return true;
    }

    private static Set<String> collectIngressHosts(List<V1Ingress> ingresses) {
        Set<String> hosts = new LinkedHashSet<>();
        for (V1Ingress ingress : ingresses) {
            hosts.addAll(hostsOf(ingress));
        }
        return hosts;
    }

    private static Set<String> hostsOf(V1Ingress ingress) {
        Set<String> hosts = new LinkedHashSet<>();
        if (ingress.getSpec() == null || CollectionUtils.isEmpty(ingress.getSpec().getRules())) {
            return hosts;
        }
        for (V1IngressRule rule : ingress.getSpec().getRules()) {
            String host = rule == null ? null : StringUtils.trimToNull(rule.getHost());
            if (host != null && !"*".equals(host)) {
                hosts.add(host.toLowerCase(Locale.ROOT));
            }
        }
        return hosts;
    }

    private List<String> loadAuthorizedDomains() {
        try {
            if (dnsPolicyService == null) {
                return Collections.emptyList();
            }
            AiShadowDnsPolicy policy = dnsPolicyService.getPolicy();
            if (policy == null || StringUtils.isBlank(policy.getAuthorizedDomains())) {
                return Collections.emptyList();
            }
            List<String> domains = new ArrayList<>();
            for (String part : policy.getAuthorizedDomains().split(",")) {
                String domain = normalizeDomain(part);
                if (StringUtils.isNotEmpty(domain)) {
                    domains.add(domain);
                }
            }
            return domains;
        } catch (Exception e) {
            log.warn("Failed to load authorized domains for channel derivation", e);
            return null;
        }
    }

    /**
     * Mirrors the rule already used by {@code AiShadowServiceImpl.isDomainAuthorized}: equal, or a
     * sub-domain of the authorized value. Both sides are normalized, so a stored
     * {@code *.example.com} (normalized to {@code example.com}) keeps covering its sub-domains.
     */
    private static boolean isCoveredByAuthorizedDomain(String host, List<String> authorizedDomains) {
        String normalizedHost = normalizeDomain(host);
        if (StringUtils.isEmpty(normalizedHost)) {
            return false;
        }
        for (String authorized : authorizedDomains) {
            if (normalizedHost.equals(authorized) || normalizedHost.endsWith("." + authorized)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isAnyHostCoveredBy(String domain, Set<String> hosts) {
        for (String host : hosts) {
            if (isCoveredByAuthorizedDomain(host, Collections.singletonList(domain))) {
                return true;
            }
        }
        return false;
    }

    private static String normalizeDomain(String domain) {
        if (StringUtils.isBlank(domain)) {
            return "";
        }
        String d = domain.trim().toLowerCase(Locale.ROOT).replaceAll("^\\*\\.", "");
        if (d.startsWith(".")) {
            d = d.substring(1);
        }
        return d;
    }
}
