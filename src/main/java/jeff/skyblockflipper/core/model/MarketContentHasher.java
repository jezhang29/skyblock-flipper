/*
 * Skyblock Flipper - a Hypixel Skyblock flipping advisor mod.
 * Copyright (C) 2026 SoupChugger
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package jeff.skyblockflipper.core.model;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;

/** Deterministic binary encoding and hash of the complete returned Bazaar content. */
public final class MarketContentHasher {
	private MarketContentHasher() {
	}

	public static MarketContentId hash(Map<String, BazaarProduct> products) {
		try {
			ByteArrayOutputStream bytes = new ByteArrayOutputStream();
			try (DataOutputStream out = new DataOutputStream(bytes)) {
				out.writeInt(1);
				out.writeInt(products.size());
				for (Map.Entry<String, BazaarProduct> entry : products.entrySet().stream()
						.sorted(Map.Entry.comparingByKey()).toList()) {
					writeString(out, entry.getKey());
					writeProduct(out, entry.getValue());
				}
			}
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			return new MarketContentId(HexFormat.of().formatHex(digest.digest(bytes.toByteArray())));
		} catch (IOException e) {
			throw new IllegalStateException("could not encode market content", e);
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException("SHA-256 is unavailable", e);
		}
	}

	private static void writeProduct(DataOutputStream out, BazaarProduct product) throws IOException {
		writeString(out, product.productId());
		writeCoverage(out, product.sellOfferCoverage());
		writeLevels(out, product.sellOffers());
		writeCoverage(out, product.buyOrderCoverage());
		writeLevels(out, product.buyOrders());
		out.writeLong(product.movingWeek().instantBought());
		out.writeLong(product.movingWeek().instantSold());
		out.writeInt(product.activityProxy().state().ordinal());
		out.writeLong(product.activityProxy().buyMovingWeek());
		out.writeLong(product.activityProxy().sellMovingWeek());

		BazaarQuickStatus quick = product.quickStatus();
		out.writeInt(quick.state().ordinal());
		writeString(out, quick.productId());
		out.writeLong(Double.doubleToLongBits(quick.sellPrice()));
		out.writeLong(quick.sellVolume());
		out.writeLong(quick.sellMovingWeek());
		out.writeInt(quick.sellOrders());
		out.writeLong(Double.doubleToLongBits(quick.buyPrice()));
		out.writeLong(quick.buyVolume());
		out.writeLong(quick.buyMovingWeek());
		out.writeInt(quick.buyOrders());
	}

	private static void writeCoverage(DataOutputStream out, DepthCoverage coverage) throws IOException {
		out.writeInt(coverage.state().ordinal());
		out.writeInt(coverage.returnedLevels());
		out.writeLong(coverage.returnedOrders());
		out.writeInt(coverage.documentedMaximumOrders());
	}

	private static void writeLevels(DataOutputStream out, java.util.List<OrderLevel> levels)
			throws IOException {
		out.writeInt(levels.size());
		for (OrderLevel level : levels) {
			out.writeLong(Double.doubleToLongBits(level.pricePerUnit()));
			out.writeLong(level.amount());
			out.writeInt(level.orders());
		}
	}

	private static void writeString(DataOutputStream out, String value) throws IOException {
		out.writeBoolean(value != null);
		if (value != null) {
			out.writeUTF(value);
		}
	}
}
