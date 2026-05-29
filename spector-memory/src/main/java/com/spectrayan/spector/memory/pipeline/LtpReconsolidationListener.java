package com.spectrayan.spector.memory.pipeline;

import com.spectrayan.spector.memory.CognitiveResult;
import com.spectrayan.spector.memory.cortex.TierRouter;
import com.spectrayan.spector.memory.index.MemoryIndex;
import com.spectrayan.spector.memory.index.MemoryIndex.MemoryLocation;
import com.spectrayan.spector.memory.sync.MemoryWal;
import com.spectrayan.spector.memory.sync.WalEvent;
import com.spectrayan.spector.memory.synapse.CognitiveRecordLayout;

import java.lang.foreign.MemorySegment;
import java.util.List;

/**
 * LTP Reconsolidation listener — increments recall_count on returned memories.
 *
 * <h3>Biological Analog: Long-Term Potentiation (LTP)</h3>
 * <p>Each time a memory is successfully recalled, its synaptic strength increases.
 * In Spector's model, this manifests as incrementing {@code recall_count},
 * which in turn reduces the decay rate via
 * {@link com.spectrayan.spector.memory.synapse.DecayStrategy#adjustForReconsolidation}.</p>
 *
 * <h3>Design Pattern: Observer</h3>
 * <p>Previously hardcoded in SpectorMemory.recall() Step 7, now a standalone
 * listener registered with {@link RecallPipeline#addListener}.</p>
 */
public final class LtpReconsolidationListener implements RecallListener {

    private final MemoryIndex index;
    private final TierRouter tierRouter;
    private final MemoryWal wal;

    public LtpReconsolidationListener(MemoryIndex index, TierRouter tierRouter, MemoryWal wal) {
        this.index = index;
        this.tierRouter = tierRouter;
        this.wal = wal;
    }

    @Override
    public void onRecallComplete(List<CognitiveResult> results) {
        for (CognitiveResult r : results) {
            MemoryLocation loc = index.locate(r.id());
            if (loc != null) {
                // Log recall hit for analytics only — recall_count is now managed
                // exclusively by reinforce() to prevent inflation from passive retrieval.
                // Previously this incremented recall_count for ALL returned results,
                // making too many memories "immortal" via reconsolidation.
                wal.append(WalEvent.EventType.RECALL_HIT,
                        index.findIdByOffset(loc.type(), loc.offset()), null);
            }
        }
    }
}
