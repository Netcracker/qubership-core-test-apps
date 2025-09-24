package controller;

import com.netcracker.quarkus.controller.Controller;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.core.UriInfo;
import lombok.extern.slf4j.Slf4j;
import org.junit.Assert;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;

import java.lang.reflect.Field;

@Slf4j
public class ControllerTest {

    @InjectMocks
    private Controller controller;

    @BeforeEach
    public void init() {
        MockitoAnnotations.initMocks(this);
    }

    @Test
    public void tesCanAccessUnsecuredEndpoint() throws NoSuchFieldException, IllegalAccessException {
        ContainerRequestContext mockHttpServletRequest = Mockito.mock(ContainerRequestContext.class);
        UriInfo mockUriInfo = Mockito.mock(UriInfo.class);

        setPrivateField(controller, "deploymentVersion", "v1");
        setPrivateField(controller, "serviceName", "mesh-test-service-spring-v1");
        setPrivateField(controller, "familyName", "mesh-test-service-spring");
        setPrivateField(controller, "namespace", "mesh-test-namespace");
        Assert.assertTrue(controller.hello(mockHttpServletRequest, mockUriInfo).startsWith("{\"serviceName\":\"mesh-test-service-spring-v1\",\"familyName\":\"mesh-test-service-spring\",\"version\":\"v1\",\"namespace\":\"mesh-test-namespace\""));
    }

    private void setPrivateField(Object target, String fieldName, Object fieldValue) throws NoSuchFieldException, IllegalAccessException {
        Field privateField = target.getClass().getDeclaredField(fieldName);
        privateField.setAccessible(true);
        privateField.set(target, fieldValue);
    }

}