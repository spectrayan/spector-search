package com.spectrayan.spector.commons.document;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.InflaterInputStream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Reads PDF documents and extracts text content preserving paragraph boundaries.
 *
 * <p>Uses a lightweight built-in PDF text extraction approach without external
 * dependencies. Handles standard PDF text streams (both raw and deflate-compressed).
 * Paragraphs are separated by double newline characters in the output.</p>
 */
public final class PdfDocumentReader implements DocumentReader {

    private static final Logger LOG = LoggerFactory.getLogger(PdfDocumentReader.class);
    private static final long MAX_FILE_SIZE = 100L * 1024 * 1024; // 100 MB
    private static final byte[] PDF_HEADER = {'%', 'P', 'D', 'F', '-'};

    // Pattern to find stream content
    private static final Pattern STREAM_PATTERN = Pattern.compile(
            "stream\\r?\\n(.*?)endstream", Pattern.DOTALL);

    // Pattern to extract text between BT and ET operators
    private static final Pattern TEXT_BLOCK = Pattern.compile("BT(.*?)ET", Pattern.DOTALL);

    // Pattern to extract text strings from PDF text operators: Tj, TJ, '
    private static final Pattern TEXT_STRING = Pattern.compile(
            "\\(([^)]*?)\\)|<([0-9a-fA-F]+)>");

    // Pattern for PDF text positioning that indicates paragraph breaks
    private static final Pattern TD_OPERATOR = Pattern.compile(
            "(-?[\\d.]+)\\s+(-?[\\d.]+)\\s+Td");

    @Override
    public DocumentResult read(Path file) throws DocumentReadException {
        String fileName = file.getFileName().toString();

        validateFile(file, fileName);
        validatePdfFormat(file, fileName);

        try {
            byte[] content = Files.readAllBytes(file);
            String text = extractText(content);

            if (text.isEmpty()) {
                throw new DocumentReadException(fileName, "PDF contains no extractable text");
            }

            var metadata = new DocumentMetadata(fileName, "PDF", text.length());
            return new DocumentResult(text, metadata);

        } catch (DocumentReadException e) {
            throw e;
        } catch (IOException e) {
            throw new DocumentReadException(fileName, "corrupted or unreadable PDF file", e);
        } catch (Exception e) {
            throw new DocumentReadException(fileName,
                    "unexpected error reading PDF: " + e.getMessage(), e);
        }
    }

    @Override
    public String supportedFormat() {
        return "PDF";
    }

    private void validateFile(Path file, String fileName) {
        if (!Files.exists(file)) {
            throw new DocumentReadException(fileName, "file does not exist");
        }
        try {
            long size = Files.size(file);
            if (size > MAX_FILE_SIZE) {
                throw new DocumentReadException(fileName,
                        "file size %d bytes exceeds the 100 MB limit".formatted(size));
            }
        } catch (IOException e) {
            throw new DocumentReadException(fileName, "unable to determine file size", e);
        }
    }

    private void validatePdfFormat(Path file, String fileName) {
        try (RandomAccessFile raf = new RandomAccessFile(file.toFile(), "r")) {
            byte[] header = new byte[5];
            if (raf.read(header) < 5) {
                throw new DocumentReadException(fileName, "file is too small to be a valid PDF");
            }
            for (int i = 0; i < PDF_HEADER.length; i++) {
                if (header[i] != PDF_HEADER[i]) {
                    throw new DocumentReadException(fileName,
                            "corrupted or unreadable PDF file (invalid header)");
                }
            }
        } catch (DocumentReadException e) {
            throw e;
        } catch (IOException e) {
            throw new DocumentReadException(fileName, "corrupted or unreadable PDF file", e);
        }
    }

    private String extractText(byte[] pdfBytes) {
        String pdfContent = new String(pdfBytes, StandardCharsets.ISO_8859_1);
        List<String> paragraphs = new ArrayList<>();

        // Find all stream objects and try to extract text
        Matcher streamMatcher = STREAM_PATTERN.matcher(pdfContent);
        while (streamMatcher.find()) {
            String streamData = streamMatcher.group(1);
            String decoded = tryDecodeStream(streamData, pdfBytes, streamMatcher.start(1));

            if (decoded != null && !decoded.isBlank()) {
                List<String> extracted = extractTextFromStream(decoded);
                paragraphs.addAll(extracted);
            }
        }

        // Also try extracting text blocks directly from uncompressed content
        List<String> directText = extractTextFromStream(pdfContent);
        if (!directText.isEmpty() && paragraphs.isEmpty()) {
            paragraphs.addAll(directText);
        }

        return normalizeParagraphs(paragraphs);
    }

    private String tryDecodeStream(String streamData, byte[] pdfBytes, int offset) {
        // Try as raw text first
        if (streamData.contains("BT") && streamData.contains("ET")) {
            return streamData;
        }

        // Try deflate decompression
        try {
            byte[] streamBytes = new byte[streamData.length()];
            for (int i = 0; i < streamData.length(); i++) {
                streamBytes[i] = (byte) streamData.charAt(i);
            }
            InputStream is = new InflaterInputStream(
                    new java.io.ByteArrayInputStream(streamBytes));
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            int read;
            while ((read = is.read(buffer)) != -1) {
                baos.write(buffer, 0, read);
            }
            return baos.toString(StandardCharsets.ISO_8859_1);
        } catch (Exception e) {
            // Not a deflate stream or corrupted — skip
            return null;
        }
    }

    private List<String> extractTextFromStream(String content) {
        List<String> paragraphs = new ArrayList<>();
        Matcher btEt = TEXT_BLOCK.matcher(content);

        while (btEt.find()) {
            String block = btEt.group(1);
            StringBuilder paragraph = new StringBuilder();

            // Check for large Y movements that indicate paragraph breaks
            Matcher tdMatcher = TD_OPERATOR.matcher(block);
            boolean hasParagraphBreak = false;
            while (tdMatcher.find()) {
                float yMove = Float.parseFloat(tdMatcher.group(2));
                if (Math.abs(yMove) > 14.0f) { // Large vertical move = paragraph break
                    hasParagraphBreak = true;
                    break;
                }
            }

            // Extract text strings
            Matcher textMatcher = TEXT_STRING.matcher(block);
            while (textMatcher.find()) {
                String textLiteral = textMatcher.group(1);
                String textHex = textMatcher.group(2);

                if (textLiteral != null) {
                    paragraph.append(decodePdfString(textLiteral));
                } else if (textHex != null && textHex.length() % 2 == 0) {
                    paragraph.append(decodeHexString(textHex));
                }
            }

            String text = paragraph.toString().strip();
            if (!text.isEmpty()) {
                paragraphs.add(text);
            }
        }

        return paragraphs;
    }

    private String decodePdfString(String str) {
        // Handle basic PDF escape sequences
        return str.replace("\\n", "\n")
                .replace("\\r", "\r")
                .replace("\\t", "\t")
                .replace("\\(", "(")
                .replace("\\)", ")")
                .replace("\\\\", "\\");
    }

    private String decodeHexString(String hex) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < hex.length() - 1; i += 2) {
            int charCode = Integer.parseInt(hex.substring(i, i + 2), 16);
            if (charCode >= 32 && charCode < 127) {
                sb.append((char) charCode);
            }
        }
        return sb.toString();
    }

    private String normalizeParagraphs(List<String> paragraphs) {
        if (paragraphs.isEmpty()) return "";

        StringBuilder result = new StringBuilder();
        for (int i = 0; i < paragraphs.size(); i++) {
            if (i > 0) {
                result.append("\n\n");
            }
            result.append(paragraphs.get(i));
        }
        return result.toString();
    }
}
