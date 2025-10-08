package com.netcracker.it.meshtestservicespring;

import com.netcracker.cloud.junit.cloudcore.extension.annotations.Cloud;
import com.netcracker.cloud.junit.cloudcore.extension.annotations.EnableExtension;
import com.netcracker.cloud.junit.cloudcore.extension.annotations.Value;
import com.netcracker.it.meshtestservicespring.model.BGContextResponse;
import com.netcracker.it.meshtestservicespring.model.BgLock;
import com.netcracker.it.meshtestservicespring.model.DetailedState;
import io.fabric8.kubernetes.client.KubernetesClient;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.concurrent.ThreadLocalRandom;

import static com.netcracker.it.meshtestservicespring.CommonOperations.*;
import static com.netcracker.it.meshtestservicespring.Const.*;
import static com.netcracker.it.meshtestservicespring.model.Operation.TERMINATE;
import static com.netcracker.it.meshtestservicespring.model.Operation.WARMUP;
import static org.junit.jupiter.api.Assertions.*;

@Slf4j
@EnableExtension
@Disabled
public class TerminateIT {

	@Cloud(namespace = @Value(prop = ORIGIN_NAMESPACE_ENV_NAME))
	private static KubernetesClient platformClient;

    @BeforeAll
    public static void init() throws Exception {
        assertNotNull(platformClient);
    }

    @Test
    @Tag("bg-e2e-phase:test-warmup-with-terminate[baseline]")
    public void terminateWarmup() throws IOException {
        log.info("Start terminateWarmup");
        BGContextResponse warmupResponse = runOperation(platformClient, WARMUP);
        assertEquals("warmup", warmupResponse.getBGContext().getOperationInProgress().getName());
        // rnd 0 sec -> 2 min
        int waitSeconds = ThreadLocalRandom.current().nextInt(0, 2 * 60 + 1);
        log.info("Wait for {} seconds", waitSeconds);
        waitForSeconds(waitSeconds);
        withTimeOut(() -> containsState(IN_PROGRESS_STATUS, COMPLETED_STATUS));

        //test lock
        BgLock bgLock = getBGLock(platformClient);
        assertTrue(bgLock.isLocked(), "Bg lock must exist while the operation is running");

        BGContextResponse terminateResponse = runOperation(platformClient, TERMINATE);
        assertEquals(TEMINATED, terminateResponse.getBGContext().getProcessStatus().getName());
        assertTrue(containsState(terminateResponse, TEMINATED));

        //test lock
        BgLock bgLockAfterTerminate = getBGLock(platformClient);
        assertFalse(bgLockAfterTerminate.isLocked(), "Bg lock should not exist while the operation is terminated");
        
        log.info("Done terminateWarmup");
    }

    private boolean containsState(String... expectedStates) {
        try {
            BGContextResponse bgContextResponse = getBGOperationStatus(platformClient);
            return containsState(bgContextResponse, expectedStates);
        } catch (IOException error) {
            log.info("Get bg status failed. Error: {}", error.getMessage());
            return false;
        }
    }

    private boolean containsState(BGContextResponse bgContextResponse, String... expectedStates) {
        for (DetailedState state : bgContextResponse.getDetailedState()) {
            log.info("Step '{}' has status '{}'", state.getStep(), state.getStatus());
            assertNotEquals(FAIL_STATUS, state.getStatus());
            for (String expectedState : expectedStates) {
                if (state.getStatus().equalsIgnoreCase(expectedState)) {
                    return true;
                }
            }
        }
        return false;
    }
}
