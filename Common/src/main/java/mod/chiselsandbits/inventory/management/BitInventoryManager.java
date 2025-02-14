package mod.chiselsandbits.inventory.management;

import mod.chiselsandbits.api.inventory.bit.IBitInventory;
import mod.chiselsandbits.api.inventory.bit.IBitInventoryItem;
import mod.chiselsandbits.api.inventory.management.IBitInventoryManager;
import mod.chiselsandbits.inventory.bit.IInventoryBitInventory;
import mod.chiselsandbits.inventory.bit.IllegalBitInventory;
import mod.chiselsandbits.inventory.player.PlayerMainAndOffhandInventoryWrapper;
import mod.chiselsandbits.platforms.core.inventory.bit.IAdaptingBitInventoryManager;
import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

public class BitInventoryManager implements IBitInventoryManager
{
    private static final BitInventoryManager INSTANCE = new BitInventoryManager();

    private BitInventoryManager()
    {
    }

    public static BitInventoryManager getInstance()
    {
        return INSTANCE;
    }

    @Override
    public IBitInventory create(final Player playerEntity)
    {
        return this.create(new PlayerMainAndOffhandInventoryWrapper(playerEntity.getInventory()));
    }

    @Override
    public IBitInventory create(final Object target)
    {
        return IAdaptingBitInventoryManager.getInstance()
                 .create(target)
                 .filter(IBitInventory.class::isInstance)
                 .map(IBitInventory.class::cast)
                 .orElseThrow(() -> new IllegalArgumentException("The given target object is not supported on the current platform!"));
    }

    @Override
    public IBitInventory create(final Container inventory)
    {
        return new IInventoryBitInventory(inventory);
    }

    @Override
    public IBitInventory create(final ItemStack stack)
    {
        if (stack.getItem() instanceof IBitInventoryItem) {
            return ((IBitInventoryItem) stack.getItem()).create(stack);
        }

        return new IllegalBitInventory();
    }
}
