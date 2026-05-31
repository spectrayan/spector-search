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
 * Exception thrown when a request cannot be routed to the appropriate shard in the cluster.
 *
 * @see SpectorClusterException
 */
public class SpectorClusterRoutingException extends SpectorClusterException {

    private final String details;

    public SpectorClusterRoutingException(String details) {
        super(ErrorCode.CLUSTER_ROUTING_FAILED, details);
        this.details = details;
    }

    public SpectorClusterRoutingException(String details, Throwable cause) {
        super(ErrorCode.CLUSTER_ROUTING_FAILED, cause, details);
        this.details = details;
    }

    /** Returns details of the cluster routing failure. */
    public String getDetails() {
        return details;
    }
}
