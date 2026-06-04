/**
 * JDK Manager — Auto-downloads Eclipse Temurin JDK 25 if no compatible
 * Java installation is found.
 *
 * Stores the JDK in ~/.openclaw/spector/jdk/ and resolves the correct
 * platform-specific archive (tar.gz for Linux/macOS, zip for Windows).
 *
 * @module jdk-manager
 */

import {
  existsSync,
  mkdirSync,
  createWriteStream,
  readdirSync,
  chmodSync,
} from "node:fs";
import { join } from "node:path";
import { execSync } from "node:child_process";
import { pipeline } from "node:stream/promises";
import { Readable } from "node:stream";
import { SpectorPaths } from "./config.js";

// ─────────────────────────────────────────────────────────────────────
// Constants
// ─────────────────────────────────────────────────────────────────────

/** Minimum required Java major version. */
const MIN_JAVA_VERSION = 25;

/** Adoptium API base URL. */
const ADOPTIUM_API =
  "https://api.adoptium.net/v3/assets/latest/25/hotspot";

// ─────────────────────────────────────────────────────────────────────
// Public API
// ─────────────────────────────────────────────────────────────────────

/** Result of Java detection. */
export interface JavaDetection {
  /** Resolved path to the `java` binary. */
  readonly javaPath: string;
  /** Detected major version number. */
  readonly majorVersion: number;
  /** Source of the detection (system, JAVA_HOME, auto-downloaded). */
  readonly source: "system" | "java-home" | "auto-downloaded";
}

/**
 * Detect or install a compatible Java 25+ runtime.
 *
 * Only downloads a JDK if no compatible version is found anywhere.
 *
 * Detection order:
 * 1. JAVA_HOME environment variable
 * 2. System PATH (`java` command)
 * 3. Previously auto-downloaded Temurin in ~/.openclaw/spector/jdk/
 * 4. If none found or all versions < 25 → auto-download Temurin JDK 25
 *
 * @param log  Logger function.
 * @returns    Detection result with the resolved java path.
 */
export async function ensureJava(
  log: (msg: string) => void
): Promise<JavaDetection> {
  // 1. Check JAVA_HOME (user's explicit preference)
  const javaHome = process.env["JAVA_HOME"];
  if (javaHome) {
    const javaBin = resolveJavaBin(javaHome);
    if (javaBin) {
      const version = detectVersion(javaBin);
      if (version >= MIN_JAVA_VERSION) {
        log(`[Spector] ✅ Using JAVA_HOME: ${javaHome} (Java ${version})`);
        return { javaPath: javaBin, majorVersion: version, source: "java-home" };
      }
      if (version > 0) {
        log(`[Spector] JAVA_HOME has Java ${version}, need ${MIN_JAVA_VERSION}+. Checking other sources...`);
      }
    }
  }

  // 2. Check system PATH
  const systemVersion = detectVersion("java");
  if (systemVersion >= MIN_JAVA_VERSION) {
    log(`[Spector] ✅ Using system Java ${systemVersion} from PATH`);
    return { javaPath: "java", majorVersion: systemVersion, source: "system" };
  }
  if (systemVersion > 0) {
    log(`[Spector] System Java is version ${systemVersion}, need ${MIN_JAVA_VERSION}+. Checking other sources...`);
  }

  // 3. Check previously auto-downloaded JDK
  const autoJava = findAutoDownloadedJava();
  if (autoJava) {
    const version = detectVersion(autoJava);
    if (version >= MIN_JAVA_VERSION) {
      log(`[Spector] ✅ Using previously downloaded JDK: Java ${version}`);
      return { javaPath: autoJava, majorVersion: version, source: "auto-downloaded" };
    }
    if (version > 0) {
      log(`[Spector] Previously downloaded JDK is version ${version}, need ${MIN_JAVA_VERSION}+.`);
    }
  }

  // 4. No compatible Java found anywhere — download Temurin
  const foundVersion = systemVersion || (javaHome ? detectVersion(resolveJavaBin(javaHome) ?? "") : 0);
  if (foundVersion > 0) {
    log(`[Spector] ⚠️  Found Java ${foundVersion} but Spector requires ${MIN_JAVA_VERSION}+. Downloading Eclipse Temurin JDK ${MIN_JAVA_VERSION}...`);
  } else {
    log(`[Spector] ⚠️  No Java installation found. Downloading Eclipse Temurin JDK ${MIN_JAVA_VERSION}...`);
  }

  const downloadedPath = await downloadTemurin(log);
  const downloadedVersion = detectVersion(downloadedPath);

  if (downloadedVersion < MIN_JAVA_VERSION) {
    throw new Error(
      `Downloaded JDK reports version ${downloadedVersion}, but ${MIN_JAVA_VERSION}+ is required`
    );
  }

  return {
    javaPath: downloadedPath,
    majorVersion: downloadedVersion,
    source: "auto-downloaded",
  };
}

// ─────────────────────────────────────────────────────────────────────
// Internal Helpers
// ─────────────────────────────────────────────────────────────────────

/**
 * Detect the major Java version from a java binary.
 * Returns 0 if detection fails.
 */
function detectVersion(javaBin: string): number {
  try {
    const output = execSync(`"${javaBin}" -version 2>&1`, {
      encoding: "utf-8",
      timeout: 10_000,
      stdio: ["pipe", "pipe", "pipe"],
    });

    // Parse version from output like: openjdk version "25" or "25.0.1"
    const match = output.match(/version\s+"?(\d+)/);
    if (match) {
      return parseInt(match[1], 10);
    }
    return 0;
  } catch {
    return 0;
  }
}

/**
 * Find the java binary inside the auto-downloaded JDK directory.
 */
function findAutoDownloadedJava(): string | null {
  const jdkDir = SpectorPaths.jdkDir;
  if (!existsSync(jdkDir)) return null;

  // Temurin extracts into a subdirectory like jdk-25+36
  const entries = readdirSync(jdkDir);
  for (const entry of entries) {
    const javaBin = resolveJavaBin(join(jdkDir, entry));
    if (javaBin) return javaBin;
  }

  // Also check direct bin/java
  const directBin = resolveJavaBin(jdkDir);
  return directBin;
}

/**
 * Resolve the java binary path inside a JDK directory.
 */
function resolveJavaBin(jdkHome: string): string | null {
  const candidates = [
    join(jdkHome, "bin", "java"),
    join(jdkHome, "bin", "java.exe"),
    // macOS: Contents/Home/bin/java
    join(jdkHome, "Contents", "Home", "bin", "java"),
  ];

  for (const candidate of candidates) {
    if (existsSync(candidate)) return candidate;
  }

  return null;
}

/**
 * Detect the current platform for Adoptium API requests.
 */
function detectPlatform(): { os: string; arch: string; ext: string } {
  const platform = process.platform;
  const arch = process.arch;

  let os: string;
  let ext: string;

  switch (platform) {
    case "win32":
      os = "windows";
      ext = "zip";
      break;
    case "darwin":
      os = "mac";
      ext = "tar.gz";
      break;
    default:
      os = "linux";
      ext = "tar.gz";
  }

  let adoptiumArch: string;
  switch (arch) {
    case "arm64":
      adoptiumArch = "aarch64";
      break;
    case "x64":
      adoptiumArch = "x64";
      break;
    default:
      adoptiumArch = "x64";
  }

  return { os, arch: adoptiumArch, ext };
}

/**
 * Download Eclipse Temurin JDK 25 and extract it.
 */
async function downloadTemurin(
  log: (msg: string) => void
): Promise<string> {
  const jdkDir = SpectorPaths.jdkDir;
  if (!existsSync(jdkDir)) {
    mkdirSync(jdkDir, { recursive: true });
  }

  const { os, arch, ext } = detectPlatform();

  // Query Adoptium API for latest JDK 25
  const apiUrl = `${ADOPTIUM_API}?architecture=${arch}&image_type=jdk&os=${os}&vendor=eclipse`;
  log(`[Spector] Querying Adoptium API: ${apiUrl}`);

  const apiResponse = await fetch(apiUrl, {
    headers: { Accept: "application/json" },
  });

  if (!apiResponse.ok) {
    throw new Error(
      `Adoptium API failed: HTTP ${apiResponse.status}. Please install Java ${MIN_JAVA_VERSION}+ manually.`
    );
  }

  const assets = (await apiResponse.json()) as Array<{
    binary: {
      package: { link: string; name: string; size: number };
    };
  }>;

  if (assets.length === 0) {
    throw new Error(
      `No Temurin JDK ${MIN_JAVA_VERSION} builds found for ${os}/${arch}. Please install Java manually.`
    );
  }

  const downloadUrl = assets[0].binary.package.link;
  const fileName = assets[0].binary.package.name;
  const fileSize = assets[0].binary.package.size;

  log(
    `[Spector] Downloading ${fileName} (${(fileSize / 1024 / 1024).toFixed(0)} MB)...`
  );

  const downloadResponse = await fetch(downloadUrl, { redirect: "follow" });
  if (!downloadResponse.ok || !downloadResponse.body) {
    throw new Error(`JDK download failed: HTTP ${downloadResponse.status}`);
  }

  // Save archive to temp location
  const archivePath = join(jdkDir, fileName);
  const fileStream = createWriteStream(archivePath);
  const readable = Readable.fromWeb(
    downloadResponse.body as import("node:stream/web").ReadableStream
  );
  await pipeline(readable, fileStream);

  log("[Spector] Extracting JDK...");

  // Extract based on platform
  if (ext === "zip") {
    // Windows: use PowerShell to extract
    execSync(
      `powershell -Command "Expand-Archive -Path '${archivePath}' -DestinationPath '${jdkDir}' -Force"`,
      { timeout: 120_000 }
    );
  } else {
    // Linux/macOS: use tar
    execSync(`tar -xzf "${archivePath}" -C "${jdkDir}"`, {
      timeout: 120_000,
    });
  }

  // Clean up archive
  try {
    const { unlinkSync } = await import("node:fs");
    unlinkSync(archivePath);
  } catch {
    // Non-fatal
  }

  // Find extracted java binary
  const javaBin = findAutoDownloadedJava();
  if (!javaBin) {
    throw new Error("JDK extracted but java binary not found");
  }

  // Ensure executable on Unix
  if (process.platform !== "win32") {
    try {
      chmodSync(javaBin, 0o755);
    } catch {
      // Non-fatal
    }
  }

  log(`[Spector] ✅ JDK installed: ${javaBin}`);
  return javaBin;
}
