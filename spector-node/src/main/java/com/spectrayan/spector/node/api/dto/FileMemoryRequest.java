package com.spectrayan.spector.node.api.dto;

/**
 * Request DTO for directory ingestion ({@code POST /api/v1/memory/ingest-directory}).
 *
 * <p>Specifies a local filesystem directory path along with ingestion
 * configuration (file pattern, chunking parameters, directories to skip).
 * The endpoint walks the directory tree and ingests each matching file
 * through the unified {@code IngestionPipeline}.</p>
 */
public class FileMemoryRequest {

    /** Absolute path to the directory to ingest (required). */
    private String path;

    /** Glob file pattern, e.g. {@code "**\/*.md,**\/*.java"}. Defaults to common text formats. */
    private String filePattern;

    /** Chunk size in characters. Defaults to 800. */
    private int chunkSize;

    /** Overlap between chunks in characters. Defaults to 100. */
    private int chunkOverlap;

    /** Comma-separated directory names to skip. Defaults to common build/VCS dirs. */
    private String skipDirs;

    public FileMemoryRequest() {}

    public String path() { return path; }
    public void setPath(String path) { this.path = path; }

    public String filePattern() { return filePattern; }
    public void setFilePattern(String filePattern) { this.filePattern = filePattern; }

    public int chunkSize() { return chunkSize; }
    public void setChunkSize(int chunkSize) { this.chunkSize = chunkSize; }

    public int chunkOverlap() { return chunkOverlap; }
    public void setChunkOverlap(int chunkOverlap) { this.chunkOverlap = chunkOverlap; }

    public String skipDirs() { return skipDirs; }
    public void setSkipDirs(String skipDirs) { this.skipDirs = skipDirs; }
}
