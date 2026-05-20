package com.spectrayan.spector.core;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * Unit tests for {@link NibblePacker}.
 */
class NibblePackerTest {

    @Test
    void packTwoValues() {
        int[] values = {0x0A, 0x05};
        byte[] packed = NibblePacker.pack(values, 2);
        assertEquals(1, packed.length);
        assertEquals((byte) 0xA5, packed[0]);
    }

    @Test
    void packFourValues() {
        int[] values = {15, 0, 8, 3};
        byte[] packed = NibblePacker.pack(values, 4);
        assertEquals(2, packed.length);
        assertEquals((byte) 0xF0, packed[0]);
        assertEquals((byte) 0x83, packed[1]);
    }

    @Test
    void packOddLength_padsFinalNibbleWithZero() {
        int[] values = {7, 12, 3};
        byte[] packed = NibblePacker.pack(values, 3);
        assertEquals(2, packed.length);
        assertEquals((byte) 0x7C, packed[0]);
        // 3 in high nibble, 0 pad in low nibble
        assertEquals((byte) 0x30, packed[1]);
    }

    @Test
    void packEmptyArray() {
        int[] values = {};
        byte[] packed = NibblePacker.pack(values, 0);
        assertEquals(0, packed.length);
    }

    @Test
    void packSingleValue_padded() {
        int[] values = {9};
        byte[] packed = NibblePacker.pack(values, 1);
        assertEquals(1, packed.length);
        assertEquals((byte) 0x90, packed[0]);
    }

    @Test
    void unpackTwoValues() {
        byte[] packed = {(byte) 0xA5};
        int[] values = NibblePacker.unpack(packed, 2);
        assertArrayEquals(new int[]{10, 5}, values);
    }

    @Test
    void unpackOddLength() {
        byte[] packed = {(byte) 0x7C, (byte) 0x30};
        int[] values = NibblePacker.unpack(packed, 3);
        assertArrayEquals(new int[]{7, 12, 3}, values);
    }

    @Test
    void unpackEmpty() {
        byte[] packed = {};
        int[] values = NibblePacker.unpack(packed, 0);
        assertEquals(0, values.length);
    }

    @Test
    void roundTrip_evenLength() {
        int[] original = {0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15};
        byte[] packed = NibblePacker.pack(original, original.length);
        int[] unpacked = NibblePacker.unpack(packed, original.length);
        assertArrayEquals(original, unpacked);
    }

    @Test
    void roundTrip_oddLength() {
        int[] original = {1, 3, 5, 7, 9};
        byte[] packed = NibblePacker.pack(original, original.length);
        int[] unpacked = NibblePacker.unpack(packed, original.length);
        assertArrayEquals(original, unpacked);
    }

    @ParameterizedTest
    @CsvSource({
        "1, 1",
        "2, 1",
        "3, 2",
        "4, 2",
        "5, 3",
        "7, 4",
        "8, 4",
        "100, 50",
        "101, 51",
        "384, 192",
    })
    void packedSize(int dimensions, int expectedBytes) {
        assertEquals(expectedBytes, NibblePacker.packedSize(dimensions));
    }

    @Test
    void pack_invalidLength_throwsException() {
        int[] values = {1, 2, 3};
        assertThrows(IllegalArgumentException.class, () -> NibblePacker.pack(values, -1));
        assertThrows(IllegalArgumentException.class, () -> NibblePacker.pack(values, 4));
    }

    @Test
    void unpack_invalidOriginalLength_throwsException() {
        byte[] packed = {(byte) 0xAB};
        assertThrows(IllegalArgumentException.class, () -> NibblePacker.unpack(packed, -1));
        assertThrows(IllegalArgumentException.class, () -> NibblePacker.unpack(packed, 3));
    }
}
