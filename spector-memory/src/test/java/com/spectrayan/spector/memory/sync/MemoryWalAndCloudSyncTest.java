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
package com.spectrayan.spector.memory.sync;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class MemoryWalTest {

    @Test
    void appendAssignsMonotonicSequence() {
        var wal = new MemoryWal();
        var e1 = wal.appendRemember("m1", new byte[]{1, 2, 3});
        var e2 = wal.appendRemember("m2", new byte[]{4, 5, 6});
        var e3 = wal.appendForget("m1");

        assertThat(e1.sequence()).isEqualTo(1);
        assertThat(e2.sequence()).isEqualTo(2);
        assertThat(e3.sequence()).isEqualTo(3);
        assertThat(wal.highWaterMark()).isEqualTo(3);
    }

    @Test
    void replayFromSequence() {
        var wal = new MemoryWal();
        wal.appendRemember("m1", null);
        wal.appendRemember("m2", null);
        wal.appendRemember("m3", null);

        var afterFirst = wal.replay(1);
        assertThat(afterFirst).hasSize(2);
        assertThat(afterFirst.getFirst().memoryId()).isEqualTo("m2");
    }

    @Test
    void replayAllFromZero() {
        var wal = new MemoryWal();
        wal.appendRemember("m1", null);
        wal.appendForget("m2");
        wal.appendReinforce("m1", (byte) 50);

        var all = wal.replay(0);
        assertThat(all).hasSize(3);
    }

    @Test
    void eventTypesAreCaptured() {
        var wal = new MemoryWal();
        wal.appendRemember("m1", null);
        wal.appendForget("m1");
        wal.appendReinforce("m1", (byte) 50);

        var events = wal.replay(0);
        assertThat(events.get(0).type()).isEqualTo(WalEvent.EventType.REMEMBER);
        assertThat(events.get(1).type()).isEqualTo(WalEvent.EventType.FORGET);
        assertThat(events.get(2).type()).isEqualTo(WalEvent.EventType.REINFORCE);
    }

    @Test
    void sizeTracksEventCount() {
        var wal = new MemoryWal();
        assertThat(wal.size()).isZero();

        wal.appendRemember("m1", null);
        assertThat(wal.size()).isEqualTo(1);

        wal.appendForget("m1");
        assertThat(wal.size()).isEqualTo(2);
    }
}

class CloudSyncTest {

    @Test
    void exportEventsFromWal() {
        var wal = new MemoryWal();
        wal.appendRemember("m1", null);
        wal.appendRemember("m2", null);
        wal.appendRemember("m3", null);

        var sync = new CloudSync(wal);
        var events = sync.exportEvents(1); // after m1
        assertThat(events).hasSize(2);
    }

    @Test
    void importEventsUpdatesHighWaterMark() {
        var localWal = new MemoryWal();
        var sync = new CloudSync(localWal);

        // Simulate remote events
        var remoteEvents = List.of(
                new WalEvent(1, WalEvent.EventType.REMEMBER, "m1",
                        java.time.Instant.now(), new byte[0]),
                new WalEvent(2, WalEvent.EventType.REMEMBER, "m2",
                        java.time.Instant.now(), new byte[0])
        );

        List<String> replayed = new ArrayList<>();
        sync.importEvents(remoteEvents, event -> replayed.add(event.memoryId()));

        assertThat(replayed).containsExactly("m1", "m2");
        assertThat(sync.remoteHighWaterMark()).isEqualTo(2);
    }

    @Test
    void importSkipsDuplicates() {
        var localWal = new MemoryWal();
        var sync = new CloudSync(localWal);

        var events = List.of(
                new WalEvent(1, WalEvent.EventType.REMEMBER, "m1",
                        java.time.Instant.now(), new byte[0])
        );

        List<String> replayed = new ArrayList<>();
        sync.importEvents(events, e -> replayed.add(e.memoryId()));
        sync.importEvents(events, e -> replayed.add(e.memoryId())); // duplicate

        // Second import should skip (hwm already at 1)
        assertThat(replayed).hasSize(1);
    }
}
