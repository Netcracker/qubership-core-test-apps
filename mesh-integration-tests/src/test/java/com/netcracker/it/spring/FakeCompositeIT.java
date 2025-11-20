package com.netcracker.it.spring;

import com.netcracker.cloud.junit.cloudcore.extension.annotations.EnableExtension;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertTrue;

@EnableExtension
@Slf4j
public class FakeCompositeIT {

    @Test
    @Tag("e2e-phase:test[baseline]:standalone")
    @Tag("e2e-phase:test[satellite]:standalone/[baseline]:bgd{O{a}*P{c}*C}")
    @Tag("e2e-phase:test[satellite]:standalone/[baseline]:bgd{O{l}*P{a}*C}")
    @Tag("e2e-phase:test[satellite]:standalone/[baseline]:bgd{O{a}*P{c}*C}")
    @Tag("e2e-phase:test[satellite]:standalone/[baseline]:bgd{O{c}*P{a}*C}")
    @Tag("e2e-phase:test[satellite]:standalone/[baseline]:bgd{O{a}*P{l}*C}")
    @Tag("e2e-phase:test[satellite]:bgd{O{a}*P{i}*C}/[baseline]:bgd{O{a}*P{i}*C}")
    @Tag("e2e-phase:test[satellite]:bgd{O{a}*P{c}*C}/[baseline]:bgd{O{a}*P{i}*C}")
    @Tag("e2e-phase:test[satellite]:bgd{O{l}*P{a}*C}/[baseline]:bgd{O{a}*P{i}*C}")
    @Tag("e2e-phase:test[satellite]:bgd{O{i}*P{a}*C}/[baseline]:bgd{O{a}*P{i}*C}")
    @Tag("e2e-phase:test[satellite]:bgd{O{c}*P{a}*C}/[baseline]:bgd{O{a}*P{i}*C}")
    @Tag("e2e-phase:test[satellite]:bgd{O{a}*P{l}*C}/[baseline]:bgd{O{a}*P{i}*C}")
    public void fakeCompositeTest() throws IOException {
        log.info("Test is not implemented yet");
        assertTrue(true);
    }
}
