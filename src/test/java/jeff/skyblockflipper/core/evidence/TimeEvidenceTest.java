/*
 * Skyblock Flipper - a Hypixel Skyblock flipping advisor mod.
 * Copyright (C) 2026 SoupChugger
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package jeff.skyblockflipper.core.evidence;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TimeEvidenceTest {
	private static final EvidenceRef EVIDENCE = EvidenceRef.observed(new ObservationId("obs-time"),
			EvidenceSource.CHAT, Instant.EPOCH, AccountScope.unresolvedProfile("account"),
			"parser-v1", ClaimClass.C_EMPIRICAL_OBSERVATION, Optional.empty());

	@Test
	void intervalIsOpenThenClosedAndRejectsInvalidOrdering() {
		TimeEvidence.Interval interval = new TimeEvidence.Interval(Instant.EPOCH,
				Instant.EPOCH.plusSeconds(5), List.of(EVIDENCE));

		assertEquals(EndpointSemantics.OPEN_CLOSED, interval.endpointSemantics());
		assertThrows(IllegalArgumentException.class, () -> new TimeEvidence.Interval(Instant.EPOCH,
				Instant.EPOCH, List.of(EVIDENCE)));
	}

	@Test
	void leftTruncationAndOpenEndRemainDifferentVariants() {
		TimeEvidence left = new TimeEvidence.LeftTruncated(Instant.EPOCH, List.of(EVIDENCE));
		TimeEvidence open = new TimeEvidence.Open(Instant.EPOCH, List.of(EVIDENCE));

		assertEquals("left-truncated", kind(left));
		assertEquals("open", kind(open));
	}

	private static String kind(TimeEvidence evidence) {
		return switch (evidence) {
			case TimeEvidence.Exact ignored -> "exact";
			case TimeEvidence.Interval ignored -> "interval";
			case TimeEvidence.LeftTruncated ignored -> "left-truncated";
			case TimeEvidence.Open ignored -> "open";
			case TimeEvidence.Unknown ignored -> "unknown";
		};
	}
}
