package com.netcracker.cloud.storagetestservice.workload;

/** How the workload obtains its handle - the two access patterns real services have. */
public enum HandleMode {

    /** A connection per operation, which is the path the client's recovery logic sits on. */
    PER_CALL,

    /** Resolved once at startup and reused, so recovery must work without asking again. */
    LONG_HELD
}
