/*
 * Copyright 2026 Spectrayan
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.spectrayan.spector.cluster;

import java.time.Instant;

/**
 * Information about a specific replica in a replication group.
 *
 * @param replicaId unique identifier of the replica
 * @param endpoint  network endpoint (host:port) of the replica node
 * @param state     current state of the replica
 * @param lastSyncTimestamp the timestamp of the last successful synchronization
 */
public record ReplicaInfo(
        String replicaId,
        String endpoint,
        ReplicaState state,
        Instant lastSyncTimestamp
) {
    /**
     * Creates a new ReplicaInfo with an updated state.
     */
    public ReplicaInfo withState(ReplicaState newState) {
        return new ReplicaInfo(replicaId, endpoint, newState, lastSyncTimestamp);
    }

    /**
     * Creates a new ReplicaInfo with an updated sync timestamp.
     */
    public ReplicaInfo withSyncTimestamp(Instant timestamp) {
        return new ReplicaInfo(replicaId, endpoint, state, timestamp);
    }
}
