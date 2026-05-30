package com.spectrayan.spector.core;

import com.spectrayan.spector.commons.error.SpectorValidationException;

import com.spectrayan.spector.core.quantization.CrumbPacker;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link CrumbPacker} — packing and unpacking 2-bit values.
 */
class CrumbPackerTest {

    @Test
    void pack_fourValues_singleByte() {
        int[] values = {3, 2, 1, 0};
        byte[] packed = CrumbPacker.pack(values, 4);

        assertEquals(1, packed.length);
        // 3=11, 2=10, 1=01, 0=00 → 11_10_01_00 = 0xE4
        assertEquals((byte) 0xE4, packed[0]);
    }

    @Test
    void pack_eightValues_twoBytes() {
        int[] values = {0, 1, 2, 3, 3, 2, 1, 0};
        byte[] packed = CrumbPacker.pack(values, 8);

        assertEquals(2, packed.length);
        // Byte 0: 00_01_10_11 = 0x1B
        assertEquals((byte) 0x1B, packed[0]);
        // Byte 1: 11_10_01_00 = 0xE4
        assertEquals((byte) 0xE4, packed[1]);
    }

    @Test
    void pack_nonMultipleOfFour_paddsWithZero() {
        int[] values = {3, 1}; // 2 values, not a multiple of 4
        byte[] packed = CrumbPacker.pack(values, 2);

        assertEquals(1, packed.length);
        // 3=11, 1=01, pad=00, pad=00 → 11_01_00_00 = 0xD0
        assertEquals((byte) 0xD0, packed[0]);
    }

    @Test
    void pack_singleValue_paddsRemainingCrumbs() {
        int[] values = {2};
        byte[] packed = CrumbPacker.pack(values, 1);

        assertEquals(1, packed.length);
        // 2=10, pad=00, pad=00, pad=00 → 10_00_00_00 = 0x80
        assertEquals((byte) 0x80, packed[0]);
    }

    @Test
    void pack_emptyArray_returnsEmpty() {
        int[] values = {};
        byte[] packed = CrumbPacker.pack(values, 0);

        assertEquals(0, packed.length);
    }

    @Test
    void unpack_singleByte_fourValues() {
        byte[] packed = {(byte) 0xE4}; // 11_10_01_00
        int[] values = CrumbPacker.unpack(packed, 4);

        assertArrayEquals(new int[]{3, 2, 1, 0}, values);
    }

    @Test
    void unpack_partialByte_respectsOriginalLength() {
        byte[] packed = {(byte) 0xD0}; // 11_01_00_00
        int[] values = CrumbPacker.unpack(packed, 2);

        assertArrayEquals(new int[]{3, 1}, values);
    }

    @Test
    void roundTrip_multipleOfFour() {
        int[] original = {0, 1, 2, 3, 3, 2, 1, 0, 1, 1, 2, 2};
        byte[] packed = CrumbPacker.pack(original, original.length);
        int[] unpacked = CrumbPacker.unpack(packed, original.length);

        assertArrayEquals(original, unpacked);
    }

    @Test
    void roundTrip_nonMultipleOfFour() {
        int[] original = {3, 0, 2, 1, 3};
        byte[] packed = CrumbPacker.pack(original, original.length);
        int[] unpacked = CrumbPacker.unpack(packed, original.length);

        assertArrayEquals(original, unpacked);
    }

    @Test
    void roundTrip_singleValue() {
        int[] original = {2};
        byte[] packed = CrumbPacker.pack(original, original.length);
        int[] unpacked = CrumbPacker.unpack(packed, original.length);

        assertArrayEquals(original, unpacked);
    }

    @Test
    void packedSize_multipleOfFour() {
        assertEquals(1, CrumbPacker.packedSize(4));
        assertEquals(2, CrumbPacker.packedSize(8));
        assertEquals(32, CrumbPacker.packedSize(128));
        assertEquals(96, CrumbPacker.packedSize(384));
    }

    @Test
    void packedSize_nonMultipleOfFour() {
        assertEquals(1, CrumbPacker.packedSize(1));
        assertEquals(1, CrumbPacker.packedSize(2));
        assertEquals(1, CrumbPacker.packedSize(3));
        assertEquals(2, CrumbPacker.packedSize(5));
        assertEquals(2, CrumbPacker.packedSize(6));
        assertEquals(2, CrumbPacker.packedSize(7));
    }

    @Test
    void pack_negativeLengthThrows() {
        int[] values = {1, 2, 3};
        assertThrows(SpectorValidationException.class,
                () -> CrumbPacker.pack(values, -1));
    }

    @Test
    void pack_lengthExceedsArrayThrows() {
        int[] values = {1, 2};
        assertThrows(SpectorValidationException.class,
                () -> CrumbPacker.pack(values, 5));
    }

    @Test
    void unpack_negativeOriginalLengthThrows() {
        byte[] packed = {0x00};
        assertThrows(SpectorValidationException.class,
                () -> CrumbPacker.unpack(packed, -1));
    }

    @Test
    void unpack_originalLengthExceedsCapacityThrows() {
        byte[] packed = {0x00};
        assertThrows(SpectorValidationException.class,
                () -> CrumbPacker.unpack(packed, 5));
    }

    @Test
    void pack_valuesMaskedToTwoBits() {
        // Values outside [0, 3] should be masked to lower 2 bits
        int[] values = {4, 7, 255, 0}; // 4&3=0, 7&3=3, 255&3=3, 0&3=0
        byte[] packed = CrumbPacker.pack(values, 4);
        int[] unpacked = CrumbPacker.unpack(packed, 4);

        assertArrayEquals(new int[]{0, 3, 3, 0}, unpacked);
    }
}
