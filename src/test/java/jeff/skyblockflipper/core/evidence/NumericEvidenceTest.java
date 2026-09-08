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

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NumericEvidenceTest {
	private static final EvidenceRef EVIDENCE = EvidenceRef.observed(new ObservationId("obs-1"),
			EvidenceSource.BAZAAR_MENU, Instant.EPOCH, AccountScope.unresolvedProfile("account"),
			"parser-v1", ClaimClass.C_EMPIRICAL_OBSERVATION,
			Optional.of(NumericClassification.MEASURED));

	@Test
	void preservesExactDecimalRepresentationAndEvidence() {
		BigDecimal amount = new BigDecimal("123.4500");
		NumericEvidence.Exact<BigDecimal> exact = new NumericEvidence.Exact<>(amount,
				List.of(EVIDENCE));

		assertEquals(amount, exact.value());
		assertEquals(4, exact.value().scale());
		assertEquals(List.of(EVIDENCE), exact.evidence());
	}

	@Test
	void validatesBoundsAndTheirEndpointBehavior() {
		NumericEvidence.Bounded<Long> bounded = new NumericEvidence.Bounded<>(10L, 20L,
				EndpointSemantics.OPEN_CLOSED, List.of(EVIDENCE), List.of("menu observations"));

		assertFalse(bounded.contains(10L));
		assertTrue(bounded.contains(11L));
		assertTrue(bounded.contains(20L));
		assertThrows(IllegalArgumentException.class, () -> new NumericEvidence.Bounded<>(20L,
				10L, EndpointSemantics.CLOSED_CLOSED, List.of(EVIDENCE), List.of()));
		assertThrows(IllegalArgumentException.class, () -> new NumericEvidence.Bounded<>(10L,
				10L, EndpointSemantics.OPEN_OPEN, List.of(EVIDENCE), List.of()));
	}

	@Test
	void approximateAndUnknownExposeNoNumericMidpoint() {
		NumericEvidence<Long> approximate = new NumericEvidence.Approximate<>("1.1k",
				"abbreviated", List.of("total is exact"), List.of(EVIDENCE));
		NumericEvidence<Long> unknown = new NumericEvidence.Unknown<>("malformed numerator",
				List.of("exact claim or parseable lore"), List.of(EVIDENCE));

		assertEquals("approximate", kind(approximate));
		assertEquals("unknown", kind(unknown));
	}

	@Test
	void preservesConflictingEvidenceAndHypotheses() {
		EvidenceRef second = EvidenceRef.observed(new ObservationId("obs-2"), EvidenceSource.CHAT,
				Instant.EPOCH.plusSeconds(1), AccountScope.unresolvedProfile("account"), "parser-v1",
				ClaimClass.C_EMPIRICAL_OBSERVATION,
				Optional.of(NumericClassification.MEASURED));
		NumericEvidence.Conflict<Long> conflict = new NumericEvidence.Conflict<>(
				List.of(EVIDENCE, second), List.of("row is stale", "claim belongs to another order"));

		assertEquals(List.of(EVIDENCE, second), conflict.evidence());
		assertEquals(2, conflict.feasibleHypotheses().size());
		assertThrows(IllegalArgumentException.class,
				() -> new NumericEvidence.Conflict<>(List.of(EVIDENCE), List.of("only one input")));
	}

	@Test
	void checkedQuantityAdditionRejectsOverflow() {
		NumericEvidence.Exact<Long> maximum = new NumericEvidence.Exact<>(Long.MAX_VALUE,
				List.of(EVIDENCE));
		NumericEvidence.Exact<Long> one = new NumericEvidence.Exact<>(1L, List.of(EVIDENCE));

		assertThrows(ArithmeticException.class, () -> NumericEvidence.addExact(maximum, one));
	}

	private static String kind(NumericEvidence<Long> evidence) {
		return switch (evidence) {
			case NumericEvidence.Exact<Long> ignored -> "exact";
			case NumericEvidence.Bounded<Long> ignored -> "bounded";
			case NumericEvidence.Approximate<Long> ignored -> "approximate";
			case NumericEvidence.Unknown<Long> ignored -> "unknown";
			case NumericEvidence.Conflict<Long> ignored -> "conflict";
		};
	}
}
