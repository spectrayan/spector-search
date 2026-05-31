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

import com.spectrayan.spector.commons.error.SpectorValidationException;
import com.spectrayan.spector.commons.error.ErrorCode;

/**
 * Packs and unpacks 4-bit (nibble) values into byte arrays for INT4 quantized storage.
 *
 * <p>Layout: Each byte stores two 4-bit values — the first value occupies the high nibble
 * (bits 7-4) and the second value occupies the low nibble (bits 3-0). For odd-length
 * inputs, the final byte's low nibble is padded with zero.
 */
public final class NibblePacker {

    private NibblePacker() {
        // Utility class — no instantiation
    }

    /**
     * Packs an array of 4-bit values into a byte array.
     *
     * @param values array of values, each in [0, 15]
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

        for (int i = 0; i < length; i += 2) {
            int high = values[i] & 0x0F;
            int low = (i + 1 < length) ? (values[i + 1] & 0x0F) : 0;
            packed[i / 2] = (byte) ((high << 4) | low);
        }

        return packed;
    }

    /**
     * Unpacks a byte array into individual 4-bit values.
     *
     * @param packed the packed byte array
     * @param originalLength the number of values that were originally packed
     * @return array of unpacked 4-bit values
     * @throws SpectorValidationException if originalLength is negative or exceeds capacity
     */
    public static int[] unpack(byte[] packed, int originalLength) {
        if (originalLength < 0 || originalLength > packed.length * 2) {
            throw new SpectorValidationException(ErrorCode.ARGUMENT_OUT_OF_RANGE, "originalLength", 0, 0, originalLength);
        }

        int[] values = new int[originalLength];

        for (int i = 0; i < originalLength; i++) {
            int byteIndex = i / 2;
            if (i % 2 == 0) {
                values[i] = (packed[byteIndex] >> 4) & 0x0F;
            } else {
                values[i] = packed[byteIndex] & 0x0F;
            }
        }

        return values;
    }

    /**
     * Returns the number of bytes required to store the given number of dimensions
     * in nibble-packed format.
     *
     * @param dimensions the number of dimensions (values) to pack
     * @return ceil(dimensions / 2)
     */
    public static int packedSize(int dimensions) {
        return (dimensions + 1) / 2;
    }
}