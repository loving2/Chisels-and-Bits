package mod.chiselsandbits.platforms.core.entity;

import mod.chiselsandbits.platforms.core.IChiselsAndBitsPlatformCore;
import net.minecraft.world.entity.player.Player;

/**
 * Gives access to a platforms specific entity information storage system.
 */
public interface IEntityInformationManager
{

    /**
     * Gives access to the current platform's way of processing entity information.
     *
     * @return The entity information manager.
     */
    static IEntityInformationManager getInstance() {
        return IChiselsAndBitsPlatformCore.getInstance().getEntityInformationManager();
    }

    /**
     * Determines the reach distance of the player.
     * Might be constant or attribute dependent.
     *
     * @param player The player in question.
     * @return The reach distance for the given player.
     */
    double getReachDistance(final Player player);
}
