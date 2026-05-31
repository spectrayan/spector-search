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

/**
 * Represents the state of a replica in the replication group.
 */
public enum ReplicaState {
    /** Replica is fully synchronized and serving reads. */
    ACTIVE,
    /** Replica is synchronizing with the primary (not serving reads). */
    SYNCING,
    /** Replica is unreachable/failed. */
    UNAVAILABLE
}
