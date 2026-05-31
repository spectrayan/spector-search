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
package com.spectrayan.spector.commons.error;

/**
 * Exception for server-side transport errors ({@code SPE-500-xxx}).
 *
 * <p>Base class for REST API, gRPC, and MCP server errors. The subclass
 * {@link SpectorApiException} adds HTTP status code mapping.</p>
 *
 * @see ErrorCode#API_BAD_REQUEST
 * @see ErrorCode#MCP_TOOL_FAILED
 * @see SpectorApiException
 */
public class SpectorServerException extends SpectorException {

    public SpectorServerException(ErrorCode errorCode, Object... args) {
        super(errorCode, args);
    }

    public SpectorServerException(ErrorCode errorCode, String preformattedMessage, boolean isPreformatted) {
        super(errorCode, preformattedMessage, isPreformatted);
    }

    public SpectorServerException(ErrorCode errorCode, Throwable cause, Object... args) {
        super(errorCode, cause, args);
    }
}
