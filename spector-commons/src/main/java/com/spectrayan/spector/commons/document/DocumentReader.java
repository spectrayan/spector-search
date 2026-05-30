package com.spectrayan.spector.commons.document;

import com.spectrayan.spector.commons.error.SpectorDocumentReadException;

import java.nio.file.Path;

/**
 * Interface for reading and extracting text content from document files.
 *
 * <p>Implementations handle specific formats (PDF, HTML, Markdown) and produce
 * structured text suitable for downstream processing in the RAG pipeline.</p>
 */
public interface DocumentReader {

    /**
     * Reads a document file and extracts its text content.
     *
     * @param file the path to the document file
     * @return the extracted text and metadata
     * @throws SpectorDocumentReadException if the file cannot be read or is in an unsupported format
     */
    DocumentResult read(Path file) throws SpectorDocumentReadException;

    /**
     * Returns the format this reader supports.
     *
     * @return the supported format name (e.g., "PDF", "HTML", "MARKDOWN")
     */
    String supportedFormat();
}
