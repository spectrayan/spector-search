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
package com.spectrayan.spector.embed;

/**
 * Configuration options for text generation.
 *
 * @param temperature  sampling temperature (0.0 = deterministic, 1.0 = creative)
 * @param maxTokens    maximum tokens to generate
 * @param topP         nucleus sampling threshold
 * @param stopSequences stop generation at any of these sequences
 */
public record GenerationOptions(
        float temperature,
        int maxTokens,
        float topP,
        String[] stopSequences
) {

    /** Default options: deterministic, 512 max tokens. */
    public static final GenerationOptions DEFAULT = new GenerationOptions(0.1f, 512, 0.9f, new String[0]);

    /** Creative options: higher temperature for synthesis. */
    public static final GenerationOptions CREATIVE = new GenerationOptions(0.7f, 1024, 0.95f, new String[0]);

    /** Concise options: short, factual output for reflection. */
    public static final GenerationOptions CONCISE = new GenerationOptions(0.1f, 256, 0.9f, new String[0]);

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private float temperature = 0.1f;
        private int maxTokens = 512;
        private float topP = 0.9f;
        private String[] stopSequences = new String[0];

        public Builder temperature(float t) { this.temperature = t; return this; }
        public Builder maxTokens(int m) { this.maxTokens = m; return this; }
        public Builder topP(float p) { this.topP = p; return this; }
        public Builder stopSequences(String... s) { this.stopSequences = s; return this; }

        public GenerationOptions build() {
            return new GenerationOptions(temperature, maxTokens, topP, stopSequences);
        }
    }
}
