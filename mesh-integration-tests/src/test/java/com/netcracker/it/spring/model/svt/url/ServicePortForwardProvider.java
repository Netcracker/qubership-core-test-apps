package com.netcracker.it.spring.model.svt.url;

import com.netcracker.cloud.junit.cloudcore.extension.service.PortForwardService;
import com.netcracker.it.spring.utils.CloseableUrl;
import com.netcracker.it.spring.utils.ClosablePortForward;
import lombok.RequiredArgsConstructor;

import java.util.function.Function;

import static com.netcracker.it.spring.Const.CONTROLLER_NAMESPACE;

@RequiredArgsConstructor
public class ServicePortForwardProvider implements Function<String, CloseableUrl> {
    private final PortForwardService portForwardService;

    @Override
    public CloseableUrl apply(String serviceName) {
        return new ClosablePortForward(portForwardService, CONTROLLER_NAMESPACE, serviceName);
    }
}
