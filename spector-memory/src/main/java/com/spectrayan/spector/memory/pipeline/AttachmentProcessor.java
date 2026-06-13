/*
 * Copyright 2026 Spectrayan
 *
 * Licensed under the Business Source License 1.1 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://github.com/spectrayan/spector/blob/main/spector-memory/LICENSE
 *
 * Change Date: May 27, 2030
 * Change License: Apache License, Version 2.0
 */
package com.spectrayan.spector.memory.pipeline;

import com.spectrayan.spector.ingestion.sensory.AssetStore;
import com.spectrayan.spector.ingestion.sensory.SensoryExtractor;
import com.spectrayan.spector.ingestion.sensory.SensoryExtractor.ExtractionChunk;
import com.spectrayan.spector.memory.model.IngestionContext;
import com.spectrayan.spector.memory.model.SourceModality;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Processes attachments from {@link IngestionContext} metadata.
 *
 * <p>When a memory's metadata contains the {@value SourceModality#ATTACHMENTS_KEY} key,
 * this processor resolves each path/URI, routes to the appropriate {@link SensoryExtractor},
 * and produces {@link AttachmentResult} objects ready for ingestion as sub-memories.</p>
 *
 * <h3>URI Resolution</h3>
 * <ul>
 *   <li>{@code /path/to/file.jpg} — resolved as local file path</li>
 *   <li>{@code file:///path/to/file.jpg} — resolved as local file URI</li>
 *   <li>{@code s3://bucket/key.jpg} — downloaded via {@link AssetStore}</li>
 *   <li>{@code https://cdn.example.com/file.jpg} — downloaded via HTTP</li>
 * </ul>
 *
 * <h3>Thread Safety</h3>
 * <p>Thread-safe. Each extraction is independent.</p>
 */
public final class AttachmentProcessor {

    private static final Logger log = LoggerFactory.getLogger(AttachmentProcessor.class);

    private final List<SensoryExtractor> extractors;
    private final AssetStore assetStore; // nullable

    /**
     * Result of processing a single attachment.
     *
     * @param chunkId     sub-memory chunk ID (e.g., "parentId::attachment-0::chunk-0")
     * @param text        extracted text (caption, transcript, etc.)
     * @param metadata    merged metadata (extractor metadata + attachment context)
     * @param storedUri   URI of the stored asset (via AssetStore), or the original URI
     */
    public record AttachmentResult(
            String chunkId,
            String text,
            Map<String, String> metadata,
            String storedUri
    ) {}

    /**
     * Creates an attachment processor.
     *
     * @param extractors available sensory extractors (routed by MIME type)
     * @param assetStore optional asset store for persisting raw files (nullable)
     */
    public AttachmentProcessor(List<SensoryExtractor> extractors, AssetStore assetStore) {
        this.extractors = extractors != null ? List.copyOf(extractors) : List.of();
        this.assetStore = assetStore;
    }

    /**
     * Processes all attachments from an ingestion context.
     *
     * @param parentId the parent memory ID
     * @param context  the ingestion context containing attachment metadata
     * @return list of attachment results ready for sub-memory ingestion
     */
    public List<AttachmentResult> processAttachments(String parentId, IngestionContext context) {
        if (context == null || !context.hasAttachments()) {
            return List.of();
        }

        List<String> attachmentPaths = context.attachmentList();
        List<AttachmentResult> results = new ArrayList<>();

        for (int i = 0; i < attachmentPaths.size(); i++) {
            String attachmentPath = attachmentPaths.get(i);
            try {
                List<AttachmentResult> chunkResults = processOneAttachment(
                        parentId, attachmentPath, i);
                results.addAll(chunkResults);
                log.debug("Processed attachment {}/{}: {} → {} chunks",
                        i + 1, attachmentPaths.size(), attachmentPath, chunkResults.size());
            } catch (Exception e) {
                log.warn("Failed to process attachment '{}': {}", attachmentPath, e.getMessage(), e);
                // Continue with remaining attachments — don't fail the entire batch
            }
        }

        log.info("[Attachments] Processed {}/{} attachments → {} total chunks for parent '{}'",
                results.isEmpty() ? 0 : attachmentPaths.size(), attachmentPaths.size(),
                results.size(), parentId);

        return results;
    }

    /**
     * Processes a single attachment path/URI.
     */
    private List<AttachmentResult> processOneAttachment(String parentId, String attachmentPath,
                                                         int attachmentIndex) throws IOException {
        // Resolve to local file path
        Path localFile = resolveToLocalPath(attachmentPath);
        if (localFile == null || !Files.exists(localFile)) {
            log.warn("Attachment not found: {}", attachmentPath);
            return List.of();
        }

        // Detect MIME type
        String mimeType = detectMimeType(localFile, attachmentPath);
        if (mimeType == null) {
            log.warn("Could not detect MIME type for: {}", attachmentPath);
            return List.of();
        }

        // Find matching extractor
        SensoryExtractor extractor = findExtractor(mimeType);
        if (extractor == null) {
            log.warn("No extractor supports MIME type '{}' for: {}", mimeType, attachmentPath);
            return List.of();
        }

        // Store asset if AssetStore is configured
        String storedUri = attachmentPath;
        if (assetStore != null) {
            try {
                URI uri = assetStore.store(localFile, parentId + "::attachment-" + attachmentIndex, mimeType);
                storedUri = uri.toString();
                log.debug("Stored asset: {} → {}", attachmentPath, storedUri);
            } catch (IOException e) {
                log.warn("Failed to store asset '{}' — continuing without storage: {}",
                        attachmentPath, e.getMessage());
            }
        }

        // Extract text chunks
        List<AttachmentResult> results = new ArrayList<>();
        try (Stream<ExtractionChunk> chunks = extractor.extract(localFile, mimeType)) {
            final String finalStoredUri = storedUri;
            chunks.forEach(chunk -> {
                String subChunkId = parentId + "::attachment-" + attachmentIndex + "::" + chunk.chunkId();

                Map<String, String> mergedMetadata = new HashMap<>(chunk.metadata());
                mergedMetadata.put(SourceModality.URI_KEY, finalStoredUri);
                mergedMetadata.put("attachment_index", String.valueOf(attachmentIndex));
                mergedMetadata.put("original_path", attachmentPath);

                // Set modality from MIME type if not already set
                if (!mergedMetadata.containsKey(SourceModality.METADATA_KEY)) {
                    mergedMetadata.put(SourceModality.METADATA_KEY, modalityFromMimeType(mimeType).name());
                }

                results.add(new AttachmentResult(subChunkId, chunk.text(), mergedMetadata, finalStoredUri));
            });
        }

        // Clean up temp files created from remote URIs
        cleanupTempFile(localFile, attachmentPath);

        return results;
    }

    /**
     * Resolves a path/URI string to a local file path.
     *
     * <p>For remote URIs (s3://, https://), downloads to a temp file.</p>
     */
    private Path resolveToLocalPath(String pathOrUri) throws IOException {
        if (pathOrUri == null || pathOrUri.isBlank()) return null;

        String trimmed = pathOrUri.strip();

        // Plain file path (most common case)
        if (!trimmed.contains("://")) {
            return Path.of(trimmed);
        }

        // file:// URI
        if (trimmed.startsWith("file://")) {
            try {
                return Path.of(URI.create(trimmed));
            } catch (Exception e) {
                return Path.of(trimmed.substring("file://".length()));
            }
        }

        // Remote URI — try to download via AssetStore if available
        if (assetStore != null && (trimmed.startsWith("s3://") || trimmed.startsWith("gs://")
                || trimmed.startsWith("https://") || trimmed.startsWith("http://"))) {
            try {
                URI remoteUri = URI.create(trimmed);
                Path tempFile = Files.createTempFile("spector-attachment-", getSuffix(trimmed));
                try (InputStream is = assetStore.retrieve(remoteUri)) {
                    Files.copy(is, tempFile, StandardCopyOption.REPLACE_EXISTING);
                }
                return tempFile;
            } catch (Exception e) {
                log.warn("Failed to download remote URI '{}': {}", trimmed, e.getMessage());
                return null;
            }
        }

        // Fallback — try as plain path
        return Path.of(trimmed);
    }

    private String detectMimeType(Path file, String originalPath) {
        try {
            String probed = Files.probeContentType(file);
            if (probed != null) return probed;
        } catch (IOException ignored) {}

        // Fallback: extension-based detection
        String name = originalPath.toLowerCase();
        if (name.endsWith(".pdf")) return "application/pdf";
        if (name.endsWith(".docx")) return "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
        if (name.endsWith(".doc")) return "application/msword";
        if (name.endsWith(".html") || name.endsWith(".htm")) return "text/html";
        if (name.endsWith(".txt")) return "text/plain";
        if (name.endsWith(".md")) return "text/markdown";
        if (name.endsWith(".jpg") || name.endsWith(".jpeg")) return "image/jpeg";
        if (name.endsWith(".png")) return "image/png";
        if (name.endsWith(".gif")) return "image/gif";
        if (name.endsWith(".webp")) return "image/webp";
        if (name.endsWith(".mp3")) return "audio/mpeg";
        if (name.endsWith(".wav")) return "audio/wav";
        if (name.endsWith(".mp4")) return "video/mp4";
        if (name.endsWith(".webm")) return "video/webm";
        return null;
    }

    private SensoryExtractor findExtractor(String mimeType) {
        for (SensoryExtractor extractor : extractors) {
            if (extractor.supports(mimeType) && extractor.isAvailable()) {
                return extractor;
            }
        }
        return null;
    }

    private static SourceModality modalityFromMimeType(String mimeType) {
        if (mimeType == null) return SourceModality.TEXT;
        if (mimeType.startsWith("image/")) return SourceModality.IMAGE;
        if (mimeType.startsWith("audio/")) return SourceModality.AUDIO;
        if (mimeType.startsWith("video/")) return SourceModality.VIDEO;
        return SourceModality.TEXT;
    }

    private static String getSuffix(String path) {
        int dot = path.lastIndexOf('.');
        return dot >= 0 ? path.substring(dot) : ".tmp";
    }

    private void cleanupTempFile(Path localFile, String originalPath) {
        // Only clean up temp files created from remote downloads
        if (originalPath.contains("://") && !originalPath.startsWith("file://")) {
            try {
                Files.deleteIfExists(localFile);
            } catch (IOException ignored) {}
        }
    }
}
