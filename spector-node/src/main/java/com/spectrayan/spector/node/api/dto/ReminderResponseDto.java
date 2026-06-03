package com.spectrayan.spector.node.api.dto;

import com.spectrayan.spector.memory.prospective.Reminder;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * Response DTO for {@code POST /memory/reminder}.
 *
 * @param id             the reminder's unique identifier
 * @param text           the reminder text
 * @param delaySeconds   the requested delay
 * @param triggerTime    ISO-8601 timestamp when the reminder will fire
 * @param synapticTags   bloom filter tag encoding (long)
 */
public record ReminderResponseDto(
        String id,
        String text,
        int delaySeconds,
        String triggerTime,
        long synapticTags
) {

    /**
     * Creates a response DTO from the domain model.
     */
    public static ReminderResponseDto from(Reminder reminder, int delaySeconds) {
        String triggerTime = Instant.now()
                .plus(delaySeconds, ChronoUnit.SECONDS)
                .toString();
        return new ReminderResponseDto(
                reminder.id(),
                reminder.text(),
                delaySeconds,
                triggerTime,
                reminder.synapticTags()
        );
    }
}
