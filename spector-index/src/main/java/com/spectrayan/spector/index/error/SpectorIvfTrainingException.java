package com.spectrayan.spector.index.error;

import com.spectrayan.spector.commons.error.*;

/**
 * Exception thrown when IVF centroid training fails during calibration.
 *
 * @see SpectorIndexException
 */
public class SpectorIvfTrainingException extends SpectorIndexException {

    private final String details;

    public SpectorIvfTrainingException(String details) {
        super(ErrorCode.IVF_TRAINING_FAILED, details);
        this.details = details;
    }

    public SpectorIvfTrainingException(String details, Throwable cause) {
        super(ErrorCode.IVF_TRAINING_FAILED, cause, details);
        this.details = details;
    }

    /** Returns the details of the IVF training failure. */
    public String getDetails() {
        return details;
    }
}
