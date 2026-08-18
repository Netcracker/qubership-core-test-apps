package com.netcracker.it.storage.scenario;

import com.netcracker.it.storage.controller.DeploymentFaultController;
import com.netcracker.it.storage.controller.FaultController;
import com.netcracker.it.storage.controller.KafkaFaultController;
import com.netcracker.it.storage.controller.PatroniFaultController;
import io.fabric8.kubernetes.client.KubernetesClient;

import java.util.List;
import java.util.function.Function;

/**
 * What a storage needs to be tested: the probe name in the application contract, the timing it is
 * held to, and the faults it can be subjected to. The platform under test is chosen separately, so
 * the same profile drives the Spring, Go and Quarkus applications.
 */
public enum StorageProfile {

    /** The DBaaS client against a Patroni leader change. */
    POSTGRESQL("postgresql", 10, List.of(), Thresholds.postgresql(),
            leaderFaults(), Fault.ABRUPT_LEADER_LOSS, StorageProfile::patroni),

    /**
     * The MaaS client while the database behind maas-service moves its leader. A demoted leader
     * surfaces to the client as 405 from maas-service and 500 from maas-agent. Not control-plane
     * only: maas-service reaches into the broker to create and describe the topic, so this needs
     * a healthy Kafka as well.
     */
    MAAS_KAFKA("maas-kafka", 2, List.of("maas-agent"), Thresholds.maas(),
            leaderFaults(), Fault.ABRUPT_LEADER_LOSS, StorageProfile::patroni),

    /**
     * The Kafka data plane. The local-dev chart deploys one single-node KRaft broker per release
     * and gives it no volume, so the achievable event is the broker losing both its process and
     * its data — not a partition leader moving, and not a plain restart either.
     */
    KAFKA("kafka", 5, List.of("maas-agent"), Thresholds.kafka(),
            List.of(Fault.BROKER_DATA_LOSS), Fault.BROKER_DATA_LOSS, StorageProfile::kafka),

    /**
     * The same MaaS calls, but the fault is maas-agent itself losing an instance. The client sees
     * a reset connection rather than a status code, which is the other half of its retry logic.
     */
    MAAS_AGENT("maas-kafka", 2, List.of("maas-agent"), Thresholds.maasAgent(),
            List.of(Fault.INSTANCE_LOSS, Fault.ROLLING_RESTART), Fault.INSTANCE_LOSS,
            StorageProfile::maasAgent);

    private static final String NAMESPACE = System.getProperty("storage.namespace");
    private static final String LEADER_SERVICE = System.getProperty("storage.leaderService");
    private static final String MEMBER_PREFIX = System.getProperty("storage.memberPrefix");
    private static final String KAFKA_NAMESPACE = System.getProperty("storage.kafkaNamespace");
    private static final String KAFKA_INSTANCE = System.getProperty("storage.kafkaInstance");
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

    /**
     * Waits for what this storage needs but does not disturb. Every probe here goes through a
     * topic, and a broker killed by the preceding scenario makes get-or-create fail on setup with
     * MAAS-0600 rather than anything about the client.
     */
    public void awaitDependencies(KubernetesClient kubernetes, java.time.Duration timeout) {
        kafka(kubernetes).awaitStable(timeout);
    }

    private static List<Fault> leaderFaults() {
        return List.of(Fault.ABRUPT_LEADER_LOSS, Fault.GRACEFUL_SWITCHOVER, Fault.ROLLING_RESTART);
    }

    private static FaultController patroni(KubernetesClient kubernetes) {
        return new PatroniFaultController(kubernetes, NAMESPACE, LEADER_SERVICE, MEMBER_PREFIX);
    }

    private static FaultController kafka(KubernetesClient kubernetes) {
        return new KafkaFaultController(kubernetes, KAFKA_NAMESPACE, KAFKA_INSTANCE);
    }

    private static FaultController maasAgent(KubernetesClient kubernetes) {
        return new DeploymentFaultController(kubernetes, Namespaces.application(),
                AGENT_DEPLOYMENT, AGENT_REPLICAS);
    }
}
