package com.netcracker.it.storage.scenario;

import com.netcracker.cloud.junit.cloudcore.extension.annotations.EnableExtension;
import org.junit.jupiter.api.Disabled;

/**
 * The Go MaaS client, obtaining a vhost while the database behind maas-service moves its leader.
 *
 * <p>Disabled because get-or-create never succeeds, whatever the storage is doing. The Go client
 * posts the bare classifier to {@code /api/v1/rabbit/vhost}, while maas-service reads that body as
 * {@code VHostRegistrationReqDto}, where the classifier is a nested field. Every call is rejected
 * with 400 and {@code Field validation for 'Name' failed on the 'required' tag}.
 * TODO: re-enable once a client carrying the fix is released.
 */
@Disabled("Go maas-client sends a bare classifier to rabbit get-or-create; see README.md")
@EnableExtension
class MaasRabbitGoStorageIT extends GoStorageITBase {

    @Override
    protected StorageProfile profile() {
        return StorageProfile.MAAS_RABBIT;
    }
}
