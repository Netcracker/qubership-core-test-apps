package com.netcracker.cloud.meshspringtestapp.controller;

import com.netcracker.cloud.meshtestservicespring.controller.SleepController;
import org.junit.Before;
import org.junit.Test;
import org.mockito.InjectMocks;
import org.mockito.MockitoAnnotations;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

public class SleepControllerTest {
    @InjectMocks
    private SleepController controller;

    private MockMvc mockMvc;

    @Before
    public void init() {
        MockitoAnnotations.initMocks(this);
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @Test
    public void testSleep() throws Exception {
        String urlTemplate = "/api/v1/sleep";
        mockMvc.perform(get(urlTemplate).contentType(MediaType.APPLICATION_JSON).param("seconds", "5"))
                .andExpect(status().isOk());
    }
}
