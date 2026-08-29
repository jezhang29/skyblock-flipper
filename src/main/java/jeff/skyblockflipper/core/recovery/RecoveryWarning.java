package jeff.skyblockflipper.core.recovery;

/** Reasons a recovery leg was rejected, reduced, or needs manual evidence. */
public enum RecoveryWarning {
	UNKNOWN_MAPPING,
	UNKNOWN_REMOVAL_COST,
	UNSUPPORTED_SLOT,
	MALFORMED_METADATA,
	INSUFFICIENT_SAMPLES,
	INSUFFICIENT_DEPTH,
	ILLIQUID,
	PARTIAL_DEPTH_ZERO_CREDIT,
	PREVIEW_REQUIRED,
	STALE_EVIDENCE,
	ARITHMETIC_OVERFLOW
}
