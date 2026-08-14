package jeff.skyblockflipper.client.track;

import jeff.skyblockflipper.core.track.CapturedMenu;
import jeff.skyblockflipper.core.track.CapturedSlot;

import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.ItemLore;

import java.util.ArrayList;
import java.util.List;

/**
 * Turns an open Hypixel menu into plain data.
 *
 * <p>The conversion happens here, in {@code client}, so nothing under {@code core} ever holds an
 * {@link ItemStack}. That is the same rule the rest of the mod runs on, and it is what keeps the
 * capture records loadable by a test with no Minecraft on the classpath.
 */
public final class MenuReader {
	/**
	 * Long enough for the biggest custom-data compound Hypixel sends and short enough that one
	 * pathological stack cannot fill the capture file by itself.
	 */
	private static final int MAX_NBT_CHARS = 8_000;

	private MenuReader() {
	}

	static CapturedMenu read(AbstractContainerScreen<?> screen, long at) {
		return read(screen, at, true);
	}

	/**
	 * The same menu without the raw NBT, for a caller that only wants to know what is where.
	 *
	 * <p>{@link #snbt} stringifies a compound per slot, which is worth doing once when a menu is
	 * written to the capture file and not worth doing for fifty-four slots on the way to deciding
	 * which one to draw a box behind. Everything a slot is matched on - name, lore, item id - is kept.
	 */
	public static CapturedMenu describe(AbstractContainerScreen<?> screen, long at) {
		return read(screen, at, false);
	}

	private static CapturedMenu read(AbstractContainerScreen<?> screen, long at, boolean withNbt) {
		List<CapturedSlot> slots = new ArrayList<>();

		for (Slot slot : screen.getMenu().slots) {
			// The player's own inventory is in every container menu and says nothing about the
			// market. Skipping it also keeps the capture file from recording what is in your bags.
			if (slot.container instanceof Inventory) {
				continue;
			}

			ItemStack stack = slot.getItem();

			if (stack.isEmpty()) {
				continue;
			}

			slots.add(toCaptured(slot.index, stack, withNbt));
		}

		return new CapturedMenu(at, plain(screen.getTitle()), slots);
	}

	private static CapturedSlot toCaptured(int index, ItemStack stack, boolean withNbt) {
		CustomData data = stack.get(DataComponents.CUSTOM_DATA);
		CompoundTag tag = data == null ? new CompoundTag() : data.copyTag();

		return new CapturedSlot(index, plain(stack.getHoverName()), lore(stack), itemId(tag),
				stack.getCount(), withNbt ? snbt(tag) : "");
	}

	/**
	 * Hypixel's own item id, which is the thing chat cannot give you.
	 *
	 * <p>Measured in the first capture session: menu stacks carry the {@code ExtraAttributes} keys
	 * flattened onto the root of {@code minecraft:custom_data}, so the id is {@code id} and not
	 * {@code ExtraAttributes.id} as it is inside the auction API's {@code item_bytes} blobs. All 497
	 * stacks in that session that had custom data at all had a root {@code id}. The nested form is
	 * still tried second, in case a menu somewhere sends the blob shape.
	 */
	private static String itemId(CompoundTag tag) {
		String root = tag.getStringOr("id", "");
		return root.isEmpty() ? tag.getCompoundOrEmpty("ExtraAttributes").getStringOr("id", "") : root;
	}

	private static List<String> lore(ItemStack stack) {
		ItemLore lore = stack.get(DataComponents.LORE);

		if (lore == null) {
			return List.of();
		}

		return lore.lines().stream().map(MenuReader::plain).toList();
	}

	/** Formatting stripped, because a parser will be matching on words rather than on colours. */
	private static String plain(Component component) {
		return component == null ? "" : component.getString();
	}

	private static String snbt(CompoundTag tag) {
		if (tag.isEmpty()) {
			return "";
		}

		String text = tag.toString();
		return text.length() <= MAX_NBT_CHARS ? text : text.substring(0, MAX_NBT_CHARS);
	}
}
