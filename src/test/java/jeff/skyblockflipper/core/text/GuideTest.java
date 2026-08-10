package jeff.skyblockflipper.core.text;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The guide is the only place the mod explains itself, so it fails here rather than in front of a
 * player: a section nobody can ask for, or a command that stopped existing, is not a typo but a
 * feature nobody can find.
 */
class GuideTest {
	/**
	 * Every {@code /flip} subcommand there is.
	 *
	 * <p>Held here rather than read from the command tree, which lives in {@code client} and needs
	 * Minecraft. It is a tripwire, not a source of truth: when it disagrees with the guide, one of
	 * the two has been changed without the other.
	 */
	private static final Set<String> SUBCOMMANDS = Set.of(
			"bazaar", "npc", "snipe", "guide", "status", "config", "take", "close", "abandon",
			"ledger", "hud", "capture", "track", "sync", "gui", "reload");

	/** {@code /flip} followed by a word, which is how the guide writes a command. */
	private static final Pattern MENTION = Pattern.compile("/flip (<?[a-z]+)");

	@Test
	void everySectionCanBeAskedForByName() {
		Set<String> seen = new HashSet<>();

		for (Guide.Section section : Guide.sections()) {
			assertTrue(section.keyword().matches("[a-z]+"),
					section.keyword() + " has to be one lowercase word - /flip guide takes a word");
			assertTrue(seen.add(section.keyword()), "two sections answer to " + section.keyword());
			assertFalse(section.terms().isEmpty(), section.keyword() + " explains nothing");
		}
	}

	@Test
	void everyTermSaysSomething() {
		for (Guide.Section section : Guide.sections()) {
			for (Guide.Term term : section.terms()) {
				assertFalse(term.name().isBlank(), "unnamed term in " + section.keyword());
				assertTrue(term.meaning().length() > 20,
						term.name() + " in " + section.keyword() + " is not an explanation");
			}
		}
	}

	@Test
	void theWalkthroughComesFirstAndItsStepsAreInOrder() {
		Guide.Section first = Guide.sections().getFirst();

		assertEquals("start", first.keyword());

		int expected = 1;

		for (Guide.Term term : first.terms()) {
			// The tail of the section is unnumbered on purpose, and nothing may follow a step.
			if (!Character.isDigit(term.name().charAt(0))) {
				break;
			}

			assertTrue(term.name().startsWith(expected + ". "),
					"step out of order: " + term.name() + " where " + expected + " was expected");
			expected++;
		}

		assertTrue(expected > 5, "a walkthrough of " + (expected - 1) + " steps is not a walkthrough");
	}

	@Test
	void everyCommandTheGuideMentionsExists() {
		List<String> unknown = new ArrayList<>();

		for (Guide.Section section : Guide.sections()) {
			for (Guide.Term term : section.terms()) {
				collectUnknown(term.name(), unknown);
				collectUnknown(term.meaning(), unknown);
			}
		}

		assertEquals(List.of(), unknown, "the guide describes commands that do not exist");
	}

	private static void collectUnknown(String text, List<String> unknown) {
		Matcher matcher = MENTION.matcher(text);

		while (matcher.find()) {
			String word = matcher.group(1);

			// A placeholder, as in /flip guide <section>: the argument, not another subcommand.
			if (!word.startsWith("<") && !SUBCOMMANDS.contains(word)) {
				unknown.add(word);
			}
		}
	}
}
