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

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/** Supported execution time evidence, distinct from the time an observation was received. */
public sealed interface TimeEvidence permits TimeEvidence.Exact, TimeEvidence.Interval,
		TimeEvidence.LeftTruncated, TimeEvidence.Open, TimeEvidence.Unknown {
	int SCHEMA_VERSION = 1;

	List<EvidenceRef> evidence();

	record Exact(Instant value, List<EvidenceRef> evidence) implements TimeEvidence {
		public Exact {
			Objects.requireNonNull(value, "value");
			evidence = requiredEvidence(evidence);
		}
	}

	/** Execution occurred in {@code (lastKnownBefore, firstKnownAfter]}. */
	record Interval(Instant lastKnownBefore, Instant firstKnownAfter,
			List<EvidenceRef> evidence) implements TimeEvidence {
		public Interval {
			Objects.requireNonNull(lastKnownBefore, "lastKnownBefore");
			Objects.requireNonNull(firstKnownAfter, "firstKnownAfter");
			if (!lastKnownBefore.isBefore(firstKnownAfter)) {
				throw new IllegalArgumentException("last-known-before must precede first-known-after");
			}
			evidence = requiredEvidence(evidence);
		}

		public EndpointSemantics endpointSemantics() {
			return EndpointSemantics.OPEN_CLOSED;
		}
	}

	/** The event may predate the first observation; its earlier age is not known to be zero. */
	record LeftTruncated(Instant firstObservedAt, List<EvidenceRef> evidence)
			implements TimeEvidence {
		public LeftTruncated {
			Objects.requireNonNull(firstObservedAt, "firstObservedAt");
			evidence = requiredEvidence(evidence);
		}
	}

	/** The event is known not to have happened by this instant and has no observed upper endpoint. */
	record Open(Instant lastKnownBefore, List<EvidenceRef> evidence) implements TimeEvidence {
		public Open {
			Objects.requireNonNull(lastKnownBefore, "lastKnownBefore");
			evidence = requiredEvidence(evidence);
		}
	}

	record Unknown(String reason, List<String> neededEvidence, List<EvidenceRef> evidence)
			implements TimeEvidence {
		public Unknown {
			if (reason == null || reason.isBlank()) {
				throw new IllegalArgumentException("reason must not be blank");
			}
			Objects.requireNonNull(neededEvidence, "neededEvidence");
			neededEvidence = neededEvidence.stream().map(value -> {
				if (value == null || value.isBlank()) {
					throw new IllegalArgumentException("needed evidence must not be blank");
				}
				return value;
			}).toList();
			if (neededEvidence.isEmpty()) {
				throw new IllegalArgumentException("neededEvidence must not be empty");
			}
			evidence = immutableEvidence(evidence);
		}
	}

	private static List<EvidenceRef> requiredEvidence(List<EvidenceRef> evidence) {
		List<EvidenceRef> copy = immutableEvidence(evidence);
		if (copy.isEmpty()) {
			throw new IllegalArgumentException("time evidence needs provenance");
		}
		return copy;
	}

	private static List<EvidenceRef> immutableEvidence(List<EvidenceRef> evidence) {
		Objects.requireNonNull(evidence, "evidence");
		return List.copyOf(evidence);
	}
}
