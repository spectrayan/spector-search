package com.spectrayan.spector.memory.amygdala;

import com.spectrayan.spector.memory.MemoryType;
import com.spectrayan.spector.memory.synapse.CognitiveRecordLayout;
import com.spectrayan.spector.memory.synapse.CognitiveRecordLayout.CognitiveHeader;
import com.spectrayan.spector.memory.synapse.SynapticHeaderConstants;
import org.junit.jupiter.api.Test;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;

import static org.assertj.core.api.Assertions.assertThat;

class ValenceTrackerTest {

    private final CognitiveRecordLayout layout = new CognitiveRecordLayout(32);

    @Test
    void reinforceBlends() {
        try (var arena = Arena.ofConfined()) {
            MemorySegment seg = arena.allocate(layout.stride());
            var header = CognitiveHeader.create(System.currentTimeMillis(), 0L, 1f, 1f, (short) 0, MemoryType.EPISODIC);
            layout.writeHeader(seg, 0, header);

            var tracker = new ValenceTracker(0.5f);
            // Reinforce with strong positive
            tracker.reinforce(seg, 0, layout, Valence.STRONGLY_POSITIVE);
            byte v1 = layout.readValence(seg, 0);
            assertThat(v1).isGreaterThan((byte) 0);

            // Reinforce with negative — should blend down
            tracker.reinforce(seg, 0, layout, Valence.STRONGLY_NEGATIVE);
            byte v2 = layout.readValence(seg, 0);
            assertThat(v2).isLessThan(v1);
        }
    }

    @Test
    void valenceClampsBounds() {
        assertThat(Valence.clamp(200)).isEqualTo(Byte.MAX_VALUE);
        assertThat(Valence.clamp(-200)).isEqualTo(Byte.MIN_VALUE);
        assertThat(Valence.clamp(50)).isEqualTo((byte) 50);
    }

    @Test
    void valencePolarity() {
        assertThat(Valence.isPositive(Valence.STRONGLY_POSITIVE)).isTrue();
        assertThat(Valence.isNegative(Valence.STRONGLY_NEGATIVE)).isTrue();
        assertThat(Valence.isPositive(Valence.NEUTRAL)).isFalse();
        assertThat(Valence.isNegative(Valence.NEUTRAL)).isFalse();
    }

    @Test
    void blendConverges() {
        // Repeated positive reinforcement should converge toward max
        byte v = Valence.NEUTRAL;
        for (int i = 0; i < 20; i++) {
            v = Valence.blend(v, Valence.STRONGLY_POSITIVE, 0.3f);
        }
        assertThat(v).isGreaterThan((byte) 80);
    }
}
