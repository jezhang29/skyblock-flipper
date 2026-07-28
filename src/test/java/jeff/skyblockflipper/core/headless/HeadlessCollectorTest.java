package jeff.skyblockflipper.core.headless;

import jeff.skyblockflipper.core.headless.HeadlessCollector.Options;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HeadlessCollectorTest {
	@Test
	void keepsTheClientsDirectoryLayoutSoTapeCopiesStraightAcross() {
		Options options = Options.parse(new String[] {"--data-dir", "/srv/flipper"});

		// The client writes these as siblings under <.minecraft>/config/skyblock-flipper. A collector
		// that nested them differently would produce tape nobody could drop into an install.
		assertEquals(Path.of("/srv/flipper/tape"), options.tapeDir());
		assertEquals(Path.of("/srv/flipper/bazaar-tape"), options.bazaarTapeDir());
		assertEquals(Path.of("/srv/flipper/config.json"), options.configFile());
	}

	@Test
	void readsTheConfigFromWhereverItWasPointed() {
		Options options = Options.parse(
				new String[] {"--data-dir", "/srv/flipper", "--config", "/etc/flipper.json"});

		assertEquals(Path.of("/etc/flipper.json"), options.configFile());
		// An explicit config must not drag the tape along with it.
		assertEquals(Path.of("/srv/flipper/tape"), options.tapeDir());
	}

	@Test
	void acceptsTheFlagsInEitherOrder() {
		Options first = Options.parse(
				new String[] {"--config", "/etc/flipper.json", "--data-dir", "/srv/flipper"});
		Options second = Options.parse(
				new String[] {"--data-dir", "/srv/flipper", "--config", "/etc/flipper.json"});

		assertEquals(first, second);
	}

	@Test
	void defaultsToADirectoryBesideTheJar() {
		Options options = Options.parse(new String[] {});

		assertFalse(options.help());
		assertTrue(options.dataDir().isAbsolute(), "a relative data dir depends on the working "
				+ "directory, which under systemd is not where anyone thinks it is");
		assertEquals(options.dataDir().resolve("config.json"), options.configFile());
	}

	@Test
	void refusesRatherThanGuessingAtBadInput() {
		// Silently ignoring these is how a service ends up collecting into the wrong directory, or
		// running a sweep the operator believed they had turned off.
		assertThrows(IllegalArgumentException.class, () -> Options.parse(new String[] {"--data-dir"}));
		assertThrows(IllegalArgumentException.class, () -> Options.parse(new String[] {"--tape-dir", "x"}));
		assertThrows(IllegalArgumentException.class, () -> Options.parse(new String[] {"/srv/flipper"}));
	}

	@Test
	void answersHelpWithoutNeedingTheRestToMakeSense() {
		assertTrue(Options.parse(new String[] {"--help"}).help());
		assertTrue(Options.parse(new String[] {"--data-dir", "/srv/flipper", "-h"}).help());
	}
}
