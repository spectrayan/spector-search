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
package com.spectrayan.spector.config.error;

import com.spectrayan.spector.commons.error.*;

/**
 * Exception thrown when a configuration file cannot be found at the specified path.
 *
 * @see SpectorConfigException
 */
public class SpectorConfigNotFoundException extends SpectorConfigException {

    private final String path;

    public SpectorConfigNotFoundException(String path) {
        super(ErrorCode.CONFIG_FILE_NOT_FOUND, path);
        this.path = path;
    }

    public SpectorConfigNotFoundException(String path, Throwable cause) {
        super(ErrorCode.CONFIG_FILE_NOT_FOUND, cause, path);
        this.path = path;
    }

    /** Returns the path to the configuration file that was not found. */
    public String getPath() {
        return path;
    }
}
