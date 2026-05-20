package com.spectrayan.spector.commons.document;

/**
 * Metadata about a successfully extracted document.
 *
 * @param sourceFile     the name of the source file
 * @param format         the detected format (PDF, HTML, MARKDOWN)
 * @param characterCount the number of characters in the extracted text
 */
public record DocumentMetadata(String sourceFile, String format, int characterCount) {
}
