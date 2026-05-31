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
 * Represents a shard assignment to a node with a specific role.
 *
 * @param shardIndex   the shard index
 * @param nodeEndpoint the endpoint of the node hosting this shard
 * @param role         the role of this assignment (PRIMARY or REPLICA)
 */
public record ShardAssignment(int shardIndex, String nodeEndpoint, ShardRole role) {
}
