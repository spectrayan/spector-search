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
package com.spectrayan.spector.core.quantization;
import com.spectrayan.spector.commons.error.SpectorException;

import com.spectrayan.spector.commons.error.ErrorCode;
import com.spectrayan.spector.commons.error.SpectorValidationException;

/**
 * Packs and unpacks 2-bit (crumb) values into byte arrays for INT2 quantized storage.
 *
 * <p>Layout: Each byte stores four 2-bit values — the first value occupies bits 7-6,
 * the second bits 5-4, the third bits 3-2, and the fourth bits 1-0. For non-multiple-of-4
 * length inputs, trailing crumbs in the final byte are padded with zero.
 */
public final class CrumbPacker {

    private CrumbPacker() {
        // Utility class — no instantiation
    }

    /**
     * Packs an array of 2-bit values into a byte array.
     *
     * @param values array of values, each in [0, 3]
     * @param length number of values to pack from the array
     * @return packed byte array
     * @throws SpectorValidationException if length is negative or exceeds array length
     */
    public static byte[] pack(int[] values, int length) {
        if (length < 0 || length > values.length) {
            throw new SpectorValidationException(ErrorCode.ARGUMENT_OUT_OF_RANGE, "length", 0, 0, length);
        }

        int packedLength = packedSize(length);
        byte[] packed = new byte[packedLength];

        for (int i = 0; i < length; i++) {
            int byteIndex = i / 4;
            int positionInByte = i % 4;
            int shift = 6 - (positionInByte * 2); // 6, 4, 2, 0
            packed[byteIndex] |= (byte) ((values[i] & 0x03) << shift);
        }

        return packed;
    }

    /**
     * Unpacks a byte array into individual 2-bit values.
     *
     * @param packed the packed byte array
     * @param originalLength the number of values that were originally packed
     * @return array of unpacked 2-bit values
     * @throws SpectorValidationException if originalLength is negative or exceeds capacity
     */
    public static int[] unpack(byte[] packed, int originalLength) {
        if (originalLength < 0 || originalLength > packed.length * 4) {
            throw new SpectorValidationException(ErrorCode.ARGUMENT_OUT_OF_RANGE, "originalLength", 0, 0, originalLength);
        }

        int[] values = new int[originalLength];

        for (int i = 0; i < originalLength; i++) {
            int byteIndex = i / 4;
            int positionInByte = i % 4;
            int shift = 6 - (positionInByte * 2); // 6, 4, 2, 0
            values[i] = (packed[byteIndex] >> shift) & 0x03;
        }

        return values;
    }

    /**
     * Returns the number of bytes required to store the given number of dimensions
     * in crumb-packed format.
     *
     * @param dimensions the number of dimensions (values) to pack
     * @return ceil(dimensions / 4)
     */
    public static int packedSize(int dimensions) {
        return (dimensions + 3) / 4;
    }
}