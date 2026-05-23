package com.spectrayan.spector.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * Unit tests for {@link QuantizationType} enum including INT4 and INT2 variants.
 */
class QuantizationTypeTest {

    @Test
    void testEnumVariantsExist() {
        assertEquals(5, QuantizationType.values().length);
        QuantizationType.valueOf("NONE");
        QuantizationType.valueOf("SCALAR_INT8");
        QuantizationType.valueOf("SCALAR_INT4");
        QuantizationType.valueOf("SCALAR_INT2");
        QuantizationType.valueOf("TURBO_QUANT");
    }

    @Test
    void testBitsPerDimension() {
        assertEquals(32, QuantizationType.NONE.bitsPerDimension());
        assertEquals(8, QuantizationType.SCALAR_INT8.bitsPerDimension());
        assertEquals(4, QuantizationType.SCALAR_INT4.bitsPerDimension());
        assertEquals(2, QuantizationType.SCALAR_INT2.bitsPerDimension());
    }

    @Test
    void testLevels() {
        assertEquals(256, QuantizationType.SCALAR_INT8.levels());
        assertEquals(16, QuantizationType.SCALAR_INT4.levels());
        assertEquals(4, QuantizationType.SCALAR_INT2.levels());
    }

    @ParameterizedTest
    @CsvSource({
        // dimensions, expectedInt4Bytes, expectedInt2Bytes
        "1, 1, 1",
        "2, 1, 1",
        "3, 2, 1",
        "4, 2, 1",
        "5, 3, 2",
        "7, 4, 2",
        "8, 4, 2",
        "9, 5, 3",
        "128, 64, 32",
        "384, 192, 96",
    })
    void testBytesPerVectorInt4AndInt2(int dimensions, int expectedInt4, int expectedInt2) {
        assertEquals(expectedInt4, QuantizationType.SCALAR_INT4.bytesPerVector(dimensions));
        assertEquals(expectedInt2, QuantizationType.SCALAR_INT2.bytesPerVector(dimensions));
    }

    @Test
    void testBytesPerVectorNoneAndInt8() {
        assertEquals(1536, QuantizationType.NONE.bytesPerVector(384));
        assertEquals(384, QuantizationType.SCALAR_INT8.bytesPerVector(384));
    }

    @Test
    void testLevelsForNone() {
        // 1 << 32 in Java int wraps (shift by 32 % 32 = 0), so result is 1.
        // This is acceptable since levels() is not meaningful for NONE.
        assertEquals(1, QuantizationType.NONE.levels());
    }
}
