package jeff.skyblockflipper.core.sync;

/**
 * A sync that stopped early.
 *
 * <p>Checked, because every caller has something better to do than fail: the local tape is still
 * the tape, and a server that is down or a token that is wrong should cost a log line and the
 * hours the collector holds, not the poll loop.
 */
public class SyncException extends Exception {
	private static final long serialVersionUID = 1L;

	public SyncException(String message) {
		super(message);
	}

	public SyncException(String message, Throwable cause) {
		super(message, cause);
	}
}
