package mod.chiselsandbits.item;

import mod.chiselsandbits.api.change.IChangeTrackerManager;
import mod.chiselsandbits.api.exceptions.SpaceOccupiedException;
import mod.chiselsandbits.api.item.chiseled.IChiseledBlockItem;
import mod.chiselsandbits.api.item.multistate.IMultiStateItemStack;
import mod.chiselsandbits.api.modification.operation.IModificationOperation;
import mod.chiselsandbits.api.multistate.accessor.IAreaAccessor;
import mod.chiselsandbits.api.multistate.mutator.IMutatorFactory;
import mod.chiselsandbits.api.multistate.mutator.batched.IBatchMutation;
import mod.chiselsandbits.api.multistate.mutator.world.IWorldAreaMutator;
import mod.chiselsandbits.api.multistate.snapshot.IMultiStateSnapshot;
import mod.chiselsandbits.api.util.HelpTextUtils;
import mod.chiselsandbits.api.util.LocalStrings;
import mod.chiselsandbits.item.multistate.SingleBlockMultiStateItemStack;
import mod.chiselsandbits.registrars.ModModificationOperation;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Collection;
import java.util.List;

public class ChiseledBlockItem extends BlockItem implements IChiseledBlockItem
{

    public ChiseledBlockItem(final Block blockIn, final Properties builder)
    {
        super(blockIn, builder);
    }

    /**
     * Creates an itemstack aware context wrapper that gives access to the multistate information contained within the given itemstack.
     *
     * @param stack The stack to get an {@link IMultiStateItemStack} for.
     * @return The {@link IMultiStateItemStack} that represents the data in the given itemstack.
     */
    @NotNull
    @Override
    public IMultiStateItemStack createItemStack(final ItemStack stack)
    {
        return new SingleBlockMultiStateItemStack(stack);
    }

    @NotNull
    @Override
    public InteractionResult place(@NotNull final BlockPlaceContext context)
    {
        final IAreaAccessor source = this.createItemStack(context.getItemInHand());
        final IWorldAreaMutator areaMutator = context.getPlayer().isCrouching() ?
                                                IMutatorFactory.getInstance().covering(
                                                  context.getLevel(),
                                                  context.getClickLocation(),
                                                  context.getClickLocation().add(1d, 1d, 1d)
                                                )
                                                :
                                                  IMutatorFactory.getInstance().in(context.getLevel(), context.getClickedPos());
        final IMultiStateSnapshot attemptTarget = areaMutator.createSnapshot();

        final boolean noCollisions = source.stream().sequential()
          .allMatch(stateEntryInfo -> {
              try
              {
                  attemptTarget.setInAreaTarget(
                    stateEntryInfo.getState(),
                    stateEntryInfo.getStartPoint()
                  );

                  return true;
              }
              catch (SpaceOccupiedException exception)
              {
                  return false;
              }
          });

        if (noCollisions)
        {
            try (IBatchMutation ignored = areaMutator.batch(IChangeTrackerManager.getInstance().getChangeTracker(context.getPlayer())))
            {
                source.stream().sequential().forEach(
                  stateEntryInfo -> {
                      try
                      {
                          areaMutator.setInAreaTarget(
                            stateEntryInfo.getState(),
                            stateEntryInfo.getStartPoint()
                          );
                      }
                      catch (SpaceOccupiedException ignored1)
                      {
                      }
                  }
                );
            }


            if (context.getPlayer() == null || !context.getPlayer().isCreative()) {
                context.getItemInHand().shrink(1);
            }
            return InteractionResult.SUCCESS;
        }

        return InteractionResult.FAIL;
    }

    @Override
    public void appendHoverText(
      final @NotNull ItemStack stack, @Nullable final Level worldIn, final @NotNull List<Component> tooltip, final @NotNull TooltipFlag flagIn)
    {
        super.appendHoverText(stack, worldIn, tooltip, flagIn);
        HelpTextUtils.build(LocalStrings.HelpBitBag, tooltip);
    }

    @Override
    public boolean canPlace(final ItemStack heldStack, final Player playerEntity, final BlockHitResult blockRayTraceResult)
    {
        final IAreaAccessor source = this.createItemStack(heldStack);
        final Vec3 target = getTargetedBlockPos(heldStack, playerEntity, blockRayTraceResult);
        final IWorldAreaMutator areaMutator = IMutatorFactory.getInstance().covering(
          playerEntity.level,
          target,
          target.add(1,1,1));
        final IMultiStateSnapshot attemptTarget = areaMutator.createSnapshot();

        return source.stream()
          .allMatch(stateEntryInfo -> {
              try
              {
                  attemptTarget.setInAreaTarget(
                    stateEntryInfo.getState(),
                    stateEntryInfo.getStartPoint()
                  );

                  return true;
              }
              catch (SpaceOccupiedException exception)
              {
                  return false;
              }
          });
    }

    @Override
    public @NotNull IModificationOperation getMode(final ItemStack stack)
    {
        return ModModificationOperation.ROTATE_AROUND_X.get();
    }

    @Override
    public void setMode(final ItemStack stack, final IModificationOperation mode)
    {
        final IMultiStateItemStack multiStateItemStack = this.createItemStack(stack);
        mode.apply(multiStateItemStack);
    }

    @Override
    public @NotNull Collection<IModificationOperation> getPossibleModes()
    {
        return ModModificationOperation.REGISTRY_SUPPLIER.get().getValues();
    }

    @Override
    public boolean requiresUpdateOnClosure()
    {
        return false;
    }
}
