package com.netcracker.it.spring.model;

import static com.netcracker.it.spring.Const.CONTROLLER_NAMESPACE;
import static com.netcracker.it.spring.Const.ORIGIN_NAMESPACE;
import static com.netcracker.it.spring.Const.PEER_NAMESPACE;

public class Metrics {

    public static final String METRICS_BEFORE_WARMUP =
            "bg_domain_status_time{bg_controller=\"" + CONTROLLER_NAMESPACE + "\"\n" +

            "bg_namespace_status{bg_controller=\"" + CONTROLLER_NAMESPACE + "\",ns_genesis=\"peer\",ns_name=\"" + PEER_NAMESPACE + "\",ns_status=\"candidate\"} 0.0\n" +
            "bg_namespace_status{bg_controller=\"" + CONTROLLER_NAMESPACE + "\",ns_genesis=\"peer\",ns_name=\"" + PEER_NAMESPACE + "\",ns_status=\"legacy\"} 0.0\n" +
            "bg_namespace_status{bg_controller=\"" + CONTROLLER_NAMESPACE + "\",ns_genesis=\"origin\",ns_name=\"" + ORIGIN_NAMESPACE + "\",ns_status=\"legacy\"} 0.0\n" +
            "bg_namespace_status{bg_controller=\"" + CONTROLLER_NAMESPACE + "\",ns_genesis=\"origin\",ns_name=\"" + ORIGIN_NAMESPACE + "\",ns_status=\"candidate\"} 0.0\n" +

            "bg_namespace_status_time{bg_controller=\"" + CONTROLLER_NAMESPACE + "\",ns_genesis=\"peer\",ns_name=\"" + PEER_NAMESPACE + "\"}\n" +
            "bg_namespace_status_time{bg_controller=\"" + CONTROLLER_NAMESPACE + "\",ns_genesis=\"origin\",ns_name=\"" + ORIGIN_NAMESPACE + "\"}\n" +

            "bg_namespace_version{bg_controller=\"" + CONTROLLER_NAMESPACE + "\",ns_genesis=\"peer\",ns_name=\"" + PEER_NAMESPACE + "\"}\n" +
            "bg_namespace_version{bg_controller=\"" + CONTROLLER_NAMESPACE + "\",ns_genesis=\"origin\",ns_name=\"" + ORIGIN_NAMESPACE + "\"}\n" +

            "bg_domain_status_info{bg_controller=\"" + CONTROLLER_NAMESPACE + "\",bg_status=\"BG Finalize\"} 0.0\n" +
            "bg_domain_status_info{bg_controller=\"" + CONTROLLER_NAMESPACE + "\",bg_status=\"BG Failure\"} 0.0\n" +
            "bg_domain_status_info{bg_controller=\"" + CONTROLLER_NAMESPACE + "\",bg_status=\"BG Prepare\"} 0.0\n" +
            "bg_domain_status_info{bg_controller=\"" + CONTROLLER_NAMESPACE + "\",bg_status=\"Active\"} 1.0";

    public static final String METRICS_AFTER_WARMUP =
            "bg_domain_status_time{bg_controller=\"" + CONTROLLER_NAMESPACE + "\"\n" +

            "bg_namespace_status{bg_controller=\"" + CONTROLLER_NAMESPACE + "\",ns_genesis=\"peer\",ns_name=\"" + PEER_NAMESPACE + "\",ns_status=\"idle\"} 0.0\n" +
            "bg_namespace_status{bg_controller=\"" + CONTROLLER_NAMESPACE + "\",ns_genesis=\"peer\",ns_name=\"" + PEER_NAMESPACE + "\",ns_status=\"legacy\"} 0.0\n" +
            "bg_namespace_status{bg_controller=\"" + CONTROLLER_NAMESPACE + "\",ns_genesis=\"origin\",ns_name=\"" + ORIGIN_NAMESPACE + "\",ns_status=\"legacy\"} 0.0\n" +
            "bg_namespace_status{bg_controller=\"" + CONTROLLER_NAMESPACE + "\",ns_genesis=\"origin\",ns_name=\"" + ORIGIN_NAMESPACE + "\",ns_status=\"idle\"} 0.0\n" +

            "bg_namespace_status_time{bg_controller=\"" + CONTROLLER_NAMESPACE + "\",ns_genesis=\"peer\",ns_name=\"" + PEER_NAMESPACE + "\"}\n" +
            "bg_namespace_status_time{bg_controller=\"" + CONTROLLER_NAMESPACE + "\",ns_genesis=\"origin\",ns_name=\"" + ORIGIN_NAMESPACE + "\"}\n" +

            "bg_namespace_version{bg_controller=\"" + CONTROLLER_NAMESPACE + "\",ns_genesis=\"peer\",ns_name=\"" + PEER_NAMESPACE + "\"}\n" +
            "bg_namespace_version{bg_controller=\"" + CONTROLLER_NAMESPACE + "\",ns_genesis=\"origin\",ns_name=\"" + ORIGIN_NAMESPACE + "\"}\n" +

            "bg_domain_status_info{bg_controller=\"" + CONTROLLER_NAMESPACE + "\",bg_status=\"BG Finalize\"} 0.0\n" +
            "bg_domain_status_info{bg_controller=\"" + CONTROLLER_NAMESPACE + "\",bg_status=\"BG Failure\"} 0.0\n" +
            "bg_domain_status_info{bg_controller=\"" + CONTROLLER_NAMESPACE + "\",bg_status=\"BG Prepare\"} 1.0\n" +
            "bg_domain_status_info{bg_controller=\"" + CONTROLLER_NAMESPACE + "\",bg_status=\"Active\"} 0.0\n" +

            "bg_last_operation_execution{bg_controller=\"" + CONTROLLER_NAMESPACE + "\",bg_operation=\"warmup\",op_status=\"completed\"} 1.0\n" +
            "bg_last_operation_execution_time{bg_controller=\"" + CONTROLLER_NAMESPACE + "\",bg_operation=\"warmup\",op_status=\"completed\"}\n" +
            "bg_operation_seconds_count{bg_controller=\"" + CONTROLLER_NAMESPACE + "\",bg_operation=\"warmup\",op_status=\"completed\"}\n" +
            "bg_operation_seconds_sum{bg_controller=\"" + CONTROLLER_NAMESPACE + "\",bg_operation=\"warmup\",op_status=\"completed\"}\n";

    public static final String METRICS_AFTER_PROMOTE =
            "bg_domain_status_time{bg_controller=\"" + CONTROLLER_NAMESPACE + "\"\n" +

            "bg_namespace_status{bg_controller=\"" + CONTROLLER_NAMESPACE + "\",ns_genesis=\"peer\",ns_name=\"" + PEER_NAMESPACE + "\",ns_status=\"candidate\"} 0.0\n" +
            "bg_namespace_status{bg_controller=\"" + CONTROLLER_NAMESPACE + "\",ns_genesis=\"peer\",ns_name=\"" + PEER_NAMESPACE + "\",ns_status=\"idle\"} 0.0\n" +
            "bg_namespace_status{bg_controller=\"" + CONTROLLER_NAMESPACE + "\",ns_genesis=\"origin\",ns_name=\"" + ORIGIN_NAMESPACE + "\",ns_status=\"candidate\"} 0.0\n" +
            "bg_namespace_status{bg_controller=\"" + CONTROLLER_NAMESPACE + "\",ns_genesis=\"origin\",ns_name=\"" + ORIGIN_NAMESPACE + "\",ns_status=\"idle\"} 0.0\n" +

            "bg_namespace_status_time{bg_controller=\"" + CONTROLLER_NAMESPACE + "\",ns_genesis=\"peer\",ns_name=\"" + PEER_NAMESPACE + "\"}\n" +
            "bg_namespace_status_time{bg_controller=\"" + CONTROLLER_NAMESPACE + "\",ns_genesis=\"origin\",ns_name=\"" + ORIGIN_NAMESPACE + "\"}\n" +

            "bg_namespace_version{bg_controller=\"" + CONTROLLER_NAMESPACE + "\",ns_genesis=\"peer\",ns_name=\"" + PEER_NAMESPACE + "\"}\n" +
            "bg_namespace_version{bg_controller=\"" + CONTROLLER_NAMESPACE + "\",ns_genesis=\"origin\",ns_name=\"" + ORIGIN_NAMESPACE + "\"}\n" +

            "bg_domain_status_info{bg_controller=\"" + CONTROLLER_NAMESPACE + "\",bg_status=\"BG Finalize\"} 1.0\n" +
            "bg_domain_status_info{bg_controller=\"" + CONTROLLER_NAMESPACE + "\",bg_status=\"BG Failure\"} 0.0\n" +
            "bg_domain_status_info{bg_controller=\"" + CONTROLLER_NAMESPACE + "\",bg_status=\"BG Prepare\"} 0.0\n" +
            "bg_domain_status_info{bg_controller=\"" + CONTROLLER_NAMESPACE + "\",bg_status=\"Active\"} 0.0\n" +

            "bg_last_operation_execution{bg_controller=\"" + CONTROLLER_NAMESPACE + "\",bg_operation=\"promote\",op_status=\"completed\"} 1.0\n" +
            "bg_last_operation_execution_time{bg_controller=\"" + CONTROLLER_NAMESPACE + "\",bg_operation=\"promote\",op_status=\"completed\"}\n" +
            "bg_operation_seconds_count{bg_controller=\"" + CONTROLLER_NAMESPACE + "\",bg_operation=\"promote\",op_status=\"completed\"}\n" +
            "bg_operation_seconds_sum{bg_controller=\"" + CONTROLLER_NAMESPACE + "\",bg_operation=\"promote\",op_status=\"completed\"}\n";

    public static final String METRICS_AFTER_COMMIT =
            "bg_domain_status_time{bg_controller=\"" + CONTROLLER_NAMESPACE + "\"\n" +

            "bg_namespace_status{bg_controller=\"" + CONTROLLER_NAMESPACE + "\",ns_genesis=\"peer\",ns_name=\"" + PEER_NAMESPACE + "\",ns_status=\"candidate\"} 0.0\n" +
            "bg_namespace_status{bg_controller=\"" + CONTROLLER_NAMESPACE + "\",ns_genesis=\"peer\",ns_name=\"" + PEER_NAMESPACE + "\",ns_status=\"legacy\"} 0.0\n" +
            "bg_namespace_status{bg_controller=\"" + CONTROLLER_NAMESPACE + "\",ns_genesis=\"origin\",ns_name=\"" + ORIGIN_NAMESPACE + "\",ns_status=\"legacy\"} 0.0\n" +
            "bg_namespace_status{bg_controller=\"" + CONTROLLER_NAMESPACE + "\",ns_genesis=\"origin\",ns_name=\"" + ORIGIN_NAMESPACE + "\",ns_status=\"candidate\"} 0.0\n" +

            "bg_namespace_status_time{bg_controller=\"" + CONTROLLER_NAMESPACE + "\",ns_genesis=\"peer\",ns_name=\"" + PEER_NAMESPACE + "\"}\n" +
            "bg_namespace_status_time{bg_controller=\"" + CONTROLLER_NAMESPACE + "\",ns_genesis=\"origin\",ns_name=\"" + ORIGIN_NAMESPACE + "\"}\n" +

            "bg_namespace_version{bg_controller=\"" + CONTROLLER_NAMESPACE + "\",ns_genesis=\"peer\",ns_name=\"" + PEER_NAMESPACE + "\"}\n" +
            "bg_namespace_version{bg_controller=\"" + CONTROLLER_NAMESPACE + "\",ns_genesis=\"origin\",ns_name=\"" + ORIGIN_NAMESPACE + "\"}\n" +

            "bg_domain_status_info{bg_controller=\"" + CONTROLLER_NAMESPACE + "\",bg_status=\"BG Finalize\"} 0.0\n" +
            "bg_domain_status_info{bg_controller=\"" + CONTROLLER_NAMESPACE + "\",bg_status=\"BG Failure\"} 0.0\n" +
            "bg_domain_status_info{bg_controller=\"" + CONTROLLER_NAMESPACE + "\",bg_status=\"BG Prepare\"} 0.0\n" +
            "bg_domain_status_info{bg_controller=\"" + CONTROLLER_NAMESPACE + "\",bg_status=\"Active\"} 1.0\n" +

            "bg_last_operation_execution{bg_controller=\"" + CONTROLLER_NAMESPACE + "\",bg_operation=\"commit\",op_status=\"completed\"} 1.0\n" +
            "bg_last_operation_execution_time{bg_controller=\"" + CONTROLLER_NAMESPACE + "\",bg_operation=\"commit\",op_status=\"completed\"}\n" +
            "bg_operation_seconds_count{bg_controller=\"" + CONTROLLER_NAMESPACE + "\",bg_operation=\"commit\",op_status=\"completed\"}\n" +
            "bg_operation_seconds_sum{bg_controller=\"" + CONTROLLER_NAMESPACE + "\",bg_operation=\"commit\",op_status=\"completed\"}\n";

    public static final String METRICS_AFTER_ROLLBACK =
            "bg_domain_status_time{bg_controller=\"" + CONTROLLER_NAMESPACE + "\"\n" +

            "bg_namespace_status{bg_controller=\"" + CONTROLLER_NAMESPACE + "\",ns_genesis=\"peer\",ns_name=\"" + PEER_NAMESPACE + "\",ns_status=\"idle\"} 0.0\n" +
            "bg_namespace_status{bg_controller=\"" + CONTROLLER_NAMESPACE + "\",ns_genesis=\"peer\",ns_name=\"" + PEER_NAMESPACE + "\",ns_status=\"legacy\"} 0.0\n" +
            "bg_namespace_status{bg_controller=\"" + CONTROLLER_NAMESPACE + "\",ns_genesis=\"origin\",ns_name=\"" + ORIGIN_NAMESPACE + "\",ns_status=\"legacy\"} 0.0\n" +
            "bg_namespace_status{bg_controller=\"" + CONTROLLER_NAMESPACE + "\",ns_genesis=\"origin\",ns_name=\"" + ORIGIN_NAMESPACE + "\",ns_status=\"idle\"} 0.0\n" +

            "bg_namespace_status_time{bg_controller=\"" + CONTROLLER_NAMESPACE + "\",ns_genesis=\"peer\",ns_name=\"" + PEER_NAMESPACE + "\"}\n" +
            "bg_namespace_status_time{bg_controller=\"" + CONTROLLER_NAMESPACE + "\",ns_genesis=\"origin\",ns_name=\"" + ORIGIN_NAMESPACE + "\"}\n" +

            "bg_namespace_version{bg_controller=\"" + CONTROLLER_NAMESPACE + "\",ns_genesis=\"peer\",ns_name=\"" + PEER_NAMESPACE + "\"}\n" +
            "bg_namespace_version{bg_controller=\"" + CONTROLLER_NAMESPACE + "\",ns_genesis=\"origin\",ns_name=\"" + ORIGIN_NAMESPACE + "\"}\n" +

            "bg_domain_status_info{bg_controller=\"" + CONTROLLER_NAMESPACE + "\",bg_status=\"BG Finalize\"} 0.0\n" +
            "bg_domain_status_info{bg_controller=\"" + CONTROLLER_NAMESPACE + "\",bg_status=\"BG Failure\"} 0.0\n" +
            "bg_domain_status_info{bg_controller=\"" + CONTROLLER_NAMESPACE + "\",bg_status=\"BG Prepare\"} 1.0\n" +
            "bg_domain_status_info{bg_controller=\"" + CONTROLLER_NAMESPACE + "\",bg_status=\"Active\"} 0.0\n" +

            "bg_last_operation_execution{bg_controller=\"" + CONTROLLER_NAMESPACE + "\",bg_operation=\"rollback\",op_status=\"completed\"} 1.0\n" +
            "bg_last_operation_execution_time{bg_controller=\"" + CONTROLLER_NAMESPACE + "\",bg_operation=\"rollback\",op_status=\"completed\"}\n" +
            "bg_operation_seconds_count{bg_controller=\"" + CONTROLLER_NAMESPACE + "\",bg_operation=\"rollback\",op_status=\"completed\"}\n" +
            "bg_operation_seconds_sum{bg_controller=\"" + CONTROLLER_NAMESPACE + "\",bg_operation=\"rollback\",op_status=\"completed\"}\n";

}
