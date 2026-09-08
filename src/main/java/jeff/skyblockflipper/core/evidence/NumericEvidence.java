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

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Numerical information with its actual observation strength.
 *
 * <p>There is deliberately no common numeric accessor: callers must exhaustively handle exact,
 * bounded, approximate, unknown, and conflicting evidence instead of obtaining a sentinel or
 * midpoint.
 */
public sealed interface NumericEvidence<T extends Comparable<? super T>>
		permits NumericEvidence.Exact, NumericEvidence.Bounded, NumericEvidence.Approximate,
		NumericEvidence.Unknown, NumericEvidence.Conflict {
	int SCHEMA_VERSION = 1;

	List<EvidenceRef> evidence();

	record Exact<T extends Comparable<? super T>>(T value, List<EvidenceRef> evidence)
			implements NumericEvidence<T> {
		public static final String SERIALIZED_TYPE = "exact";

		public Exact {
			Objects.requireNonNull(value, "value");
			evidence = requiredEvidence(evidence);
		}
	}

	record Bounded<T extends Comparable<? super T>>(T lower, T upper,
			EndpointSemantics endpointSemantics, List<EvidenceRef> evidence,
			List<String> constraints) implements NumericEvidence<T> {
		public static final String SERIALIZED_TYPE = "bounded";

		public Bounded {
			Objects.requireNonNull(lower, "lower");
			Objects.requireNonNull(upper, "upper");
			Objects.requireNonNull(endpointSemantics, "endpointSemantics");
			evidence = requiredEvidence(evidence);
			constraints = textList(constraints, "constraints", false);
			int order = lower.compareTo(upper);
			if (order > 0 || (order == 0
					&& (!endpointSemantics.lowerInclusive() || !endpointSemantics.upperInclusive()))) {
				throw new IllegalArgumentException("bounds describe an empty interval");
			}
		}

		public boolean contains(T candidate) {
			Objects.requireNonNull(candidate, "candidate");
			int fromLower = candidate.compareTo(lower);
			int fromUpper = candidate.compareTo(upper);
			return (fromLower > 0 || (fromLower == 0 && endpointSemantics.lowerInclusive()))
					&& (fromUpper < 0 || (fromUpper == 0 && endpointSemantics.upperInclusive()));
		}
	}

	record Approximate<T extends Comparable<? super T>>(String rawText, String displayPrecision,
			List<String> knownConstraints, List<EvidenceRef> evidence) implements NumericEvidence<T> {
		public static final String SERIALIZED_TYPE = "approximate";

		public Approximate {
			rawText = requireText(rawText, "rawText");
			displayPrecision = requireText(displayPrecision, "displayPrecision");
			knownConstraints = textList(knownConstraints, "knownConstraints", false);
			evidence = requiredEvidence(evidence);
		}
	}

	record Unknown<T extends Comparable<? super T>>(String reason, List<String> neededEvidence,
			List<EvidenceRef> evidence) implements NumericEvidence<T> {
		public static final String SERIALIZED_TYPE = "unknown";

		public Unknown {
			reason = requireText(reason, "reason");
			neededEvidence = textList(neededEvidence, "neededEvidence", true);
			evidence = immutableEvidence(evidence);
		}
	}

	record Conflict<T extends Comparable<? super T>>(List<EvidenceRef> evidence,
			List<String> feasibleHypotheses) implements NumericEvidence<T> {
		public static final String SERIALIZED_TYPE = "conflict";

		public Conflict {
			evidence = immutableEvidence(evidence);
			if (evidence.size() < 2) {
				throw new IllegalArgumentException("a conflict needs at least two evidence references");
			}
			feasibleHypotheses = textList(feasibleHypotheses, "feasibleHypotheses", true);
		}
	}

	/** Checked quantity addition; overflow is rejected rather than wrapped into a valid-looking value. */
	static Exact<Long> addExact(Exact<Long> left, Exact<Long> right) {
		Objects.requireNonNull(left, "left");
		Objects.requireNonNull(right, "right");
		List<EvidenceRef> combined = new ArrayList<>(left.evidence());
		for (EvidenceRef ref : right.evidence()) {
			if (!combined.contains(ref)) {
				combined.add(ref);
			}
		}
		return new Exact<>(Math.addExact(left.value(), right.value()), combined);
	}

	private static List<EvidenceRef> requiredEvidence(List<EvidenceRef> evidence) {
		List<EvidenceRef> copy = immutableEvidence(evidence);
		if (copy.isEmpty()) {
			throw new IllegalArgumentException("observed numeric evidence needs provenance");
		}
		return copy;
	}

	private static List<EvidenceRef> immutableEvidence(List<EvidenceRef> evidence) {
		Objects.requireNonNull(evidence, "evidence");
		return List.copyOf(evidence);
	}

	private static List<String> textList(List<String> values, String name, boolean requireNonEmpty) {
		Objects.requireNonNull(values, name);
		List<String> copy = values.stream().map(v -> requireText(v, name + " entry")).toList();
		if (requireNonEmpty && copy.isEmpty()) {
			throw new IllegalArgumentException(name + " must not be empty");
		}
		return copy;
	}

	private static String requireText(String value, String name) {
		Objects.requireNonNull(value, name);
		if (value.isBlank()) {
			throw new IllegalArgumentException(name + " must not be blank");
		}
		return value;
	}
}
