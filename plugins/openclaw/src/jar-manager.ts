/**
 * JAR Manager — Auto-downloads and manages spector.jar versions.
 *
 * Downloads from GitHub Releases, stores in ~/.openclaw/spector/bin/,
 * and tracks version via a local version.json file.
 *
 * @module jar-manager
 */

import { createWriteStream, existsSync, mkdirSync, readFileSync, writeFileSync } from "node:fs";
import { dirname } from "node:path";
import { pipeline } from "node:stream/promises";
import { Readable } from "node:stream";
import { SpectorPaths } from "./config.js";

// ─────────────────────────────────────────────────────────────────────
// Types
// ─────────────────────────────────────────────────────────────────────

/** Version tracking metadata. */
interface VersionInfo {
  readonly version: string;
  readonly downloadedAt: string;
  readonly lastChecked: string;
  readonly jarSize: number;
}

// ─────────────────────────────────────────────────────────────────────
// Constants
// ─────────────────────────────────────────────────────────────────────

/** GitHub Releases URL for spector.jar. */
const RELEASE_URL =
  "https://github.com/spectrayan/spector/releases/latest/download/spector.jar";

/** GitHub API URL for latest release metadata. */
const RELEASE_API_URL =
  "https://api.github.com/repos/spectrayan/spector/releases/latest";

/** Minimum time between update checks (24 hours). */
const CHECK_INTERVAL_MS = 24 * 60 * 60 * 1000;

// ─────────────────────────────────────────────────────────────────────
// Public API
// ─────────────────────────────────────────────────────────────────────

/**
 * Ensure spector.jar is available locally, downloading if needed.
 *
 * @param log  Logger function for progress messages.
 * @returns    Path to the local spector.jar.
 */
export async function ensureJar(
  log: (msg: string) => void
): Promise<string> {
  const jarPath = SpectorPaths.jarPath;

  if (existsSync(jarPath)) {
    log(`[Spector] JAR found: ${jarPath}`);
    return jarPath;
  }

  log("[Spector] Downloading spector.jar from GitHub Releases...");
  await downloadJar(log);
  return jarPath;
}

/**
 * Check for updates if the last check was more than 24 hours ago.
 *
 * @param log  Logger function.
 * @returns    True if an update is available.
 */
export async function checkForUpdates(
  log: (msg: string) => void
): Promise<boolean> {
  const versionInfo = readVersionInfo();

  if (versionInfo) {
    const lastChecked = new Date(versionInfo.lastChecked).getTime();
    const now = Date.now();
    if (now - lastChecked < CHECK_INTERVAL_MS) {
      return false; // Too soon to check again
    }
  }

  try {
    const response = await fetch(RELEASE_API_URL, {
      headers: { Accept: "application/vnd.github.v3+json" },
    });

    if (!response.ok) {
      log(`[Spector] Update check failed: HTTP ${response.status}`);
      return false;
    }

    const release = (await response.json()) as { tag_name: string };
    const latestVersion = release.tag_name;

    // Update last-checked timestamp
    if (versionInfo) {
      writeVersionInfo({
        ...versionInfo,
        lastChecked: new Date().toISOString(),
      });
    }

    if (versionInfo && versionInfo.version !== latestVersion) {
      log(
        `[Spector] Update available: ${versionInfo.version} → ${latestVersion}`
      );
      return true;
    }

    return false;
  } catch {
    // Network errors are non-fatal for update checks
    return false;
  }
}

/**
 * Download the latest spector.jar from GitHub Releases.
 *
 * @param log  Logger function for progress reporting.
 */
export async function downloadJar(
  log: (msg: string) => void
): Promise<void> {
  const binDir = SpectorPaths.binDir;
  const jarPath = SpectorPaths.jarPath;

  // Ensure bin directory exists
  if (!existsSync(binDir)) {
    mkdirSync(binDir, { recursive: true });
  }

  log(`[Spector] Downloading from: ${RELEASE_URL}`);

  const response = await fetch(RELEASE_URL, { redirect: "follow" });

  if (!response.ok) {
    throw new Error(
      `Failed to download spector.jar: HTTP ${response.status} ${response.statusText}`
    );
  }

  if (!response.body) {
    throw new Error("Download response has no body");
  }

  // Stream to disk
  const fileStream = createWriteStream(jarPath);
  const readable = Readable.fromWeb(
    response.body as import("node:stream/web").ReadableStream
  );
  await pipeline(readable, fileStream);

  // Get file size for version tracking
  const { size } = await import("node:fs").then((fs) =>
    fs.promises.stat(jarPath)
  );

  // Fetch latest version tag for tracking
  let version = "unknown";
  try {
    const releaseResponse = await fetch(RELEASE_API_URL, {
      headers: { Accept: "application/vnd.github.v3+json" },
    });
    if (releaseResponse.ok) {
      const release = (await releaseResponse.json()) as { tag_name: string };
      version = release.tag_name;
    }
  } catch {
    // Non-fatal
  }

  writeVersionInfo({
    version,
    downloadedAt: new Date().toISOString(),
    lastChecked: new Date().toISOString(),
    jarSize: size,
  });

  log(
    `[Spector] ✅ Downloaded spector.jar (${(size / 1024 / 1024).toFixed(1)} MB, version: ${version})`
  );
}

// ─────────────────────────────────────────────────────────────────────
// Internal Helpers
// ─────────────────────────────────────────────────────────────────────

function readVersionInfo(): VersionInfo | null {
  const versionFile = SpectorPaths.versionFile;
  if (!existsSync(versionFile)) return null;

  try {
    const content = readFileSync(versionFile, "utf-8");
    return JSON.parse(content) as VersionInfo;
  } catch {
    return null;
  }
}

function writeVersionInfo(info: VersionInfo): void {
  const versionFile = SpectorPaths.versionFile;
  const dir = dirname(versionFile);
  if (!existsSync(dir)) {
    mkdirSync(dir, { recursive: true });
  }
  writeFileSync(versionFile, JSON.stringify(info, null, 2), "utf-8");
}
