package controller;

import com.google.common.reflect.Reflection;
import com.netcracker.quarkus.controller.Controller;
import lombok.extern.slf4j.Slf4j;
import org.junit.Assert;
import org.junit.Ignore;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;
import java.lang.reflect.Field;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;

import static org.mockito.ArgumentMatchers.any;

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
        HttpServletRequest mockHttpServletRequest = Mockito.mock(HttpServletRequest.class);

        setPrivateField(controller, "deploymentVersion", "v1");
        setPrivateField(controller, "serviceName", "mesh-test-service-spring-v1");
        setPrivateField(controller, "familyName", "mesh-test-service-spring");
        setPrivateField(controller, "namespace", "mesh-test-namespace");
        Assert.assertTrue(controller.hello(mockHttpServletRequest).startsWith("{\"serviceName\":\"mesh-test-service-spring-v1\",\"familyName\":\"mesh-test-service-spring\",\"version\":\"v1\",\"namespace\":\"mesh-test-namespace\""));
    }

    private void setPrivateField(Object target, String fieldName, Object fieldValue) throws NoSuchFieldException, IllegalAccessException {
        Field privateField = target.getClass().getDeclaredField(fieldName);
        privateField.setAccessible(true);
        privateField.set(target, fieldValue);
    }

}