package com.netcracker.it.storage.scenario;

import com.netcracker.it.storage.controller.DeploymentFaultController;
import com.netcracker.it.storage.controller.FaultController;
import com.netcracker.it.storage.controller.PatroniFaultController;
import io.fabric8.kubernetes.client.KubernetesClient;

import java.util.List;
import java.util.function.Function;

/**
 * What a storage needs to be tested: the probe name in the application contract, the timing it is
 * held to, and the faults it can be subjected to. The platform under test is chosen separately, so
 * the same profile drives the Spring and Go applications.
 */
public enum StorageProfile {

    /**
     * The MaaS client while the database behind maas-service moves its leader. A demoted leader
     * surfaces to the client as 405 from maas-service and 500 from maas-agent. Not control-plane
     * only: maas-service reaches into the broker to create and describe the topic, so this needs
     * a healthy Kafka as well.
     *
     * <p>The one profile that also covers a planned switchover: a demoted leader answers 405 for a
     * while, where an abrupt loss shows up as reset connections and an election first.
     */
    MAAS_KAFKA("maas-kafka", 2, List.of("maas-agent"), Thresholds.maas(),
            List.of(Fault.ABRUPT_LEADER_LOSS, Fault.GRACEFUL_SWITCHOVER), Fault.ABRUPT_LEADER_LOSS,
            StorageProfile::patroni),

    /** The other MaaS resource. A vhost travels the same path as a topic, so a leader change hits it too. */
    MAAS_RABBIT("maas-rabbit", 2, List.of("maas-agent"), Thresholds.maas(),
            List.of(Fault.ABRUPT_LEADER_LOSS), Fault.ABRUPT_LEADER_LOSS, StorageProfile::patroni),

    /**
     * The watch subscription: a long poll the client holds open against maas-agent, rather than a
     * connection opened per call. Covered nowhere else, and the place a regression already
     * happened once in the Go client.
     */
    MAAS_WATCH("maas-watch", 2, List.of("maas-agent"), Thresholds.maas(),
            List.of(Fault.ABRUPT_LEADER_LOSS), Fault.ABRUPT_LEADER_LOSS, StorageProfile::patroni),

    /**
     * The same MaaS calls, but the fault is maas-agent itself losing an instance. The client sees
     * a reset connection rather than a status code, which is the other half of its retry logic.
     */
    MAAS_AGENT("maas-kafka", 2, List.of("maas-agent"), Thresholds.maasAgent(),
            List.of(Fault.INSTANCE_LOSS), Fault.INSTANCE_LOSS, StorageProfile::maasAgent);

    private static final String NAMESPACE = System.getProperty("storage.namespace");
    private static final String LEADER_SERVICE = System.getProperty("storage.leaderService");
    private static final String MEMBER_PREFIX = System.getProperty("storage.memberPrefix");
    private static final String AGENT_DEPLOYMENT = System.getProperty("storage.maasAgentDeployment");
    private static final int AGENT_REPLICAS =
            Integer.parseInt(System.getProperty("storage.maasAgentReplicas", "2"));

    private final String probe;
    private final int operationsPerSecond;
    private final List<String> requiredServices;
    private final Thresholds thresholds;
    private final List<Fault> faults;
    private final Fault primaryFault;
    private final Function<KubernetesClient, FaultController> controller;

    StorageProfile(String probe, int operationsPerSecond, List<String> requiredServices,
                   Thresholds thresholds, List<Fault> faults, Fault primaryFault,
                   Function<KubernetesClient, FaultController> controller) {
        this.probe = probe;
        this.operationsPerSecond = operationsPerSecond;
        this.requiredServices = requiredServices;
        this.thresholds = thresholds;
        this.faults = faults;
        this.primaryFault = primaryFault;
        this.controller = controller;
    }

    /** Probe name in the application contract, for example {@code maas-kafka}. */
    public String probe() {
        return probe;
    }

    /** Operations per second the workload issues; slower for storages whose calls are expensive. */
    public int operationsPerSecond() {
        return operationsPerSecond;
    }

    /**
     * Services this storage cannot be tested without. Checked up front so a missing install fails
     * with its own name rather than as a client timeout thirty operations later.
     */
    public List<String> requiredServices() {
        return requiredServices;
    }

    public Thresholds thresholds() {
        return thresholds;
    }

    public List<Fault> faults() {
        return faults;
    }

    /** The fault used where a scenario needs just one. */
    public Fault primaryFault() {
        return primaryFault;
    }

    public FaultController newController(KubernetesClient kubernetes) {
        return controller.apply(kubernetes);
    }

    private static FaultController patroni(KubernetesClient kubernetes) {
        return new PatroniFaultController(kubernetes, NAMESPACE, LEADER_SERVICE, MEMBER_PREFIX);
    }

    private static FaultController maasAgent(KubernetesClient kubernetes) {
        return new DeploymentFaultController(kubernetes, Namespaces.application(),
                AGENT_DEPLOYMENT, AGENT_REPLICAS);
    }
}
