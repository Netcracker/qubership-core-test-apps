package com.netcracker.cloud.storagetestservice;

import com.netcracker.cloud.dbaas.client.config.EnableServiceDbaasPostgresql;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** Exercises the DBaaS client libraries from inside the cluster while a storage leader moves. */
@SpringBootApplication
@EnableServiceDbaasPostgresql
public class Application {

    public static void main(String[] args) {
        SpringApplication.run(Application.class, args);
    }
}
