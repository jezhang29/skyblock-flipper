package jeff.skyblockflipper.core.nbt;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The reader parses bytes that came off the internet, so these are mostly about what it refuses to
 * do with a blob that is corrupt, truncated or deliberately hostile.
 */
class NbtReaderTest {
	/** Writes a root compound holding one named value of the given tag type. */
	private static byte[] rootWith(int tagType, byte[] payload, String name) throws IOException {
		ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		DataOutputStream out = new DataOutputStream(bytes);

		out.writeByte(10);
		out.writeUTF("");
		out.writeByte(tagType);
		out.writeUTF(name);
		out.write(payload);
		out.writeByte(0);

		return bytes.toByteArray();
	}

	private static NbtCompound parse(byte[] nbt) throws IOException {
		return NbtReader.read(new ByteArrayInputStream(nbt));
	}

	@Test
	void readsEveryTagTypeItClaimsTo() throws Exception {
		ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		DataOutputStream out = new DataOutputStream(bytes);

		out.writeByte(10);
		out.writeUTF("root");
		out.writeByte(1);
		out.writeUTF("aByte");
		out.writeByte(7);
		out.writeByte(4);
		out.writeUTF("aLong");
		out.writeLong(123456789L);
		out.writeByte(6);
		out.writeUTF("aDouble");
		out.writeDouble(2.5d);
		out.writeByte(8);
		out.writeUTF("aString");
		out.writeUTF("hello");
		out.writeByte(9);
		out.writeUTF("aList");
		out.writeByte(8);
		out.writeInt(2);
		out.writeUTF("x");
		out.writeUTF("y");
		out.writeByte(0);

		NbtCompound root = parse(bytes.toByteArray());

		// Numerics widen, because Hypixel is inconsistent about byte versus int for flags.
		assertEquals(7, root.intOr("aByte", -1));
		assertEquals(123456789.0d, root.number("aLong").orElseThrow());
		assertEquals(2.5d, root.number("aDouble").orElseThrow());
		assertEquals("hello", root.string("aString").orElseThrow());
		assertEquals(List.of("x", "y"), root.strings("aList"));
	}

	@Test
	void readsAnEmptyListWrittenWithNoElementType() throws Exception {
		// An empty list is written with element type TAG_END, which cannot be read as a value.
		byte[] payload = {0, 0, 0, 0, 0};
		assertTrue(parse(rootWith(9, payload, "empty")).list("empty").isEmpty());
	}

	@Test
	void refusesAnImplausibleLength() {
		// A corrupt length is the one field that turns a bad blob into a heap-sized allocation.
		byte[] payload = {0x7F, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF};

		assertThrows(IOException.class, () -> parse(rootWith(7, payload, "huge")));
	}

	@Test
	void refusesANegativeLength() {
		byte[] payload = {(byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF};

		assertThrows(IOException.class, () -> parse(rootWith(11, payload, "negative")));
	}

	@Test
	void refusesUnboundedNesting() throws Exception {
		ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		DataOutputStream out = new DataOutputStream(bytes);
		out.writeByte(10);
		out.writeUTF("");

		for (int i = 0; i < 200; i++) {
			out.writeByte(10);
			out.writeUTF("deeper");
		}

		assertThrows(IOException.class, () -> parse(bytes.toByteArray()));
	}

	@Test
	void refusesAnUnknownTagType() {
		assertThrows(IOException.class, () -> parse(rootWith(99, new byte[0], "what")));
	}

	@Test
	void refusesSomethingThatIsNotACompoundAtAll() {
		assertThrows(IOException.class, () -> parse(new byte[]{8, 0, 0}));
	}

	@Test
	void refusesATruncatedBlob() throws Exception {
		byte[] full = rootWith(8, new byte[]{0, 1, 'x'}, "text");

		assertThrows(IOException.class,
				() -> parse(java.util.Arrays.copyOf(full, full.length - 2)));
	}

	@Test
	void accessorsReturnEmptyRatherThanThrowingOnTheWrongType() throws Exception {
		NbtCompound root = parse(rootWith(8, new byte[]{0, 1, 'x'}, "text"));

		// The blob is half legacy tag and half components, so every accessor has to tolerate a
		// key being absent or being something other than what was expected.
		assertTrue(root.number("text").isEmpty());
		assertTrue(root.compound("text").isEmpty());
		assertTrue(root.string("missing").isEmpty());
		assertTrue(root.child("missing").isEmpty());
		assertTrue(root.list("text").isEmpty());
		assertEquals(3, root.intOr("text", 3));
	}
}
