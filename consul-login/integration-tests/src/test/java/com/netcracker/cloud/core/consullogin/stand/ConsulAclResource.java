package com.netcracker.cloud.core.consullogin.stand;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.fabric8.kubernetes.api.model.GenericKubernetesResource;
import io.fabric8.kubernetes.api.model.GenericKubernetesResourceList;
import io.fabric8.kubernetes.api.model.ObjectMetaBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.dsl.MixedOperation;
import io.fabric8.kubernetes.client.dsl.Resource;
import org.awaitility.Awaitility;
import org.awaitility.core.ConditionTimeoutException;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The ACL objects of the kubernetes way, declared the way a service declares them in production: one
 * {@code ConsulACL} resource in the namespace of the service, which the Consul ACL Configurator operator turns into a
 * policy over a key prefix, a role that grants it, and a binding rule under {@link #AUTH_METHOD}.
 *
 * <p>The operator prefixes every name with the name and the namespace of the resource, and deletes what it created,
 * revoking the tokens of the role, once the resource is gone. Deleting the namespace of a scenario is therefore the
 * whole cleanup.
 */
public final class ConsulAclResource {

    /** The auth method the operator creates at its start; the login libraries default to it. */
    public static final String AUTH_METHOD = "applications-k8s-m2m";

    private static final ObjectMapper JSON = new ObjectMapper();

    private static final String API_VERSION = "netcracker.com/v1alpha1";
    private static final String KIND = "ConsulACL";
    private static final String NAME = "consul-login-test";
    private static final String POLICY = "read";
    private static final String ROLE = "reader";

    /**
     * Longer than the period after which the operator retries a transient failure, such as its auth method not being
     * created yet, so that one retry fits.
     */
    private static final Duration RECONCILE_TIMEOUT = Duration.ofSeconds(150);

    private ConsulAclResource() {
    }

    /** The name the operator gives the role of a namespace, which the tokens of the kubernetes way carry. */
    public static String roleName(String namespace) {
        return NAME + "_" + namespace + "_" + ROLE;
    }

    /**
     * Grants a service account of a namespace read access to a key prefix, and waits until the operator has created
     * every object of the grant. The namespace is created when it does not exist yet.
     *
     * @throws IllegalStateException when the operator reports an object it could not create
     * @throws ConditionTimeoutException when the operator does not finish within {@link #RECONCILE_TIMEOUT}
     */
    public static void apply(KubernetesClient kubernetes, String namespace, String serviceAccount, String keyPrefix) {
        Cluster.ensureNamespace(kubernetes, namespace);

        GenericKubernetesResource resource = new GenericKubernetesResource();
        resource.setApiVersion(API_VERSION);
        resource.setKind(KIND);
        resource.setMetadata(new ObjectMetaBuilder().withName(NAME).withNamespace(namespace).build());
        resource.setAdditionalProperty("spec", Map.of("acl", Map.of(
                "name", NAME,
                "operatorNamespace", Cluster.CONSUL_NAMESPACE,
                "json", aclJson(serviceAccount, keyPrefix))));
        resources(kubernetes).inNamespace(namespace).resource(resource).create();

        Awaitility.await("the operator reconciles " + KIND + " " + namespace + "/" + NAME)
                .atMost(RECONCILE_TIMEOUT)
                .pollInterval(Duration.ofSeconds(2))
                .until(() -> reconciled(read(kubernetes, namespace)));
        String status = status(kubernetes, namespace);
        if (status.contains("error:")) {
            throw new IllegalStateException("the operator could not create every object of " + KIND + " "
                    + namespace + "/" + NAME + ": " + status);
        }
    }

    /** The status of the resource of a namespace, as one line. */
    static String status(KubernetesClient kubernetes, String namespace) {
        GenericKubernetesResource resource = read(kubernetes, namespace);
        if (resource == null) {
            return "no " + KIND + " " + namespace + "/" + NAME;
        }
        Object status = resource.getAdditionalProperties().get("status");
        if (status == null) {
            return "no status yet; an operator that does not watch namespace " + namespace + " never writes one";
        }
        return "generation=" + resource.getMetadata().getGeneration() + " status=" + status;
    }

    private static String aclJson(String serviceAccount, String keyPrefix) {
        ObjectNode acl = JSON.createObjectNode();
        acl.putArray("policies").addObject()
                .put("Name", POLICY)
                .put("Rules", "key_prefix \"" + keyPrefix + "\" { policy = \"read\" }");
        ObjectNode role = acl.putArray("roles").addObject().put("Name", ROLE);
        role.putArray("policy_names").add(POLICY);
        acl.putArray("bind_rules").addObject()
                .put("BindName", ROLE)
                .put("ServiceAccountName", serviceAccount);
        return acl.toString();
    }

    /**
     * Reconciled once the condition {@code Successful} is true for the current generation. The operator sets it to
     * false only for a failure it retries; a failure it does not retry leaves the condition true and an
     * {@code error:} entry in the status of the object concerned.
     */
    @SuppressWarnings("unchecked")
    private static boolean reconciled(GenericKubernetesResource resource) {
        if (resource == null || !(resource.getAdditionalProperties().get("status") instanceof Map<?, ?> status)) {
            return false;
        }
        Object conditions = status.get("conditions");
        if (!(conditions instanceof List<?> list)) {
            return false;
        }
        Long generation = resource.getMetadata().getGeneration();
        return ((List<Map<String, Object>>) list).stream()
                .anyMatch(condition -> "Successful".equals(condition.get("type"))
                        && "True".equals(condition.get("status"))
                        && condition.get("observedGeneration") instanceof Number observed
                        && Objects.equals(observed.longValue(), generation));
    }

    private static GenericKubernetesResource read(KubernetesClient kubernetes, String namespace) {
        return resources(kubernetes).inNamespace(namespace).withName(NAME).get();
    }

    private static MixedOperation<GenericKubernetesResource, GenericKubernetesResourceList,
            Resource<GenericKubernetesResource>> resources(KubernetesClient kubernetes) {
        return kubernetes.genericKubernetesResources(API_VERSION, KIND);
    }
}
