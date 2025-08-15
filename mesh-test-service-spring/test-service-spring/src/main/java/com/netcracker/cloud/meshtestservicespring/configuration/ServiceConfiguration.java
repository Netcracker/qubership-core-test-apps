package com.netcracker.cloud.meshtestservicespring.configuration;

import com.netcracker.cloud.meshtestservicespring.service.EgressService;
import com.netcracker.cloud.meshtestservicespring.service.HelloService;
import com.netcracker.cloud.meshtestservicespring.service.ProxyService;
import com.netcracker.cloud.meshtestservicespring.service.TcpService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ServiceConfiguration {
    @Bean
    public HelloService helloService() {
        return new HelloService();
    }

    @Bean
    public ProxyService proxyService() {
        return new ProxyService();
    }

    @Bean
    public EgressService egressService() {
        return new EgressService("http://egress-gateway:8080/api/v3/control-plane/versions/registry");
    }

    @Bean
    public TcpService tcpService(HelloService helloService) {
        return new TcpService(helloService);
    }
}

