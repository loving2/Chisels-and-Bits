package mod.chiselsandbits.chiseling;

import com.google.common.collect.Maps;
import mod.chiselsandbits.api.chiseling.ChiselingOperation;
import mod.chiselsandbits.api.chiseling.IChiselingContext;
import mod.chiselsandbits.api.chiseling.metadata.IMetadataKey;
import mod.chiselsandbits.api.chiseling.mode.IChiselMode;
import mod.chiselsandbits.api.item.chisel.IChiselingItem;
import mod.chiselsandbits.api.multistate.accessor.IAreaAccessor;
import mod.chiselsandbits.api.multistate.accessor.IStateEntryInfo;
import mod.chiselsandbits.api.multistate.mutator.IMutatorFactory;
import mod.chiselsandbits.api.multistate.mutator.world.IWorldAreaMutator;
import mod.chiselsandbits.api.permissions.IPermissionHandler;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.NotNull;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import java.util.function.Predicate;

public class ChiselingContext implements IChiselingContext
{
    private final LevelAccessor             world;
    private final IChiselMode        chiselMode;
    private final ChiselingOperation modeOfOperandus;
    private final boolean            simulation;
    private final Runnable           onCompleteCallback;
    private final ItemStack          causingItemStack;
    private final boolean      supportsDamaging;
    private final Player playerEntity;

    private boolean           complete = false;
    private IWorldAreaMutator                                   mutator       = null;
    private Function<IAreaAccessor, Predicate<IStateEntryInfo>> filterBuilder = null;
    private Map<IMetadataKey<?>, Object> metadataKeyMap = Maps.newHashMap();

    public ChiselingContext(
      final LevelAccessor world,
      final IChiselMode chiselMode,
      final ChiselingOperation modeOfOperandus,
      final boolean simulation,
      final Runnable onCompleteCallback,
      final ItemStack causingItemStack,
      final Player playerEntity)
    {
        this.world = world;
        this.chiselMode = chiselMode;
        this.simulation = simulation;
        this.onCompleteCallback = onCompleteCallback;
        this.modeOfOperandus = modeOfOperandus;
        this.causingItemStack = causingItemStack;

        if (this.causingItemStack.getItem() instanceof IChiselingItem) {
            this.supportsDamaging = ((IChiselingItem) this.causingItemStack.getItem()).isDamageableDuringChiseling();
        }
        else
        {
            this.supportsDamaging = false;
        }

        this.playerEntity = playerEntity;
    }

    private ChiselingContext(
      final LevelAccessor world,
      final IChiselMode chiselMode,
      final ChiselingOperation modeOfOperandus,
      final boolean complete,
      final IWorldAreaMutator mutator,
      final Player playerEntity)
    {
        this.world = world;
        this.chiselMode = chiselMode;
        this.causingItemStack = ItemStack.EMPTY;
        this.supportsDamaging = false;
        this.onCompleteCallback = () -> {}; //Noop this is the snapshot constructor which has no callback logic.
        this.simulation = true; //Always the case for snapshots.
        this.modeOfOperandus = modeOfOperandus;
        this.complete = complete;
        this.mutator = mutator;
        this.playerEntity = playerEntity;
    }

    private ChiselingContext(
      final LevelAccessor world,
      final IChiselMode chiselMode,
      final ChiselingOperation modeOfOperandus,
      final boolean complete,
      final Player playerEntity)
    {
        this.world = world;
        this.chiselMode = chiselMode;
        this.causingItemStack = ItemStack.EMPTY;
        this.supportsDamaging = false;
        this.onCompleteCallback = () -> {}; //Noop this is the snapshot constructor which has no callback logic.
        this.simulation = true; //Always the case for snapshots.
        this.modeOfOperandus = modeOfOperandus;
        this.complete = complete;
        this.playerEntity = playerEntity;
    }

    private void setMetadataKeyMap(final Map<IMetadataKey<?>, Object> metadataKeyMap)
    {
        this.metadataKeyMap = metadataKeyMap;
    }

    @Override
    public @NotNull Optional<IWorldAreaMutator> getMutator()
    {
        if (mutator == null || playerEntity == null || !(world instanceof Level))
            return Optional.ofNullable(mutator);

        if (!IPermissionHandler.getInstance().canManipulate(
          playerEntity, mutator
        )) {
            //We are not allowed to edit the current area.
            //Nuke it.
            mutator = null;
            return Optional.empty();
        }

        return Optional.of(mutator);
    }

    @Override
    public @NotNull LevelAccessor getWorld()
    {
        return world;
    }

    @Override
    public @NotNull IChiselMode getMode()
    {
        return chiselMode;
    }

    @Override
    public @NotNull IChiselingContext include(final Vec3 worldPosition)
    {
        if (getMutator().map(m -> !m.isInside(worldPosition)).orElse(true))
        {
            if (getMutator().isPresent())
            {
                final IWorldAreaMutator worldAreaMutator = getMutator().get();

                Vec3 start = new Vec3(
                  Math.min(worldPosition.x(), worldAreaMutator.getInWorldStartPoint().x()),
                  Math.min(worldPosition.y(), worldAreaMutator.getInWorldStartPoint().y()),
                  Math.min(worldPosition.z(), worldAreaMutator.getInWorldStartPoint().z())
                );
                Vec3 end = new Vec3(
                  Math.max(worldPosition.x(), worldAreaMutator.getInWorldEndPoint().x()),
                  Math.max(worldPosition.y(), worldAreaMutator.getInWorldEndPoint().y()),
                  Math.max(worldPosition.z(), worldAreaMutator.getInWorldEndPoint().z())
                );

                this.mutator = IMutatorFactory.getInstance().covering(world, start, end);
            }
            else
            {
                this.mutator = IMutatorFactory.getInstance().covering(
                  world,
                  worldPosition,
                  worldPosition
                );
            }
        }

        return this;
    }

    @Override
    public void setComplete()
    {
        this.complete = true;
        this.onCompleteCallback.run();
    }

    @Override
    public boolean isComplete()
    {
        return complete;
    }

    @Override
    public boolean isSimulation()
    {
        return simulation;
    }

    @Override
    public @NotNull ChiselingOperation getModeOfOperandus()
    {
        return modeOfOperandus;
    }

    @Override
    public @NotNull IChiselingContext createSnapshot()
    {
        final ChiselingContext context = createInnerSnapshot();
        final Map<IMetadataKey<?>, Object> newMetadata = Maps.newHashMap();
        for (final IMetadataKey<?> key : this.metadataKeyMap.keySet())
        {
            final Optional<?> value = snapshotMetadata(key);
            value.ifPresent(o -> newMetadata.put(key, o));
        }

        context.setMetadataKeyMap(newMetadata);
        return context;
    }

    private @NotNull ChiselingContext createInnerSnapshot() {
        if (mutator == null) {
            return new ChiselingContext(
              world,
              chiselMode,
              modeOfOperandus,
              this.complete,
              playerEntity
            );
        }

        return new ChiselingContext(
          world,
          chiselMode,
          modeOfOperandus,
          this.complete,
          IMutatorFactory.getInstance().covering(
            world,
            mutator.getInWorldStartPoint(),
            mutator.getInWorldEndPoint()
          ),
          playerEntity
        );
    }

    private <T> Optional<T> snapshotMetadata(final IMetadataKey<T> key) {
        final Optional<T> value = getMetadata(key);
        return value.map(key::snapshot);
    }

    @Override
    public boolean tryDamageItem(final int damage)
    {
        if (!this.supportsDamaging || this.simulation)
            return true;

        final AtomicBoolean broken = new AtomicBoolean(false);
        this.causingItemStack.hurtAndBreak(damage, playerEntity, playerEntity -> {
            broken.set(true);

            InteractionHand hand = InteractionHand.MAIN_HAND;
            if (playerEntity.getOffhandItem() == causingItemStack)
                hand = InteractionHand.OFF_HAND;
            playerEntity.broadcastBreakEvent(hand);
        });

        return !broken.get();
    }

    @Override
    public void setStateFilter(final @NotNull Function<IAreaAccessor, Predicate<IStateEntryInfo>> filter)
    {
        this.filterBuilder = filter;
    }

    @Override
    public void clearStateFilter()
    {
        this.filterBuilder = null;
    }

    @Override
    public Optional<Function<IAreaAccessor, Predicate<IStateEntryInfo>>> getStateFilter()
    {
        return Optional.ofNullable(this.filterBuilder);
    }

    @SuppressWarnings("unchecked")
    @Override
    public <T> Optional<T> getMetadata(final IMetadataKey<T> key)
    {
        final Object value = this.metadataKeyMap.get(key);
        if (value == null)
            return Optional.empty();

        try {
            final T castValue = (T) value;
            return Optional.ofNullable(castValue);
        } catch (ClassCastException e) {
            return Optional.empty();
        }
    }

    @Override
    public void removeMetadata(final IMetadataKey<?> key)
    {
         this.metadataKeyMap.remove(key);
    }

    @Override
    public <T> void setMetadata(final IMetadataKey<T> key, final T value)
    {
        this.metadataKeyMap.put(key, value);
    }

    @Override
    public void resetMutator()
    {
        this.mutator = null;
    }

    @Override
    public Optional<IStateEntryInfo> getInAreaTarget(final Vec3 inAreaTarget)
    {
        if (getMutator().isPresent() && getMutator().map(m -> m.isInside(inAreaTarget)).orElse(false))
        {
            return getMutator().flatMap(m -> m.getInAreaTarget(inAreaTarget));
        }

        final BlockPos position = new BlockPos(inAreaTarget);
        final Vec3 inBlockOffset = inAreaTarget.subtract(position.getX(), position.getY(), position.getZ());

        return IMutatorFactory.getInstance().in(
          getWorld(),
          position
        ).getInAreaTarget(
          inBlockOffset
        );
    }

    @Override
    public Optional<IStateEntryInfo> getInBlockTarget(final BlockPos inAreaBlockPosOffset, final Vec3 inBlockTarget)
    {
        return getInAreaTarget(Vec3.atLowerCornerOf(inAreaBlockPosOffset).add(inBlockTarget));
    }
}
