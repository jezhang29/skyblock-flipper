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
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class EvidenceRefTest {
	@Test
	void preservesIndependentClassificationScopeAndTimes() {
		AccountScope scope = new AccountScope("account", Optional.of("profile"),
				Optional.of("owner"));
		EvidenceRef reference = new EvidenceRef(EvidenceRef.CURRENT_SCHEMA_VERSION,
				new ObservationId("obs-1"), EvidenceSource.MARKET_API, Optional.of("block:abc"),
				Optional.of("abc"), Optional.of(Instant.EPOCH), Instant.EPOCH.plusSeconds(2),
				Optional.of(42L), scope, "market-v1", ClaimClass.B_EXTERNALLY_SUPPORTED_MECHANIC,
				Optional.of(NumericClassification.MECHANIC), Optional.of(Instant.EPOCH),
				Optional.of(Instant.EPOCH.plusSeconds(10)), CoverageState.COMPLETE);

		assertEquals(ClaimClass.B_EXTERNALLY_SUPPORTED_MECHANIC, reference.claimClass());
		assertEquals(Optional.of(NumericClassification.MECHANIC),
				reference.numericClassification());
		assertEquals(scope, reference.scope());
	}

	@Test
	void unresolvedProfileIsExplicitAndInvalidApplicabilityFails() {
		assertFalse(AccountScope.unresolvedProfile("account").profileResolved());
		assertThrows(IllegalArgumentException.class, () -> new EvidenceRef(
				EvidenceRef.CURRENT_SCHEMA_VERSION, new ObservationId("obs-1"), EvidenceSource.CHAT,
				Optional.empty(), Optional.empty(), Optional.empty(), Instant.EPOCH, Optional.empty(),
				AccountScope.unresolvedProfile("account"), "parser-v1",
				ClaimClass.C_EMPIRICAL_OBSERVATION, Optional.empty(),
				Optional.of(Instant.EPOCH.plusSeconds(2)), Optional.of(Instant.EPOCH),
				CoverageState.COMPLETE));
	}

	@Test
	void invalidSchemaAndBlankIdentityFailExplicitly() {
		assertThrows(IllegalArgumentException.class, () -> new ObservationId(" "));
		assertThrows(IllegalArgumentException.class, () -> new EvidenceRef(2,
				new ObservationId("obs"), EvidenceSource.CHAT, Optional.empty(), Optional.empty(),
				Optional.empty(), Instant.EPOCH, Optional.empty(),
				AccountScope.unresolvedProfile("account"), "parser-v1",
				ClaimClass.C_EMPIRICAL_OBSERVATION, Optional.empty(), Optional.empty(),
				Optional.empty(), CoverageState.COMPLETE));
	}
}
