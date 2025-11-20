package com.netcracker.cloud.meshtestservicespring;

import com.netcracker.cloud.context.propagation.spring.webclient.annotation.EnableWebclientContextProvider;
import com.netcracker.cloud.routeregistration.webclient.EnableRouteRegistrationOnWebClient;
import com.netcracker.cloud.smartclient.config.annotation.EnableFrameworkWebClient;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
@EnableRouteRegistrationOnWebClient
@EnableFrameworkWebClient
@EnableWebclientContextProvider
public class Application {
    public static void main(String[] args) {
        SpringApplication.run(Application.class, args);
    }
}

