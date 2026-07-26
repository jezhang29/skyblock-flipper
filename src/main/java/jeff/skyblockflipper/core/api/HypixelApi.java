package jeff.skyblockflipper.core.api;

import com.google.gson.Gson;
import com.google.gson.JsonParseException;

import jeff.skyblockflipper.core.model.BazaarSnapshot;
import jeff.skyblockflipper.core.model.EndedAuction;
import jeff.skyblockflipper.core.model.ItemCatalog;
import jeff.skyblockflipper.core.model.MayorInfo;
import jeff.skyblockflipper.core.model.dto.BazaarDto;
import jeff.skyblockflipper.core.model.dto.EndedAuctionsDto;
import jeff.skyblockflipper.core.model.dto.ItemsDto;
import jeff.skyblockflipper.core.model.dto.MayorDto;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.OptionalLong;
import java.util.zip.GZIPInputStream;

/**
 * Thin, blocking client for the public Hypixel Skyblock endpoints.
 *
 * <p>Every endpoint used here is public and unauthenticated, so no API key is sent. Requests ask
 * for gzip explicitly: the auctions payload is a couple of megabytes per page uncompressed and
 * the JDK's HttpClient does not negotiate compression on its own.
 *
 * <p>Rate limiting is respected globally rather than per-endpoint - Hypixel's budget is per key
 * (or per IP when unauthenticated), so a 429 on one endpoint means backing off on all of them.
 * Callers get an {@link ApiException} while the backoff is in effect rather than a silent stall.
 */
public final class HypixelApi {
	private static final String BASE = "https://api.hypixel.net/v2/";
	private static final Duration TIMEOUT = Duration.ofSeconds(30);
	/** Fallback backoff when a 429 arrives without a usable reset header. */
	private static final Duration DEFAULT_BACKOFF = Duration.ofSeconds(60);

	private final HttpClient http;
	private final Gson gson = new Gson();

	private volatile Instant blockedUntil = Instant.EPOCH;

	public HypixelApi() {
		this.http = HttpClient.newBuilder()
				.connectTimeout(Duration.ofSeconds(10))
				.followRedirects(HttpClient.Redirect.NORMAL)
				.build();
	}

	public BazaarSnapshot fetchBazaar() throws ApiException {
		return get("skyblock/bazaar", BazaarDto.class).toSnapshot();
	}

	/**
	 * Recently completed sales. The endpoint only exposes roughly the last 60 seconds, and nothing
	 * recovers a window that was missed, so this poll should never be skipped while the game runs.
	 */
	public List<EndedAuction> fetchEndedAuctions() throws ApiException {
		EndedAuctionsDto dto = get("skyblock/auctions_ended", EndedAuctionsDto.class);
		return dto.auctions != null ? dto.auctions : List.of();
	}

	/**
	 * Static item definitions, including the fixed NPC sell prices that NPC flips are measured
	 * against. Only changes with game updates, so it is fetched rarely.
	 */
	public ItemCatalog fetchItems() throws ApiException {
		return get("resources/skyblock/items", ItemsDto.class).toCatalog();
	}

	/** Current mayor and minister. Drives the Derpy fee multiplier, among other things. */
	public MayorInfo fetchMayor() throws ApiException {
		return get("resources/skyblock/election", MayorDto.class).toMayorInfo();
	}

	/** True if we are currently backing off after a 429. */
	public boolean isRateLimited() {
		return Instant.now().isBefore(blockedUntil);
	}

	public Instant rateLimitedUntil() {
		return blockedUntil;
	}

	private <T> T get(String path, Class<T> type) throws ApiException {
		Instant blocked = blockedUntil;

		if (Instant.now().isBefore(blocked)) {
			throw new ApiException("rate limited until " + blocked, true);
		}

		HttpRequest request = HttpRequest.newBuilder(URI.create(BASE + path))
				.header("Accept", "application/json")
				.header("Accept-Encoding", "gzip")
				.header("User-Agent", "skyblock-flipper")
				.timeout(TIMEOUT)
				.GET()
				.build();

		HttpResponse<InputStream> response;

		try {
			response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
		} catch (IOException e) {
			throw new ApiException("request to " + path + " failed", e, false);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new ApiException("interrupted fetching " + path, e, false);
		}

		try (InputStream body = decode(response)) {
			if (response.statusCode() == 429) {
				blockedUntil = Instant.now().plus(backoffFrom(response));
				throw new ApiException("rate limited on " + path + ", backing off until " + blockedUntil, true);
			}

			if (response.statusCode() != 200) {
				throw new ApiException("HTTP " + response.statusCode() + " from " + path, false);
			}

			T parsed = gson.fromJson(new InputStreamReader(body, StandardCharsets.UTF_8), type);

			if (parsed == null) {
				throw new ApiException("empty response body from " + path, false);
			}

			return parsed;
		} catch (JsonParseException e) {
			throw new ApiException("malformed JSON from " + path, e, false);
		} catch (IOException e) {
			throw new ApiException("failed reading response from " + path, e, false);
		}
	}

	private static InputStream decode(HttpResponse<InputStream> response) throws IOException {
		boolean gzipped = response.headers()
				.firstValue("Content-Encoding")
				.filter(value -> value.equalsIgnoreCase("gzip"))
				.isPresent();

		try {
			return gzipped ? new GZIPInputStream(response.body()) : response.body();
		} catch (IOException e) {
			response.body().close();
			throw e;
		} catch (UncheckedIOException e) {
			response.body().close();
			throw e.getCause();
		}
	}

	/**
	 * Hypixel returns {@code RateLimit-Reset} in seconds; {@code Retry-After} is honoured as a
	 * fallback for edge proxies that emit it instead.
	 */
	private static Duration backoffFrom(HttpResponse<?> response) {
		OptionalLong reset = response.headers().firstValueAsLong("RateLimit-Reset");

		if (reset.isEmpty()) {
			reset = response.headers().firstValueAsLong("Retry-After");
		}

		if (reset.isPresent() && reset.getAsLong() > 0L) {
			return Duration.ofSeconds(reset.getAsLong());
		}

		return DEFAULT_BACKOFF;
	}
}
