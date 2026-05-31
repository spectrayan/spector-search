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
package com.spectrayan.spector.cluster.error;

import com.spectrayan.spector.commons.error.*;

/**
 * Exception thrown when a target shard is not reachable or has been decommissioned.
 *
 * @see SpectorClusterException
 */
public class SpectorShardUnavailableException extends SpectorClusterException {

    private final String shardId;

    public SpectorShardUnavailableException(String shardId) {
        super(ErrorCode.SHARD_UNAVAILABLE, shardId);
        this.shardId = shardId;
    }

    public SpectorShardUnavailableException(String shardId, Throwable cause) {
        super(ErrorCode.SHARD_UNAVAILABLE, cause, shardId);
        this.shardId = shardId;
    }

    /** Returns the ID of the shard that is unavailable. */
    public String getShardId() {
        return shardId;
    }
}
