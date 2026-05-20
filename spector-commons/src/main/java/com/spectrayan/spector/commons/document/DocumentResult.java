package com.spectrayan.spector.commons.document;

/**
 * Result of reading a document, containing extracted text and metadata.
 *
 * @param text     the extracted text content (non-empty on success)
 * @param metadata metadata about the source file, format, and character count
 */
public record DocumentResult(String text, DocumentMetadata metadata) {
}
