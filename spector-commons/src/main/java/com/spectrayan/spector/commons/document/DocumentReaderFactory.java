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
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Factory that selects the appropriate {@link DocumentReader} based on file extension.
 *
 * <p>Supported formats: PDF, HTML, Markdown.</p>
 */
public final class DocumentReaderFactory {

    private static final List<String> SUPPORTED_FORMATS = List.of("PDF", "HTML", "MARKDOWN");

    private static final Map<String, DocumentReader> READERS = Map.of(
            "pdf", new PdfDocumentReader(),
            "html", new HtmlDocumentReader(),
            "htm", new HtmlDocumentReader(),
            "md", new MarkdownDocumentReader(),
            "markdown", new MarkdownDocumentReader()
    );

    private DocumentReaderFactory() {
    }

    /**
     * Returns the appropriate reader for the given file based on its extension.
     *
     * @param file the path to the document
     * @return the reader for the detected format
     * @throws SpectorDocumentReadException if the format is unsupported
     */
    public static DocumentReader getReader(Path file) throws SpectorDocumentReadException {
        String fileName = file.getFileName().toString();
        String extension = getExtension(fileName).toLowerCase(Locale.ROOT);

        DocumentReader reader = READERS.get(extension);
        if (reader == null) {
            throw new SpectorDocumentReadException(fileName,
                    "unsupported format '.%s'. Supported formats: %s".formatted(extension, SUPPORTED_FORMATS));
        }
        return reader;
    }

    /**
     * Reads a document file, automatically detecting the format from the file extension.
     *
     * @param file the path to the document
     * @return the extracted text and metadata
     * @throws SpectorDocumentReadException if the format is unsupported or the file cannot be read
     */
    public static DocumentResult read(Path file) throws SpectorDocumentReadException {
        return getReader(file).read(file);
    }

    /**
     * Returns the list of supported format names.
     */
    public static List<String> supportedFormats() {
        return SUPPORTED_FORMATS;
    }

    private static String getExtension(String fileName) {
        int lastDot = fileName.lastIndexOf('.');
        if (lastDot < 0 || lastDot == fileName.length() - 1) {
            throw new SpectorDocumentReadException(fileName,
                    "unsupported format (no file extension). Supported formats: " + SUPPORTED_FORMATS);
        }
        return fileName.substring(lastDot + 1);
    }
}
