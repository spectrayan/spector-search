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
