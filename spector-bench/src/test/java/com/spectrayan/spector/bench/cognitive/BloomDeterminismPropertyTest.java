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
package com.spectrayan.spector.bench.cognitive;

import com.spectrayan.spector.memory.synapse.SynapticTagEncoder;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

/**
 * Property-based tests for Bloom filter determinism.
 *
 * <p><b>Validates: Requirements 13.1</b>
 *
 * <p>Property 27: For any set of synaptic tag strings, SynapticTagEncoder.encode()
 * SHALL always produce the same 64-bit value — the encoding is a pure, deterministic
 * function of the input tags.
 */
class BloomDeterminismPropertyTest {

    // ══════════════════════════════════════════════════════════════
    // Property 27: Same tags → same 64-bit encoding
    // ══════════════════════════════════════════════════════════════

    /**
     * Property 27a: Encoding the same tags multiple times always produces
     * the same 64-bit value.
     *
     * <p><b>Validates: Requirements 13.1</b>
     */
    @Property(tries = 200)
    void sameTagsSameEncoding(@ForAll("tagArrays") String[] tags) {
        long first = SynapticTagEncoder.encode(tags);
        long second = SynapticTagEncoder.encode(tags);
        long third = SynapticTagEncoder.encode(tags);

        assert first == second
                : String.format("Encoding not deterministic: %016X != %016X", first, second);
        assert second == third
                : String.format("Encoding not deterministic: %016X != %016X", second, third);
    }

    /**
     * Property 27b: Encoding a single tag is deterministic.
     *
     * <p><b>Validates: Requirements 13.1</b>
     */
    @Property(tries = 200)
    void singleTagDeterministic(@ForAll("tags") String tag) {
        long first = SynapticTagEncoder.encodeTag(tag);
        long second = SynapticTagEncoder.encodeTag(tag);

        assert first == second
                : String.format("Single tag encoding not deterministic for '%s': %016X != %016X",
                tag, first, second);
    }

    /**
     * Property 27c: Tag order does not affect the encoding (commutative).
     * encode(a, b) == encode(b, a)
     *
     * <p><b>Validates: Requirements 13.1</b>
     */
    @Property(tries = 200)
    void tagOrderDoesNotMatter(
            @ForAll("tags") String tagA,
            @ForAll("tags") String tagB) {

        long ab = SynapticTagEncoder.encode(tagA, tagB);
        long ba = SynapticTagEncoder.encode(tagB, tagA);

        assert ab == ba
                : String.format("Tag order affects encoding: encode(%s,%s)=%016X != encode(%s,%s)=%016X",
                tagA, tagB, ab, tagB, tagA, ba);
    }

    // ══════════════════════════════════════════════════════════════
    // Generators
    // ══════════════════════════════════════════════════════════════

    @Provide
    Arbitrary<String[]> tagArrays() {
        return Arbitraries.strings().alpha().ofMinLength(2).ofMaxLength(20)
                .array(String[].class).ofMinSize(1).ofMaxSize(10);
    }

    @Provide
    Arbitrary<String> tags() {
        return Arbitraries.strings().alpha().ofMinLength(2).ofMaxLength(20);
    }
}
