package mod.chiselsandbits.keys.contexts;

import mod.chiselsandbits.client.screens.ToolModeSelectionScreen;
import mod.chiselsandbits.platforms.core.client.key.IKeyConflictContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;

public enum SpecificScreenOpenKeyConflictContext implements IKeyConflictContext
{

    RADIAL_TOOL_MENU(ToolModeSelectionScreen.class);

    private final Class<? extends Screen> guiScreenClass;

    SpecificScreenOpenKeyConflictContext(final Class<? extends Screen> guiScreenClass) {this.guiScreenClass = guiScreenClass;}

    /**
     * @return true if conditions are met to activate keybindings with this context
     */
    @Override
    public boolean isActive()
    {
        return Minecraft.getInstance().screen != null && Minecraft.getInstance().screen.getClass() == guiScreenClass;
    }

    /**
     * @param other The other conflict context.
     * @return true if the other context can have keybindings conflicts with this one. This will be called on both contexts to check for conflicts.
     */
    @Override
    public boolean conflicts(final IKeyConflictContext other)
    {
        return this == other;
    }
}
