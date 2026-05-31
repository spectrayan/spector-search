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
package com.spectrayan.spector.core.quantization.strategy;
import com.spectrayan.spector.commons.error.SpectorException;

import com.spectrayan.spector.core.quantization.NonUniformQuantizer;
import com.spectrayan.spector.core.quantization.QuantizationType;
import com.spectrayan.spector.core.quantization.ScalarQuantizer;
import com.spectrayan.spector.core.quantization.TurboQuantizer;
import com.spectrayan.spector.core.quantization.svasq.Svasq4Encoder;
import com.spectrayan.spector.core.quantization.svasq.SvasqEncoder;
import com.spectrayan.spector.core.quantization.svasq.SvasqParams;
import com.spectrayan.spector.core.similarity.SimilarityFunction;
import com.spectrayan.spector.commons.error.SpectorValidationException;
import com.spectrayan.spector.commons.error.ErrorCode;

/**
 * Abstract Factory for creating {@link QuantizationStrategy} instances.
 *
 * <p>Centralizes the "which strategy for which type" decision and validates
 * that required quantizer objects are present. Callers (e.g., {@code QuantizedVectorStore}
 * and {@code QuantizedHnswIndex}) call {@link #create} and hold a single
 * {@link QuantizationStrategy} reference — no more per-type fields or switch chains.</p>
 *
 * <h3>Usage</h3>
 * <pre>{@code
 *   QuantizationStrategy strategy = QuantizationStrategyFactory.create(
 *       QuantizationType.SVASQ,
 *       null, null, null,
 *       svasqEncoder,
 *       similarityFunction
 *   );
 *   strategy.encode(vector, segment, offset);
 *   DistanceContext ctx = strategy.prepareQueryContext(query);
 *   float dist = strategy.distance(segment, offset, ctx);
 * }</pre>
 *
 * <h3>Open/Closed principle</h3>
 * <p>To add a new quantization type: implement {@link QuantizationStrategy},
 * add a case here. {@code QuantizedVectorStore} and {@code QuantizedHnswIndex}
 * do not change.</p>
 */
public final class QuantizationStrategyFactory {

    private QuantizationStrategyFactory() {}

    /**
     * Creates a {@link QuantizationStrategy} for the given quantization type,
     * validating that all required sub-quantizers are present.
     *
     * @param type               the quantization type (must not be null or NONE)
     * @param scalarQuantizer    required for SCALAR_INT8 (may be null for others)
     * @param nonUniformQuantizer required for SCALAR_INT4 / SCALAR_INT2 (may be null for others)
     * @param turboQuantizer     required for TURBO_QUANT (may be null for others)
     * @param svasqEncoder        required for SVASQ (may be null for others)
     * @param svasq4Encoder       required for SVASQ_4 (may be null for others)
     * @param similarityFunction the distance metric (must not be null)
     * @return a fully initialized {@link QuantizationStrategy}
     * @throws SpectorValidationException if a required sub-quantizer is missing or dimensions mismatch
     */
    public static QuantizationStrategy create(
            QuantizationType type,
            ScalarQuantizer scalarQuantizer,
            NonUniformQuantizer nonUniformQuantizer,
            TurboQuantizer turboQuantizer,
            SvasqEncoder svasqEncoder,
            Svasq4Encoder svasq4Encoder,
            SimilarityFunction similarityFunction) {

        if (type == null) throw new SpectorValidationException(ErrorCode.ARGUMENT_NULL, "QuantizationType");
        if (similarityFunction == null) throw new SpectorValidationException(ErrorCode.ARGUMENT_NULL, "SimilarityFunction");

        return switch (type) {
            case SCALAR_INT8 -> {
                if (scalarQuantizer == null) {
                    throw new SpectorValidationException(ErrorCode.ARGUMENT_NULL, "ScalarQuantizer for SCALAR_INT8");
                }
                yield new Int8Strategy(scalarQuantizer, similarityFunction);
            }
            case SCALAR_INT4 -> {
                if (nonUniformQuantizer == null) {
                    throw new SpectorValidationException(ErrorCode.ARGUMENT_NULL, "NonUniformQuantizer for SCALAR_INT4");
                }
                validateLevels(nonUniformQuantizer, type);
                yield new Int4Strategy(nonUniformQuantizer, similarityFunction,
                        computeGlobalCentroids(nonUniformQuantizer));
            }
            case SCALAR_INT2 -> {
                if (nonUniformQuantizer == null) {
                    throw new SpectorValidationException(ErrorCode.ARGUMENT_NULL, "NonUniformQuantizer for SCALAR_INT2");
                }
                validateLevels(nonUniformQuantizer, type);
                yield new Int2Strategy(nonUniformQuantizer, similarityFunction,
                        computeGlobalCentroids(nonUniformQuantizer));
            }
            case TURBO_QUANT -> {
                if (turboQuantizer == null) {
                    throw new SpectorValidationException(ErrorCode.ARGUMENT_NULL, "TurboQuantizer for TURBO_QUANT");
                }
                yield new TurboQuantStrategy(turboQuantizer, similarityFunction);
            }
            case SVASQ -> {
                if (svasqEncoder == null) {
                    throw new SpectorValidationException(ErrorCode.ARGUMENT_NULL, "SvasqEncoder for SVASQ");
                }
                yield new SvasqStrategy(svasqEncoder, similarityFunction);
            }
            case SVASQ_4 -> {
                if (svasq4Encoder == null) {
                    throw new SpectorValidationException(ErrorCode.ARGUMENT_NULL, "Svasq4Encoder for SVASQ_4");
                }
                yield new Svasq4Strategy(svasq4Encoder, similarityFunction);
            }
            case NONE -> throw new SpectorValidationException(ErrorCode.QUANTIZATION_TYPE_INVALID, "NONE");
        };
    }

    /**
     * Backward-compatible overload without Svasq4Encoder parameter.
     *
     * <p>Delegates to the full overload with {@code svasq4Encoder = null}.</p>
     */
    public static QuantizationStrategy create(
            QuantizationType type,
            ScalarQuantizer scalarQuantizer,
            NonUniformQuantizer nonUniformQuantizer,
            TurboQuantizer turboQuantizer,
            SvasqEncoder svasqEncoder,
            SimilarityFunction similarityFunction) {
        return create(type, scalarQuantizer, nonUniformQuantizer, turboQuantizer,
                svasqEncoder, null, similarityFunction);
    }

    /**
     * Creates a {@link QuantizationStrategy} for the given quantization type,
     * additionally validating that quantizer dimensions match the expected store dimension.
     *
     * <p>Use this overload when you want to enforce dimension consistency at the
     * factory level rather than relying on the strategy to detect mismatches
     * at encode time.</p>
     *
     * @param type               the quantization type
     * @param dimensions         expected vector dimensionality
     * @param scalarQuantizer    required for SCALAR_INT8
     * @param nonUniformQuantizer required for SCALAR_INT4 / SCALAR_INT2
     * @param turboQuantizer     required for TURBO_QUANT
     * @param svasqEncoder        required for SVASQ
     * @param svasq4Encoder       required for SVASQ_4
     * @param similarityFunction the distance metric
     * @return a fully initialized {@link QuantizationStrategy}
     * @throws SpectorValidationException if required quantizer missing or dimension mismatch detected
     */
    public static QuantizationStrategy createWithDimCheck(
            QuantizationType type,
            int dimensions,
            ScalarQuantizer scalarQuantizer,
            NonUniformQuantizer nonUniformQuantizer,
            TurboQuantizer turboQuantizer,
            SvasqEncoder svasqEncoder,
            Svasq4Encoder svasq4Encoder,
            SimilarityFunction similarityFunction) {

        // Dimension consistency checks (mirrors original QuantizedVectorStore validation)
        if (type == QuantizationType.SCALAR_INT8 && scalarQuantizer != null
                && scalarQuantizer.dimensions() != dimensions) {
            throw new SpectorValidationException(ErrorCode.DIMENSIONS_MISMATCH, scalarQuantizer.dimensions(), dimensions);
        }
        if ((type == QuantizationType.SCALAR_INT4 || type == QuantizationType.SCALAR_INT2)
                && nonUniformQuantizer != null
                && nonUniformQuantizer.dimensions() != dimensions) {
            throw new SpectorValidationException(ErrorCode.DIMENSIONS_MISMATCH, nonUniformQuantizer.dimensions(), dimensions);
        }
        if (type == QuantizationType.TURBO_QUANT && turboQuantizer != null
                && turboQuantizer.dimensions() != dimensions) {
            throw new SpectorValidationException(ErrorCode.DIMENSIONS_MISMATCH, turboQuantizer.dimensions(), dimensions);
        }
        if (type == QuantizationType.SVASQ && svasqEncoder != null
                && svasqEncoder.params().originalDim() != dimensions) {
            throw new SpectorValidationException(ErrorCode.DIMENSIONS_MISMATCH, svasqEncoder.params().originalDim(), dimensions);
        }
        if (type == QuantizationType.SVASQ_4 && svasq4Encoder != null
                && svasq4Encoder.params().originalDim() != dimensions) {
            throw new SpectorValidationException(ErrorCode.DIMENSIONS_MISMATCH, svasq4Encoder.params().originalDim(), dimensions);
        }

        return create(type, scalarQuantizer, nonUniformQuantizer, turboQuantizer,
                svasqEncoder, svasq4Encoder, similarityFunction);
    }

    /**
     * Backward-compatible overload without Svasq4Encoder parameter.
     */
    public static QuantizationStrategy createWithDimCheck(
            QuantizationType type,
            int dimensions,
            ScalarQuantizer scalarQuantizer,
            NonUniformQuantizer nonUniformQuantizer,
            TurboQuantizer turboQuantizer,
            SvasqEncoder svasqEncoder,
            SimilarityFunction similarityFunction) {
        return createWithDimCheck(type, dimensions, scalarQuantizer, nonUniformQuantizer,
                turboQuantizer, svasqEncoder, null, similarityFunction);
    }

    /**
     * Creates a SVASQ strategy directly from {@link SvasqParams} (convenience overload).
     *
     * @param params             calibrated SVASQ parameters
     * @param similarityFunction distance metric
     * @return a fully initialized SVASQ {@link QuantizationStrategy}
     */
    public static QuantizationStrategy createSvasq(SvasqParams params,
                                                   SimilarityFunction similarityFunction) {
        return new SvasqStrategy(params, similarityFunction);
    }

    /**
     * Creates a SVASQ-4 strategy directly from {@link SvasqParams} (convenience overload).
     *
     * @param params             calibrated SVASQ-4 parameters (bitWidth must be 4)
     * @param similarityFunction distance metric
     * @return a fully initialized SVASQ-4 {@link QuantizationStrategy}
     */
    public static QuantizationStrategy createSvasq4(SvasqParams params,
                                                    SimilarityFunction similarityFunction) {
        return new Svasq4Strategy(params, similarityFunction);
    }

    // ─────────────── Internals ───────────────

    /**
     * Computes global centroids for INT4/INT2 packed dot product lookup.
     *
     * <p>The global centroids are a single flat array of length {@code levels},
     * where each entry is the average of that level's per-dimension centroid
     * across all dimensions. Used by {@link com.spectrayan.spector.core.similarity.PackedDotProduct}.</p>
     */
    static float[] computeGlobalCentroids(NonUniformQuantizer nuq) {
        int levels = nuq.levels();
        int dims   = nuq.dimensions();
        float[] global = new float[levels];
        for (int level = 0; level < levels; level++) {
            double sum = 0.0;
            for (int dim = 0; dim < dims; dim++) {
                sum += nuq.centroids(dim)[level];
            }
            global[level] = (float) (sum / dims);
        }
        return global;
    }

    private static void validateLevels(NonUniformQuantizer nuq, QuantizationType type) {
        int expected = type.levels();
        if (nuq.levels() != expected) {
            throw new SpectorValidationException(ErrorCode.ARGUMENT_INVALID, "levels", type + " requires " + expected + " but got " + nuq.levels());
        }
    }
}