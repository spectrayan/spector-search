#!/usr/bin/env python3
"""
setup-odysseus.py — Automated Spector integration for Odysseus.

Registers Spector as an MCP server in Odysseus's database and installs
the SPECTOR_MEMORY.md skill file.

Usage:
    # From the Odysseus project root:
    python /path/to/spector/plugins/odysseus/setup-odysseus.py

    # Or with explicit paths:
    python setup-odysseus.py --odysseus-dir /path/to/odysseus --spector-jar /path/to/spector.jar

    # Docker mode (uses Docker network hostnames):
    python setup-odysseus.py --docker
"""

import argparse
import json
import os
import shutil
import sqlite3
import sys
import uuid
from pathlib import Path


# ─────────────────────────────────────────────────────────────
# Constants
# ─────────────────────────────────────────────────────────────

SCRIPT_DIR = Path(__file__).resolve().parent
SKILL_SRC = SCRIPT_DIR / "skill" / "SPECTOR_MEMORY.md"

MCP_SERVER_NAME = "Spector Cognitive Memory"
MCP_SERVER_ID = "spector-memory"

DEFAULT_SPECTOR_ARGS = [
    "--add-modules", "jdk.incubator.vector",
    "--enable-native-access=ALL-UNNAMED",
    "--enable-preview",
    "-Xms256m", "-Xmx1g",
    "-jar", "{jar_path}",
    "--mode", "odysseus",
]


def main():
    parser = argparse.ArgumentParser(
        description="Register Spector as a memory backend in Odysseus"
    )
    parser.add_argument(
        "--odysseus-dir",
        default=".",
        help="Path to Odysseus project root (default: current directory)",
    )
    parser.add_argument(
        "--spector-jar",
        default=None,
        help="Path to spector.jar (default: auto-detect)",
    )
    parser.add_argument(
        "--spector-config",
        default=None,
        help="Path to spector.yml config file",
    )
    parser.add_argument(
        "--docker",
        action="store_true",
        help="Configure for Docker deployment (uses container hostnames)",
    )
    parser.add_argument(
        "--skip-skill",
        action="store_true",
        help="Skip installing the SKILL.md file",
    )
    args = parser.parse_args()

    odysseus_dir = Path(args.odysseus_dir).resolve()
    print(f"\n⚡ Spector × Odysseus Setup\n")
    print(f"   Odysseus directory: {odysseus_dir}")

    # ── 1. Validate Odysseus installation ──
    data_dir = odysseus_dir / "data"
    db_path = data_dir / "odysseus.db"

    if not odysseus_dir.exists():
        print(f"   ❌ Odysseus directory not found: {odysseus_dir}")
        sys.exit(1)

    if not db_path.exists():
        # Try alternative locations
        alt_db = data_dir / "database.db"
        if alt_db.exists():
            db_path = alt_db
        else:
            print(f"   ⚠️  Database not found at {db_path}")
            print(f"      Skipping database registration (add Spector manually via Settings)")
            db_path = None

    # ── 2. Resolve spector.jar path ──
    jar_path = args.spector_jar
    if jar_path is None:
        # Auto-detect: check common locations
        candidates = [
            SCRIPT_DIR.parent.parent / "spector-dist" / "target" / "spector.jar",
            Path.home() / ".openclaw" / "spector" / "bin" / "spector.jar",
            Path("/opt/spector/spector.jar"),
        ]
        for candidate in candidates:
            if candidate.exists():
                jar_path = str(candidate)
                break

        if jar_path is None and not args.docker:
            print("   ⚠️  Could not find spector.jar automatically.")
            print("      Specify with --spector-jar /path/to/spector.jar")
            print("      Or download from: https://github.com/spectrayan/spector/releases")
            jar_path = "/path/to/spector.jar"  # placeholder

    if args.docker:
        jar_path = "/opt/spector/spector.jar"

    print(f"   Spector JAR: {jar_path}")

    # ── 3. Build MCP server args ──
    mcp_args = []
    for arg in DEFAULT_SPECTOR_ARGS:
        if arg == "{jar_path}":
            mcp_args.append(jar_path)
        else:
            mcp_args.append(arg)

    # Add config if specified
    if args.spector_config:
        mcp_args.extend(["--config", args.spector_config])
    elif args.docker:
        mcp_args.extend(["--config", "/opt/spector/spector.yml"])

    # ── 4. Register MCP server in Odysseus database ──
    if db_path and db_path.exists():
        register_mcp_server(db_path, mcp_args)
    else:
        print("\n   📋 Manual registration (paste into Settings → MCP Servers):")
        print(f"      Command: java")
        print(f"      Args: {json.dumps(mcp_args)}")

    # ── 5. Install SKILL.md ──
    if not args.skip_skill:
        install_skill(odysseus_dir)

    # ── 6. Docker guidance ──
    if args.docker:
        print_docker_guidance(odysseus_dir)

    print(f"\n   ✅ Setup complete!")
    print(f"      Restart Odysseus to activate Spector memory.\n")


def register_mcp_server(db_path: Path, mcp_args: list):
    """Register Spector as an MCP server in Odysseus's SQLite database."""
    print(f"\n   📦 Registering MCP server in database...")

    try:
        conn = sqlite3.connect(str(db_path))
        cursor = conn.cursor()

        # Check if the mcp_servers table exists
        cursor.execute(
            "SELECT name FROM sqlite_master WHERE type='table' AND name='mcp_servers'"
        )
        if not cursor.fetchone():
            print("   ⚠️  mcp_servers table not found. Add Spector manually via Settings.")
            conn.close()
            return

        # Check if Spector is already registered
        cursor.execute("SELECT id FROM mcp_servers WHERE id = ?", (MCP_SERVER_ID,))
        existing = cursor.fetchone()

        if existing:
            # Update existing entry
            cursor.execute(
                """UPDATE mcp_servers
                   SET name = ?, command = ?, args = ?, transport = ?, is_enabled = 1
                   WHERE id = ?""",
                (MCP_SERVER_NAME, "java", json.dumps(mcp_args), "stdio", MCP_SERVER_ID),
            )
            print(f"   ✅ Updated existing Spector MCP server")
        else:
            # Insert new entry
            cursor.execute(
                """INSERT INTO mcp_servers (id, name, transport, command, args, is_enabled)
                   VALUES (?, ?, ?, ?, ?, ?)""",
                (MCP_SERVER_ID, MCP_SERVER_NAME, "stdio", "java", json.dumps(mcp_args), 1),
            )
            print(f"   ✅ Registered Spector MCP server (id={MCP_SERVER_ID})")

        conn.commit()
        conn.close()

    except Exception as e:
        print(f"   ❌ Database error: {e}")
        print(f"      Add Spector manually via Settings → MCP Servers")


def install_skill(odysseus_dir: Path):
    """Copy the SPECTOR_MEMORY.md skill file to Odysseus's skills directory."""
    print(f"\n   📚 Installing Spector skill file...")

    skills_dir = odysseus_dir / "data" / "skills"
    skills_dir.mkdir(parents=True, exist_ok=True)

    dest = skills_dir / "SPECTOR_MEMORY.md"

    if SKILL_SRC.exists():
        shutil.copy2(SKILL_SRC, dest)
        print(f"   ✅ Installed: {dest}")
    else:
        print(f"   ⚠️  Skill source not found: {SKILL_SRC}")
        print(f"      Download from: https://github.com/spectrayan/spector/tree/main/plugins/odysseus/skill/")


def print_docker_guidance(odysseus_dir: Path):
    """Print Docker-specific setup instructions."""
    compose_file = SCRIPT_DIR / "docker" / "docker-compose.spector.yml"
    rel_path = os.path.relpath(compose_file, odysseus_dir)

    print(f"\n   🐳 Docker Setup:")
    print(f"      Add to your .env file:")
    print(f"        COMPOSE_FILE=docker-compose.yml:{rel_path}")
    print(f"      Or run:")
    print(f"        docker compose -f docker-compose.yml -f {rel_path} up -d")


if __name__ == "__main__":
    main()
