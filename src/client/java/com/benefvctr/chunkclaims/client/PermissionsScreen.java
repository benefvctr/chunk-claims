package com.benefvctr.chunkclaims.client;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import com.mojang.blaze3d.platform.InputConstants;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvents;

import com.benefvctr.chunkclaims.net.PermissionsDataPayload;
import com.benefvctr.chunkclaims.net.SetPermissionPayload;

/**
 * Who may build in the territory (cluster) you are standing in.
 *
 * <p>Drawn in the vanilla advancements window frame at its native 252x140 size,
 * over the stone advancement-tab background. Every player who has joined the
 * server gets a row with a check (allowed) or cross (denied). Clicking a row
 * flips it immediately and the server echoes back its own view, which replaces
 * the list, so the menu can never drift from what is actually stored.
 */
final class PermissionsScreen extends Screen {
	private static final Identifier WINDOW = Identifier.withDefaultNamespace("textures/gui/advancements/window.png");
	private static final Identifier BACKGROUND = Identifier.withDefaultNamespace("textures/gui/advancements/backgrounds/stone.png");
	private static final Identifier ALLOWED = Identifier.withDefaultNamespace("container/beacon/confirm");
	private static final Identifier DENIED = Identifier.withDefaultNamespace("container/beacon/cancel");
	private static final Identifier SCROLLER = Identifier.withDefaultNamespace("widget/scroller");
	private static final Identifier SCROLLER_BACKGROUND = Identifier.withDefaultNamespace("widget/scroller_background");

	// Advancement window geometry (see AdvancementsScreen).
	private static final int WINDOW_WIDTH = 252;
	private static final int WINDOW_HEIGHT = 140;
	private static final int INNER_X = 9;
	private static final int INNER_Y = 18;
	private static final int INNER_WIDTH = 234;
	private static final int INNER_HEIGHT = 113;

	private static final int ROW_HEIGHT = 16;
	private static final int HEADER = 18;
	private static final int SCROLLBAR_WIDTH = 6;
	private static final int TITLE_COLOR = 0xFF404040;

	private PermissionsDataPayload data;
	private List<PermissionsDataPayload.Row> rows = List.of();
	private List<PermissionsDataPayload.Row> visible = List.of();
	private String filter = "";
	private double scroll;
	private boolean draggingScrollbar;
	private EditBox search;
	private int left;
	private int top;

	PermissionsScreen(PermissionsDataPayload data) {
		super(Component.translatable("chunkclaims.permissions.title"));
		update(data);
	}

	boolean isFor(PermissionsDataPayload other) {
		return data.dimension().equals(other.dimension()) && data.anchorX() == other.anchorX() && data.anchorZ() == other.anchorZ();
	}

	void update(PermissionsDataPayload fresh) {
		data = fresh;
		rows = new ArrayList<>(fresh.rows());
		refilter();
	}

	private void refilter() {
		String needle = filter.trim().toLowerCase(Locale.ROOT);
		visible = needle.isEmpty() ? rows : rows.stream().filter(r -> r.name().toLowerCase(Locale.ROOT).contains(needle)).toList();
		clampScroll();
	}

	@Override
	protected void init() {
		left = (width - WINDOW_WIDTH) / 2;
		top = (height - WINDOW_HEIGHT) / 2;
		search = new EditBox(font, left + INNER_X + 4, top + INNER_Y + 3, 120, 12, Component.translatable("chunkclaims.permissions.search"));
		search.setHint(Component.translatable("chunkclaims.permissions.search"));
		search.setMaxLength(32);
		search.setValue(filter);
		search.setResponder(text -> {
			filter = text;
			refilter();
		});
		addRenderableWidget(search);
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}

	// ---------------------------------------------------------------- layout

	private int listLeft() {
		return left + INNER_X + 4;
	}

	private int listRight() {
		return left + INNER_X + INNER_WIDTH - 4 - SCROLLBAR_WIDTH - 2;
	}

	private int listTop() {
		return top + INNER_Y + HEADER;
	}

	private int listBottom() {
		return top + INNER_Y + INNER_HEIGHT - 3;
	}

	private int maxScroll() {
		return Math.max(0, visible.size() * ROW_HEIGHT - (listBottom() - listTop()));
	}

	private void clampScroll() {
		scroll = Math.max(0, Math.min(scroll, maxScroll()));
	}

	private int rowAt(double mouseX, double mouseY) {
		if (mouseX < listLeft() || mouseX >= listRight() || mouseY < listTop() || mouseY >= listBottom()) {
			return -1;
		}
		int index = (int) ((mouseY - listTop() + scroll) / ROW_HEIGHT);
		return index < visible.size() ? index : -1;
	}

	// ---------------------------------------------------------------- drawing

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
		int innerLeft = left + INNER_X;
		int innerTop = top + INNER_Y;

		// Stone advancement-tab background, tiled, then darkened for legibility.
		for (int x = 0; x < INNER_WIDTH; x += 16) {
			for (int y = 0; y < INNER_HEIGHT; y += 16) {
				graphics.blit(RenderPipelines.GUI_TEXTURED, BACKGROUND, innerLeft + x, innerTop + y, 0, 0,
						Math.min(16, INNER_WIDTH - x), Math.min(16, INNER_HEIGHT - y), 16, 16);
			}
		}
		graphics.fill(innerLeft, innerTop, innerLeft + INNER_WIDTH, innerTop + INNER_HEIGHT, 0x88000000);

		long trusted = rows.stream().filter(PermissionsDataPayload.Row::allowed).count();
		Component summary = ClaimHud.plural("chunkclaims.permissions.area", data.clusterSize()).copy().append(" · ").append(Component.translatable("chunkclaims.permissions.trusted", trusted));
		graphics.text(font, summary, innerLeft + INNER_WIDTH - 4 - font.width(summary), innerTop + 5, 0xFFC0C0C0, true);

		extractRows(graphics, mouseX, mouseY);
		extractScrollbar(graphics);

		graphics.blit(RenderPipelines.GUI_TEXTURED, WINDOW, left, top, 0, 0, WINDOW_WIDTH, WINDOW_HEIGHT, 256, 256);
		graphics.text(font, title, left + 8, top + 6, TITLE_COLOR, false);

		super.extractRenderState(graphics, mouseX, mouseY, partialTick);
	}

	private void extractRows(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
		int x0 = listLeft();
		int x1 = listRight();
		if (visible.isEmpty()) {
			Component empty = Component.translatable(rows.isEmpty() ? "chunkclaims.permissions.empty" : "chunkclaims.permissions.no_match");
			graphics.centeredText(font, empty, (x0 + x1) / 2, listTop() + 20, 0xFFA0A0A0);
			return;
		}
		graphics.enableScissor(x0, listTop(), x1, listBottom());
		int hovered = rowAt(mouseX, mouseY);
		int first = (int) (scroll / ROW_HEIGHT);
		for (int i = first; i < visible.size(); i++) {
			int y = listTop() + i * ROW_HEIGHT - (int) scroll;
			if (y >= listBottom()) {
				break;
			}
			PermissionsDataPayload.Row row = visible.get(i);
			if (i == hovered) {
				graphics.fill(x0, y, x1, y + ROW_HEIGHT, 0x30FFFFFF);
			} else if (i % 2 == 1) {
				graphics.fill(x0, y, x1, y + ROW_HEIGHT, 0x18000000);
			}
			graphics.fill(x0 + 3, y + 6, x0 + 7, y + 10, row.online() ? 0xFF5BE36B : 0xFF5A5A5A);
			graphics.text(font, row.name(), x0 + 12, y + 4, row.allowed() ? 0xFFFFFFFF : 0xFFBDBDBD, true);
			graphics.blitSprite(RenderPipelines.GUI_TEXTURED, row.allowed() ? ALLOWED : DENIED, x1 - 19, y - 1, 18, 18);
		}
		graphics.disableScissor();

		if (hovered >= 0) {
			boolean allowed = visible.get(hovered).allowed();
			graphics.setTooltipForNextFrame(font,
					Component.translatable(allowed ? "chunkclaims.permissions.allowed" : "chunkclaims.permissions.denied"), mouseX, mouseY);
		}
	}

	private void extractScrollbar(GuiGraphicsExtractor graphics) {
		if (maxScroll() <= 0) {
			return;
		}
		int x = listRight() + 2;
		int height = listBottom() - listTop();
		int thumb = Math.max(12, height * height / (visible.size() * ROW_HEIGHT));
		int thumbY = listTop() + (int) ((height - thumb) * (scroll / maxScroll()));
		graphics.blitSprite(RenderPipelines.GUI_TEXTURED, SCROLLER_BACKGROUND, x, listTop(), SCROLLBAR_WIDTH, height);
		graphics.blitSprite(RenderPipelines.GUI_TEXTURED, SCROLLER, x, thumbY, SCROLLBAR_WIDTH, thumb);
	}

	// ---------------------------------------------------------------- input

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		if (super.mouseClicked(event, doubleClick)) {
			return true;
		}
		// 26.x numbers mouse buttons the SDL way: left is 1, not GLFW's 0.
		if (event.button() != InputConstants.MOUSE_BUTTON_LEFT) {
			return false;
		}
		int scrollbarX = listRight() + 2;
		if (maxScroll() > 0 && event.x() >= scrollbarX && event.x() < scrollbarX + SCROLLBAR_WIDTH
				&& event.y() >= listTop() && event.y() < listBottom()) {
			draggingScrollbar = true;
			scrollTo(event.y());
			return true;
		}
		int index = rowAt(event.x(), event.y());
		if (index >= 0) {
			// Players double-click names out of habit (server and world lists); the
			// second click would quietly undo the first, so it is ignored.
			if (!doubleClick) {
				toggle(visible.get(index));
			}
			return true;
		}
		return false;
	}

	@Override
	public boolean mouseDragged(MouseButtonEvent event, double dragX, double dragY) {
		if (draggingScrollbar) {
			scrollTo(event.y());
			return true;
		}
		return super.mouseDragged(event, dragX, dragY);
	}

	@Override
	public boolean mouseReleased(MouseButtonEvent event) {
		draggingScrollbar = false;
		return super.mouseReleased(event);
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
		scroll -= scrollY * ROW_HEIGHT;
		clampScroll();
		return true;
	}

	@Override
	public boolean keyPressed(KeyEvent event) {
		// The permissions hotkey closes the menu again, unless you are typing a name.
		if (!search.isFocused() && ChunkClaimsClient.permissionsKey.matches(event)) {
			onClose();
			return true;
		}
		return super.keyPressed(event);
	}

	private void scrollTo(double mouseY) {
		double fraction = (mouseY - listTop()) / (double) (listBottom() - listTop());
		scroll = fraction * maxScroll();
		clampScroll();
	}

	private void toggle(PermissionsDataPayload.Row row) {
		boolean allowed = !row.allowed();
		PermissionsDataPayload.Row flipped = new PermissionsDataPayload.Row(row.id(), row.name(), allowed, row.online());
		rows = new ArrayList<>(rows);
		rows.replaceAll(r -> r.id().equals(row.id()) ? flipped : r);
		refilter();
		minecraft.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1.0F));
		if (ClientPlayNetworking.canSend(SetPermissionPayload.TYPE)) {
			ClientPlayNetworking.send(new SetPermissionPayload(data.dimension(), data.anchorX(), data.anchorZ(), row.id(), allowed));
		}
	}
}
