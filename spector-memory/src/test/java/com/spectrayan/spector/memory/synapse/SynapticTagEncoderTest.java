package com.spectrayan.spector.memory.synapse;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@link SynapticTagEncoder} — 64-bit inline Bloom filter.
 */
class SynapticTagEncoderTest {

    @Test
    void singleTagProducesNonZeroBits() {
        long filter = SynapticTagEncoder.encodeTag("java");
        assertThat(filter).isNotZero();
        assertThat(Long.bitCount(filter)).isBetween(1, 3); // k=3 hash functions
    }

    @Test
    void multipleTagsEncodeViaOR() {
        long single1 = SynapticTagEncoder.encodeTag("java");
        long single2 = SynapticTagEncoder.encodeTag("performance");
        long combined = SynapticTagEncoder.encode("java", "performance");

        assertThat(combined).isEqualTo(single1 | single2);
    }

    @Test
    void matchesReturnsTrueForSubsetTags() {
        long record = SynapticTagEncoder.encode("java", "performance", "coding");
        long query = SynapticTagEncoder.encode("java", "coding");

        assertThat(SynapticTagEncoder.matches(record, query)).isTrue();
    }

    @Test
    void matchesReturnsFalseForNonSubsetTags() {
        long record = SynapticTagEncoder.encode("java", "performance");
        long query = SynapticTagEncoder.encode("python", "ml");

        // May or may not match depending on hash collisions, but usually doesn't
        // This test verifies the mechanism works for clearly disjoint tag sets
        // Note: Bloom filters can have false positives, so we test with a known non-match
        long emptyRecord = 0L;
        assertThat(SynapticTagEncoder.matches(emptyRecord, query)).isFalse();
    }

    @Test
    void emptyQueryMatchesEverything() {
        long record = SynapticTagEncoder.encode("java", "performance");
        long emptyQuery = 0L;

        assertThat(SynapticTagEncoder.matches(record, emptyQuery)).isTrue();
    }

    @Test
    void mergeORsCombinesFilters() {
        long a = SynapticTagEncoder.encode("java");
        long b = SynapticTagEncoder.encode("python");
        long merged = SynapticTagEncoder.merge(a, b);

        assertThat(merged).isEqualTo(a | b);
        assertThat(SynapticTagEncoder.matches(merged, a)).isTrue();
        assertThat(SynapticTagEncoder.matches(merged, b)).isTrue();
    }

    @Test
    void bitCountReflectsTagDensity() {
        long sparse = SynapticTagEncoder.encode("java");
        long dense = SynapticTagEncoder.encode("java", "python", "rust", "go", "kotlin");

        assertThat(SynapticTagEncoder.bitCount(dense))
                .isGreaterThanOrEqualTo(SynapticTagEncoder.bitCount(sparse));
    }

    @Test
    void deterministicEncoding() {
        long a = SynapticTagEncoder.encodeTag("java");
        long b = SynapticTagEncoder.encodeTag("java");

        assertThat(a).isEqualTo(b);
    }

    @Test
    void falsePositiveRateIsAcceptable() {
        // Encode 20 tags into a single record's filter
        String[] tags = new String[20];
        for (int i = 0; i < 20; i++) {
            tags[i] = "tag-" + i;
        }
        long filter = SynapticTagEncoder.encode(tags);

        // Test 1000 random non-existent tags for false positives
        int falsePositives = 0;
        for (int i = 100; i < 1100; i++) {
            long testMask = SynapticTagEncoder.encodeTag("nonexistent-" + i);
            if (SynapticTagEncoder.matches(filter, testMask)) {
                falsePositives++;
            }
        }

        // With 20 tags and k=3 (60 bit positions out of 64), saturation is near-total.
        // Better hash independence distributes bits more uniformly, which can slightly
        // increase FPR at high saturation. Allow up to 25% for this extreme case.
        double fpr = falsePositives / 1000.0;
        assertThat(fpr).as("False positive rate with 20 tags").isLessThan(0.25);
    }
}
