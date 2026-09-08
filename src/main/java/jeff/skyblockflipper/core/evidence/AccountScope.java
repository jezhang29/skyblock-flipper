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

import java.util.Objects;
import java.util.Optional;

/** Account, profile, and optional owner scope attached to evidence. */
public record AccountScope(String accountId, Optional<String> profileId,
		Optional<String> ownerId) {
	public static final int SCHEMA_VERSION = 1;

	public AccountScope {
		accountId = requireText(accountId, "accountId");
		profileId = checked(profileId, "profileId");
		ownerId = checked(ownerId, "ownerId");
	}

	public static AccountScope unresolvedProfile(String accountId) {
		return new AccountScope(accountId, Optional.empty(), Optional.empty());
	}

	public boolean profileResolved() {
		return profileId.isPresent();
	}

	private static Optional<String> checked(Optional<String> value, String name) {
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
