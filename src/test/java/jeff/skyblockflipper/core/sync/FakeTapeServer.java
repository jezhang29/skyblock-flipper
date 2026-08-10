package jeff.skyblockflipper.core.sync;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * A stand-in for the collector's nginx, serving a directory the way the real one does.
 *
 * <p>Small on purpose, and deliberately not a mock: the two things this sync depends on the server
 * for are a JSON directory index and honest {@code Range} support, and both are things a mock would
 * simply agree to. Serving real bytes off a real socket is what makes a test about resuming at an
 * offset mean anything.
 */
final class FakeTapeServer implements AutoCloseable {
	private final HttpServer server;
	private final Path root;
	private final String token;

	/** Every Range header received, in order, so a test can prove a fetch was incremental. */
	final List<String> ranges = new ArrayList<>();

	/** Set to make the server ignore Range and send whole files, as a plain file server would. */
	boolean ignoreRange;

	FakeTapeServer(Path root, String token) throws IOException {
		this.root = root;
		this.token = token;
		this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		this.server.createContext("/", this::handle);
		this.server.start();
	}

	String baseUrl() {
		return "http://127.0.0.1:" + server.getAddress().getPort();
	}

	@Override
	public void close() {
		server.stop(0);
	}

	private void handle(HttpExchange exchange) throws IOException {
		try (exchange) {
			if (!token.isEmpty()
					&& !token.equals(exchange.getRequestHeaders().getFirst(TapeSync.TOKEN_HEADER))) {
				exchange.sendResponseHeaders(403, -1);
				return;
			}

			String path = exchange.getRequestURI().getPath();

			if (path.endsWith("/")) {
				index(exchange, path);
			} else {
				file(exchange, path);
			}
		}
	}

	private void index(HttpExchange exchange, String path) throws IOException {
		Path directory = root.resolve(path.substring(1, path.length() - 1));

		if (!Files.isDirectory(directory)) {
			exchange.sendResponseHeaders(404, -1);
			return;
		}

		StringBuilder json = new StringBuilder("[");

		try (var files = Files.list(directory)) {
			for (Path file : files.sorted().toList()) {
				if (json.length() > 1) {
					json.append(',');
				}

				json.append("{\"name\":\"").append(file.getFileName())
						.append("\",\"type\":\"file\",\"size\":").append(Files.size(file)).append('}');
			}
		}

		send(exchange, 200, json.append(']').toString().getBytes(StandardCharsets.UTF_8));
	}

	private void file(HttpExchange exchange, String path) throws IOException {
		Path file = root.resolve(path.substring(1));

		if (!Files.isRegularFile(file)) {
			exchange.sendResponseHeaders(404, -1);
			return;
		}

		byte[] all = Files.readAllBytes(file);
		String range = exchange.getRequestHeaders().getFirst("Range");

		if (range != null) {
			ranges.add(range);
		}

		if (range == null || ignoreRange) {
			send(exchange, 200, all);
			return;
		}

		long from = Long.parseLong(range.substring("bytes=".length()).split("-")[0]);

		if (from >= all.length) {
			exchange.sendResponseHeaders(416, -1);
			return;
		}

		byte[] tail = new byte[(int) (all.length - from)];
		System.arraycopy(all, (int) from, tail, 0, tail.length);
		exchange.getResponseHeaders().add("Content-Range",
				"bytes " + from + "-" + (all.length - 1) + "/" + all.length);
		send(exchange, 206, tail);
	}

	private static void send(HttpExchange exchange, int status, byte[] body) throws IOException {
		exchange.sendResponseHeaders(status, body.length);

		try (OutputStream out = exchange.getResponseBody()) {
			out.write(body);
		}
	}
}
