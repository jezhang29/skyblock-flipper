package jeff.skyblockflipper.core.api;

/** A failed Hypixel API call. */
public class ApiException extends Exception {
	private final boolean rateLimited;

	public ApiException(String message, boolean rateLimited) {
		super(message);
		this.rateLimited = rateLimited;
	}

	public ApiException(String message, Throwable cause, boolean rateLimited) {
		super(message, cause);
		this.rateLimited = rateLimited;
	}

	/** True when the failure was a rate limit, which callers should treat as "retry later", not "broken". */
	public boolean isRateLimited() {
		return rateLimited;
	}
}
