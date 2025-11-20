package com.netcracker.cloud.meshspringtestapp.controller;

import com.netcracker.cloud.meshtestservicespring.controller.HelloController;
import com.netcracker.cloud.meshtestservicespring.service.HelloService;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;
import org.mockito.junit.MockitoJUnitRunner;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;


import static org.mockito.ArgumentMatchers.any;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@RunWith(MockitoJUnitRunner.class)
public class HelloControllerTest {
    @Mock
    private HelloService helloService;
    @InjectMocks
    private HelloController controller;

    private MockMvc mockMvc;

    @Before
    public void init() {
        MockitoAnnotations.initMocks(this);
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @Test
    public void testHello() throws Exception {
        String urlTemplate = "/api/v1/hello";
        mockMvc.perform(get(urlTemplate).contentType(MediaType.APPLICATION_JSON).header("X-Version", ""))
                .andExpect(status().isOk());
        Mockito.verify(helloService, Mockito.times(1)).hello(any(HttpServletRequest.class));
    }

    @Test
    public void testHelloQuarkus() throws Exception {
        String urlTemplate = "/api/v1/hello/quarkus";
        mockMvc.perform(get(urlTemplate).contentType(MediaType.APPLICATION_JSON).header("X-Version", ""))
                .andExpect(status().isOk());
        Mockito.verify(helloService, Mockito.times(1)).helloQuarkus(any());
    }
}