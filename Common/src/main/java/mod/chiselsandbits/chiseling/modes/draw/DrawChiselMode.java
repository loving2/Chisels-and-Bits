package mod.chiselsandbits.chiseling.modes.draw;

import com.google.common.collect.Maps;
import mod.chiselsandbits.api.change.IChangeTrackerManager;
import mod.chiselsandbits.api.chiseling.IChiselingContext;
import mod.chiselsandbits.api.chiseling.mode.IChiselMode;
import mod.chiselsandbits.api.inventory.bit.IBitInventory;
import mod.chiselsandbits.api.inventory.management.IBitInventoryManager;
import mod.chiselsandbits.api.item.click.ClickProcessingState;
import mod.chiselsandbits.api.item.withmode.group.IToolModeGroup;
import mod.chiselsandbits.api.multistate.StateEntrySize;
import mod.chiselsandbits.api.multistate.accessor.IAreaAccessor;
import mod.chiselsandbits.api.multistate.mutator.batched.IBatchMutation;
import mod.chiselsandbits.api.util.RayTracingUtils;
import mod.chiselsandbits.platforms.core.registries.AbstractCustomRegistryEntry;
import mod.chiselsandbits.registrars.ModMetadataKeys;
import mod.chiselsandbits.utils.BitInventoryUtils;
import mod.chiselsandbits.utils.ItemStackUtils;
import net.minecraft.ChatFormatting;
import net.minecraft.Util;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.TranslatableComponent;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.NotNull;

import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

public class DrawChiselMode extends AbstractCustomRegistryEntry implements IChiselMode
{
    private final MutableComponent displayName;
    private final MutableComponent multiLineDisplayName;
    private final ResourceLocation          iconName;

    DrawChiselMode(final MutableComponent displayName, final MutableComponent multiLineDisplayName, final ResourceLocation iconName) {
        this.displayName = displayName;
        this.multiLineDisplayName = multiLineDisplayName;
        this.iconName = iconName;
    }

    @Override
    public ClickProcessingState onLeftClickBy(
      final Player playerEntity, final IChiselingContext context)
    {
        return processRayTraceIntoContext(
          playerEntity,
          context,
          direction -> Vec3.atLowerCornerOf(direction.getOpposite().getNormal()).multiply(StateEntrySize.current().getSizePerHalfBitScalingVector())
        );
    }

    @Override
    public void onStoppedLeftClicking(final Player playerEntity, final IChiselingContext context)
    {
        onLeftClickBy(playerEntity, context);
        context.setComplete();

        if (context.isSimulation())
            return;

        context.getMutator().ifPresent(mutator -> {
            try (IBatchMutation ignored =
                   mutator.batch(IChangeTrackerManager.getInstance().getChangeTracker(playerEntity)))
            {
                final Map<BlockState, Integer> resultingBitCount = Maps.newHashMap();

                mutator.inWorldMutableStream()
                  .forEach(state -> {
                      final BlockState currentState = state.getState();
                      if (context.tryDamageItem())
                      {
                          resultingBitCount.putIfAbsent(currentState, 0);
                          resultingBitCount.computeIfPresent(currentState, (s, currentCount) -> currentCount + 1);

                          state.clear();
                      }
                  });

                resultingBitCount.forEach((blockState, count) -> BitInventoryUtils.insertIntoOrSpawn(
                  playerEntity,
                  blockState,
                  count
                ));
            }
        });
    }

    @Override
    public ClickProcessingState onRightClickBy(final Player playerEntity, final IChiselingContext context)
    {
        return processRayTraceIntoContext(
          playerEntity,
          context,
          direction -> Vec3.atLowerCornerOf(direction.getNormal()).multiply(StateEntrySize.current().getSizePerHalfBitScalingVector())
        );
    }

    @Override
    public void onStoppedRightClicking(final Player playerEntity, final IChiselingContext context)
    {
        onRightClickBy(playerEntity, context);
        context.setComplete();

        if (context.isSimulation())
            return;

        context.getMutator().ifPresent(mutator -> {
            final BlockState heldBlockState = ItemStackUtils.getHeldBitBlockStateFromPlayer(playerEntity);
            if (heldBlockState.isAir())
            {
                return;
            }

            final int missingBitCount = (int) mutator.stream()
              .filter(state -> state.getState().isAir())
              .count();

            final IBitInventory playerBitInventory = IBitInventoryManager.getInstance().create(playerEntity);

            context.setComplete();
            if (playerBitInventory.canExtract(heldBlockState, missingBitCount) || playerEntity.isCreative())
            {
                if (!playerEntity.isCreative())
                {
                    playerBitInventory.extract(heldBlockState, missingBitCount);
                }

                try (IBatchMutation ignored =
                       mutator.batch(IChangeTrackerManager.getInstance().getChangeTracker(playerEntity)))
                {
                    mutator.inWorldMutableStream()
                      .filter(state -> state.getState().isAir())
                      .forEach(state -> state.overrideState(heldBlockState)); //We can use override state here to prevent the try-catch block.
                }
            }

            if (missingBitCount == 0)
            {
                final BlockPos heightPos = new BlockPos(mutator.getInWorldEndPoint());
                if (heightPos.getY() >= context.getWorld().getMaxBuildHeight())
                {
                    Component component = (new TranslatableComponent("build.tooHigh", context.getWorld().getMaxBuildHeight() - 1)).withStyle(ChatFormatting.RED);
                    playerEntity.sendMessage(component, Util.NIL_UUID);
                }
            }
        });
    }

    private ClickProcessingState processRayTraceIntoContext(final Player playerEntity, final IChiselingContext context, Function<Direction, Vec3> offsetGenerator) {
        final HitResult rayTraceResult = RayTracingUtils.rayTracePlayer(playerEntity);
        if (rayTraceResult.getType() != HitResult.Type.BLOCK || !(rayTraceResult instanceof final BlockHitResult blockRayTraceResult))
        {
            return ClickProcessingState.DEFAULT;
        }

        Optional<Vec3> anchor = context.getMetadata(ModMetadataKeys.ANCHOR.get());
        if (anchor.isEmpty()) {
            context.setMetadata(ModMetadataKeys.ANCHOR.get(), blockRayTraceResult.getLocation().add(offsetGenerator.apply(blockRayTraceResult.getDirection())));
            anchor = context.getMetadata(ModMetadataKeys.ANCHOR.get());
        }

        context.resetMutator();
        context.include(anchor.orElseThrow());
        context.include(blockRayTraceResult.getLocation().add(offsetGenerator.apply(blockRayTraceResult.getDirection())));

        return ClickProcessingState.ALLOW;
    }

    @Override
    public Optional<IAreaAccessor> getCurrentAccessor(final IChiselingContext context)
    {
        return context.getMutator()
                 .map(IAreaAccessor.class::cast);
    }

    @Override
    public @NotNull ResourceLocation getIcon()
    {
        return iconName;
    }

    @Override
    public @NotNull Optional<IToolModeGroup> getGroup()
    {
        return Optional.empty();
    }

    @Override
    public Component getDisplayName()
    {
        return displayName;
    }

    @Override
    public Component getMultiLineDisplayName()
    {
        return multiLineDisplayName;
    }
}
