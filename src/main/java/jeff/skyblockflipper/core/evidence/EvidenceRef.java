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
import java.util.Objects;
import java.util.Optional;

/** Immutable provenance for one observation. All optional facts are explicit optionals. */
public record EvidenceRef(
		int schemaVersion,
		ObservationId observationId,
		EvidenceSource source,
		Optional<String> rawRecordReference,
		Optional<String> rawRecordHash,
		Optional<Instant> sourceEventTime,
		Instant receiptTime,
		Optional<Long> monotonicNanos,
		AccountScope scope,
		String parserVersion,
		ClaimClass claimClass,
		Optional<NumericClassification> numericClassification,
		Optional<Instant> applicableFrom,
		Optional<Instant> applicableUntil,
		CoverageState coverage) {
	public static final int CURRENT_SCHEMA_VERSION = 1;

	public EvidenceRef {
		if (schemaVersion != CURRENT_SCHEMA_VERSION) {
			throw new IllegalArgumentException("unsupported evidence schema version: " + schemaVersion);
		}
		Objects.requireNonNull(observationId, "observationId");
		Objects.requireNonNull(source, "source");
		rawRecordReference = checkedText(rawRecordReference, "rawRecordReference");
		rawRecordHash = checkedText(rawRecordHash, "rawRecordHash");
		sourceEventTime = Objects.requireNonNull(sourceEventTime, "sourceEventTime");
		Objects.requireNonNull(receiptTime, "receiptTime");
		monotonicNanos = Objects.requireNonNull(monotonicNanos, "monotonicNanos");
		Objects.requireNonNull(scope, "scope");
		parserVersion = requireText(parserVersion, "parserVersion");
		Objects.requireNonNull(claimClass, "claimClass");
		numericClassification = Objects.requireNonNull(numericClassification,
				"numericClassification");
		applicableFrom = Objects.requireNonNull(applicableFrom, "applicableFrom");
		applicableUntil = Objects.requireNonNull(applicableUntil, "applicableUntil");
		Objects.requireNonNull(coverage, "coverage");
		if (applicableFrom.isPresent() && applicableUntil.isPresent()
				&& applicableFrom.orElseThrow().isAfter(applicableUntil.orElseThrow())) {
			throw new IllegalArgumentException("applicability start must not follow its end");
		}
	}

	public static EvidenceRef observed(ObservationId id, EvidenceSource source, Instant receiptTime,
			AccountScope scope, String parserVersion, ClaimClass claimClass,
			Optional<NumericClassification> numericClassification) {
		return new EvidenceRef(CURRENT_SCHEMA_VERSION, id, source, Optional.empty(), Optional.empty(),
				Optional.empty(), receiptTime, Optional.empty(), scope, parserVersion, claimClass,
				numericClassification, Optional.empty(), Optional.empty(), CoverageState.COMPLETE);
	}

	private static Optional<String> checkedText(Optional<String> value, String name) {
		Objects.requireNonNull(value, name);
		return value.map(v -> requireText(v, name));
	}

	private static String requireText(String value, String name) {
		Objects.requireNonNull(value, name);
		if (value.isBlank()) {
			throw new IllegalArgumentException(name + " must not be blank");
		}
		return value;
	}
}
