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
package com.spectrayan.spector.client;

/**
 * Thrown when the client cannot connect to the Spector server.
 */
public class SpectorConnectionException extends SpectorClientException {

    private final String host;
    private final int port;

    public SpectorConnectionException(String host, int port, Throwable cause) {
        super("Failed to connect to Spector at " + host + ":" + port + ": " + cause.getMessage(), cause);
        this.host = host;
        this.port = port;
    }

    /** Returns the host that was attempted. */
    public String host() {
        return host;
    }

    /** Returns the port that was attempted. */
    public int port() {
        return port;
    }
}
