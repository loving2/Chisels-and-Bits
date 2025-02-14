package mod.chiselsandbits.pattern.placement;

import mod.chiselsandbits.api.block.IMultiStateBlock;
import mod.chiselsandbits.api.change.IChangeTrackerManager;
import mod.chiselsandbits.api.inventory.bit.IBitInventory;
import mod.chiselsandbits.api.inventory.management.IBitInventoryManager;
import mod.chiselsandbits.api.item.withmode.group.IToolModeGroup;
import mod.chiselsandbits.api.multistate.accessor.IStateEntryInfo;
import mod.chiselsandbits.api.multistate.mutator.IMutatorFactory;
import mod.chiselsandbits.api.multistate.mutator.batched.IBatchMutation;
import mod.chiselsandbits.api.multistate.mutator.world.IWorldAreaMutator;
import mod.chiselsandbits.api.multistate.snapshot.IMultiStateSnapshot;
import mod.chiselsandbits.api.pattern.placement.IPatternPlacementType;
import mod.chiselsandbits.api.pattern.placement.PlacementResult;
import mod.chiselsandbits.api.util.BlockPosStreamProvider;
import mod.chiselsandbits.api.util.LocalStrings;
import mod.chiselsandbits.platforms.core.registries.AbstractCustomRegistryEntry;
import mod.chiselsandbits.platforms.core.registries.SimpleChiselsAndBitsRegistryEntry;
import mod.chiselsandbits.registrars.ModPatternPlacementTypes;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jetbrains.annotations.NotNull;

import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import static mod.chiselsandbits.api.util.ColorUtils.MISSING_BITS_OR_SPACE_PATTERN_PLACEMENT_COLOR;
import static mod.chiselsandbits.api.util.ColorUtils.NOT_FITTING_PATTERN_PLACEMENT_COLOR;
import static mod.chiselsandbits.platforms.core.util.constants.Constants.MOD_ID;

public class CarvePatternPlacementType extends AbstractCustomRegistryEntry implements IPatternPlacementType
{
    @Override
    public @NotNull ResourceLocation getIcon()
    {
        return new ResourceLocation(
          MOD_ID,
          "textures/icons/pattern_carve.png"
        );
    }

    @Override
    public @NotNull Optional<IToolModeGroup> getGroup()
    {
        return Optional.empty();
    }

    @Override
    public VoxelShape buildVoxelShapeForWireframe(
      final IMultiStateSnapshot sourceSnapshot, final Player player, final Vec3 targetedPoint, final Direction hitFace)
    {
        return ModPatternPlacementTypes.PLACEMENT.get().buildVoxelShapeForWireframe(
          sourceSnapshot, player, targetedPoint, hitFace
        );
    }

    @Override
    public PlacementResult performPlacement(final IMultiStateSnapshot source, final BlockPlaceContext context, final boolean simulate)
    {
        final Vec3 targetedPosition = context.getPlayer().isCrouching() ?
                                            context.getClickLocation()
                                            : Vec3.atLowerCornerOf(context.getClickedPos().offset(context.getClickedFace().getOpposite().getNormal()));
        final IWorldAreaMutator areaMutator =
          IMutatorFactory.getInstance().covering(
            context.getLevel(),
            targetedPosition,
            targetedPosition.add(0.9999,0.9999,0.9999)
          );

        final boolean isChiseledBlock = BlockPosStreamProvider.getForRange(areaMutator.getInWorldStartPoint(), areaMutator.getInWorldEndPoint())
          .map(pos -> context.getLevel().getBlockState(pos))
          .allMatch(state -> state.getBlock() instanceof IMultiStateBlock);

        if (!isChiseledBlock)
        {
            return PlacementResult.failure(NOT_FITTING_PATTERN_PLACEMENT_COLOR, LocalStrings.PatternPlacementNotAChiseledBlock.getText());
        }

        final Map<BlockState, Integer> totalRemovedBits = source.stream()
          .filter(s -> !s.getState().isAir())
          .filter(s -> {
              final Optional<IStateEntryInfo> o = areaMutator.getInAreaTarget(s.getStartPoint());

              return o
                .filter(os -> !os.getState().isAir())
                .map(os -> !os.getState().equals(s.getState()))
                .orElse(false);
          })
          .map(s -> areaMutator.getInAreaTarget(s.getStartPoint()))
          .filter(Optional::isPresent)
          .map(Optional::get)
          .collect(Collectors.toMap(
            IStateEntryInfo::getState,
            s -> 1,
            Integer::sum
          ));

        final IBitInventory playerBitInventory = IBitInventoryManager.getInstance().create(context.getPlayer());
        final boolean hasRequiredSpace = context.getPlayer().isCreative() ||
                                           totalRemovedBits.entrySet().stream().allMatch(e -> playerBitInventory.canInsert(e.getKey(), e.getValue()));

        if (!hasRequiredSpace)
        {
            return PlacementResult.failure(MISSING_BITS_OR_SPACE_PATTERN_PLACEMENT_COLOR, LocalStrings.PatternPlacementNoBitSpace.getText());
        }

        if (simulate)
        {
            return PlacementResult.success();
        }

        try (IBatchMutation ignored = areaMutator.batch(IChangeTrackerManager.getInstance().getChangeTracker(context.getPlayer())))
        {
            source.stream()
              .filter(s -> !s.getState().isAir())
              .forEach(
                stateEntryInfo -> areaMutator.clearInAreaTarget(stateEntryInfo.getStartPoint())
              );
        }

        if (!context.getPlayer().isCreative())
        {
            totalRemovedBits.forEach(playerBitInventory::insertOrDiscard);
        }

        return PlacementResult.success();
    }

    @Override
    public Vec3 getTargetedPosition(
      final ItemStack heldStack, final Player player, final BlockHitResult blockRayTraceResult)
    {
        if (player.isCrouching())
        {
            return blockRayTraceResult.getLocation();
        }

        return Vec3.atLowerCornerOf(blockRayTraceResult.getBlockPos());
    }

    @Override
    public Component getDisplayName()
    {
        return LocalStrings.PatternPlacementModeCarving.getText();
    }
}
