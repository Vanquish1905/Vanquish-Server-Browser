package com.serverbrowser.gui;

import com.serverbrowser.Lang;
import com.serverbrowser.Nav;
import com.serverbrowser.config.Favorites;
import com.serverbrowser.data.ServerEntry;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;

/** Edit the personal note for one server (for example "Friendly admins, avoid player X"). */
public class NoteScreen extends Screen {
	private final BrowserScreen parent;
	private final ServerEntry entry;
	private EditBox box;

	NoteScreen(BrowserScreen parent, ServerEntry entry) {
		super(Lang.tr("serverbrowser.note.title"));
		this.parent = parent;
		this.entry = entry;
	}

	@Override
	protected void init() {
		int cx = this.width / 2;
		box = new EditBox(this.font, cx - 150, 70, 300, 20, Lang.tr("serverbrowser.note.title"));
		box.setMaxLength(Favorites.MAX_NOTE);
		box.setHint(Lang.tr("serverbrowser.note.hint"));
		box.setValue(Favorites.note(entry.ip));
		this.addRenderableWidget(box);
		this.setFocused(box);

		this.addRenderableWidget(Button.builder(Lang.tr("serverbrowser.note.save"), b -> save(box.getValue()))
				.bounds(cx - 150, 100, 96, 20).build());
		this.addRenderableWidget(Button.builder(Lang.tr("serverbrowser.note.clear"), b -> save(""))
				.bounds(cx - 48, 100, 96, 20).build());
		this.addRenderableWidget(Button.builder(Lang.tr("serverbrowser.note.cancel"), b -> onClose())
				.bounds(cx + 54, 100, 96, 20).build());
	}

	private void save(String text) {
		Favorites.setNote(entry.ip, entry.name, text);
		parent.noteChanged();
		Nav.open(this.minecraft, parent);
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
		super.extractRenderState(g, mouseX, mouseY, delta);
		g.text(this.font, this.title, this.width / 2 - this.font.width(this.title) / 2, 20, 0xFFFFFFFF, true);
		String who = entry.name + "  (" + entry.ip + ")";
		g.text(this.font, who, this.width / 2 - this.font.width(who) / 2, 44, 0xFFFFD27F, true);
	}

	@Override
	public void onClose() {
		Nav.open(this.minecraft, parent);
	}
}
