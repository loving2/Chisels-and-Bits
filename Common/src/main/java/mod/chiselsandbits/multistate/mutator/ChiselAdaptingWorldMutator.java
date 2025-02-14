package mod.chiselsandbits.multistate.mutator;

import mod.chiselsandbits.api.block.entity.IMultiStateBlockEntity;
import mod.chiselsandbits.api.block.state.id.IBlockStateIdManager;
import mod.chiselsandbits.api.block.storage.IStateEntryStorage;
import mod.chiselsandbits.api.change.IChangeTracker;
import mod.chiselsandbits.api.chiseling.conversion.IConversionManager;
import mod.chiselsandbits.api.chiseling.eligibility.IEligibilityManager;
import mod.chiselsandbits.api.exceptions.SpaceOccupiedException;
import mod.chiselsandbits.api.multistate.StateEntrySize;
import mod.chiselsandbits.api.multistate.accessor.IStateEntryInfo;
import mod.chiselsandbits.api.multistate.accessor.identifier.IAreaShapeIdentifier;
import mod.chiselsandbits.api.multistate.accessor.identifier.ISingleStateAreaShareIdentifier;
import mod.chiselsandbits.api.multistate.accessor.sortable.IPositionMutator;
import mod.chiselsandbits.api.multistate.mutator.IMutableStateEntryInfo;
import mod.chiselsandbits.api.multistate.mutator.batched.IBatchMutation;
import mod.chiselsandbits.api.multistate.mutator.callback.StateClearer;
import mod.chiselsandbits.api.multistate.mutator.callback.StateSetter;
import mod.chiselsandbits.api.multistate.mutator.world.IInWorldMutableStateEntryInfo;
import mod.chiselsandbits.api.multistate.mutator.world.IWorldAreaMutator;
import mod.chiselsandbits.api.multistate.snapshot.IMultiStateSnapshot;
import mod.chiselsandbits.api.util.BlockPosStreamProvider;
import mod.chiselsandbits.block.entities.storage.SimpleStateEntryStorage;
import mod.chiselsandbits.multistate.snapshot.EmptySnapshot;
import mod.chiselsandbits.utils.MultiStateSnapshotUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.data.BuiltinRegistries;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.phys.Vec3;

import java.util.Objects;
import java.util.Optional;
import java.util.stream.Stream;

public class ChiselAdaptingWorldMutator implements IWorldAreaMutator
{
    public static final BlockState DEFAULT_STATE = Blocks.STONE.defaultBlockState();
    private final LevelAccessor world;
    private final BlockPos pos;

    public ChiselAdaptingWorldMutator(final LevelAccessor world, final BlockPos pos)
    {
        this.world = world;
        this.pos = pos;
    }

    /**
     * Creates a new area shape identifier.
     * <p>
     * Note: This method always returns a new instance.
     *
     * @return The new identifier.
     */
    @Override
    public IAreaShapeIdentifier createNewShapeIdentifier()
    {
        if (getWorld().isOutsideBuildHeight(getPos())) {
            return new PreAdaptedShapeIdentifier(getWorld().getBlockState(getPos()));
        }

        final BlockEntity tileEntity = getWorld().getBlockEntity(getPos());
        if (tileEntity instanceof IMultiStateBlockEntity)
        {
            return ((IMultiStateBlockEntity) tileEntity).createNewShapeIdentifier();
        }

        return new PreAdaptedShapeIdentifier(getWorld().getBlockState(getPos()));
    }

    @SuppressWarnings("deprecation")
    @Override
    public Stream<IStateEntryInfo> stream()
    {
        if (getWorld().isOutsideBuildHeight(getPos())) {
            return Stream.empty();
        }

        final BlockEntity tileEntity = getWorld().getBlockEntity(getPos());
        if (tileEntity instanceof IMultiStateBlockEntity)
        {
            return ((IMultiStateBlockEntity) tileEntity).stream();
        }

        final BlockState currentState = getWorld().getBlockState(getPos());
        if (IEligibilityManager.getInstance().canBeChiseled(currentState) ||
            currentState.isAir())
        {
            return BlockPosStreamProvider.getForRange(StateEntrySize.current().getBitsPerBlockSide())
                     .map(blockPos -> new MutablePreAdaptedStateEntry(
                         currentState,
                         getWorld(),
                         getPos(),
                         blockPos,
                         this::setInAreaTarget,
                         this::clearInAreaTarget
                       )
                     );
        }

        return Stream.empty();
    }

    /**
     * Gets the target state in the current area, using the offset from the area as well as the in area target offset.
     *
     * @param inAreaTarget The in area offset.
     * @return An optional potentially containing the state entry of the requested target.
     */
    @Override
    public Optional<IStateEntryInfo> getInAreaTarget(final Vec3 inAreaTarget)
    {
        if (getWorld().isOutsideBuildHeight(getPos())) {
            return Optional.empty();
        }

        if (inAreaTarget.x() < 0 ||
              inAreaTarget.y() < 0 ||
              inAreaTarget.z() < 0)
        {
            return Optional.empty();
        }

        if (inAreaTarget.x() >= 1 ||
              inAreaTarget.y() >= 1 ||
              inAreaTarget.z() >= 1)
        {
            return Optional.empty();
        }

        final BlockEntity tileEntity = getWorld().getBlockEntity(getPos());
        if (tileEntity instanceof IMultiStateBlockEntity)
        {
            return ((IMultiStateBlockEntity) tileEntity).getInAreaTarget(inAreaTarget);
        }

        final BlockState currentState = getWorld().getBlockState(getPos());

        return Optional.of(new MutablePreAdaptedStateEntry(
          currentState,
          getWorld(),
          getPos(),
          new BlockPos(inAreaTarget.multiply(StateEntrySize.current().getBitsPerBlockSide(),
            StateEntrySize.current().getBitsPerBlockSide(),
            StateEntrySize.current().getBitsPerBlockSide())),
          this::setInAreaTarget,
          this::clearInAreaTarget
        ));
    }

    /**
     * Gets the target state in the current area, using the in area block position offset as well as the in block target offset to calculate the in area offset for setting.
     *
     * @param inAreaBlockPosOffset The offset of blocks in the current area.
     * @param inBlockTarget        The offset in the targeted block.
     * @return An optional potentially containing the state entry of the requested target.
     */
    @Override
    public Optional<IStateEntryInfo> getInBlockTarget(final BlockPos inAreaBlockPosOffset, final Vec3 inBlockTarget)
    {
        if (!inAreaBlockPosOffset.equals(BlockPos.ZERO))
        {
            throw new IllegalArgumentException("The chisel adapting world mutator can only mutate the given single block!");
        }

        return getInAreaTarget(inBlockTarget);
    }

    /**
     * Indicates if the given target is inside of the current accessor.
     *
     * @param inAreaTarget The area target to check.
     * @return True when inside, false when not.
     */
    @Override
    public boolean isInside(final Vec3 inAreaTarget)
    {
        if (getWorld().isOutsideBuildHeight(getPos())) {
            return false;
        }

        return !(inAreaTarget.x() < 0) &&
                 !(inAreaTarget.y() < 0) &&
                 !(inAreaTarget.z() < 0) &&
                 !(inAreaTarget.x() >= 1) &&
                 !(inAreaTarget.y() >= 1) &&
                 !(inAreaTarget.z() >= 1);
    }

    /**
     * Indicates if the given target (with the given block position offset) is inside of the current accessor.
     *
     * @param inAreaBlockPosOffset The offset of blocks in the current area.
     * @param inBlockTarget        The offset in the targeted block.
     * @return True when inside, false when not.
     */
    @Override
    public boolean isInside(final BlockPos inAreaBlockPosOffset, final Vec3 inBlockTarget)
    {
        if (!inAreaBlockPosOffset.equals(BlockPos.ZERO))
        {
            return false;
        }

        return isInside(inBlockTarget);
    }

    @Override
    public IMultiStateSnapshot createSnapshot()
    {
        if (getWorld().isOutsideBuildHeight(getPos())) {
            return EmptySnapshot.INSTANCE;
        }

        final BlockEntity tileEntity = getWorld().getBlockEntity(getPos());
        if (tileEntity instanceof IMultiStateBlockEntity)
        {
            return ((IMultiStateBlockEntity) tileEntity).createSnapshot();
        }

        final BlockState blockState = getWorld().getBlockState(getPos());
        final IStateEntryStorage temporarySection = new SimpleStateEntryStorage();
        for (int x = 0; x < StateEntrySize.current().getBitsPerBlockSide(); x++)
        {
            for (int y = 0; y < StateEntrySize.current().getBitsPerBlockSide(); y++)
            {
                for (int z = 0; z < StateEntrySize.current().getBitsPerBlockSide(); z++)
                {
                    temporarySection.setBlockState(x, y, z, blockState);
                }
            }
        }

        return MultiStateSnapshotUtils.createFromStorage(temporarySection);
    }

    @Override
    public Stream<IStateEntryInfo> streamWithPositionMutator(final IPositionMutator positionMutator)
    {
        if (getWorld().isOutsideBuildHeight(getPos())) {
            return Stream.empty();
        }

        final BlockEntity tileEntity = getWorld().getBlockEntity(getPos());
        if (tileEntity instanceof IMultiStateBlockEntity)
        {
            return ((IMultiStateBlockEntity) tileEntity).streamWithPositionMutator(positionMutator);
        }

        final BlockState currentState = getWorld().getBlockState(getPos());
        if (IEligibilityManager.getInstance().canBeChiseled(currentState) ||
              currentState.isAir())
        {
            return BlockPosStreamProvider.getForRange(StateEntrySize.current().getBitsPerBlockSide())
                     .map(positionMutator::mutate)
                     .map(blockPos -> new MutablePreAdaptedStateEntry(
                         currentState,
                         getWorld(),
                         getPos(),
                         blockPos,
                         this::setInAreaTarget,
                         this::clearInAreaTarget
                       )
                     );
        }

        return Stream.empty();
    }

    @Override
    public LevelAccessor getWorld()
    {
        return world;
    }

    public BlockPos getPos()
    {
        return pos;
    }

    @Override
    public Vec3 getInWorldStartPoint()
    {
        return Vec3.atLowerCornerOf(pos);
    }

    @Override
    public Vec3 getInWorldEndPoint()
    {
        return Vec3.atLowerCornerOf(pos).add(
          15 * StateEntrySize.current().getSizePerBit(),
          15 * StateEntrySize.current().getSizePerBit(),
          15 * StateEntrySize.current().getSizePerBit()
        );
    }

    /**
     * Returns all entries in the current area in a mutable fashion. Includes all empty areas as areas containing an air state.
     *
     * @return A stream with a mutable state entry info for each mutable section in the area.
     */
    @Override
    public Stream<IMutableStateEntryInfo> mutableStream()
    {
        if (getWorld().isOutsideBuildHeight(getPos())) {
            return Stream.empty();
        }

        final BlockEntity tileEntity = getWorld().getBlockEntity(getPos());
        if (tileEntity instanceof IMultiStateBlockEntity)
        {
            return ((IMultiStateBlockEntity) tileEntity).mutableStream();
        }

        final BlockState currentState = getWorld().getBlockState(getPos());
        if (IEligibilityManager.getInstance().canBeChiseled(currentState))
        {
            return BlockPosStreamProvider.getForRange(StateEntrySize.current().getBitsPerBlockSide())
                     .map(blockPos -> new MutablePreAdaptedStateEntry(
                         currentState,
                         getWorld(),
                         getPos(),
                         blockPos,
                         this::setInAreaTarget,
                         this::clearInAreaTarget
                       )
                     );
        }

        return Stream.empty();
    }

    @SuppressWarnings("deprecation")
    @Override
    public void setInAreaTarget(final BlockState blockState, final Vec3 inAreaTarget) throws SpaceOccupiedException
    {
        if (getWorld().isOutsideBuildHeight(getPos())) {
            return;
        }

        if (inAreaTarget.x() < 0 ||
              inAreaTarget.y() < 0 ||
              inAreaTarget.z() < 0)
        {
            throw new IllegalArgumentException(
              "The chisel adapting world mutator can only mutate blocks with an in area offset greater or equal to 0. Requested was: " + inAreaTarget);
        }

        if (inAreaTarget.x() >= 1 ||
              inAreaTarget.y() >= 1 ||
              inAreaTarget.z() >= 1)
        {
            throw new IllegalArgumentException(
              "The chisel adapting world mutator can only mutate blocks with an in area offset smaller then 1. Requested was: " + inAreaTarget);
        }

        final BlockEntity tileEntity = getWorld().getBlockEntity(getPos());
        if (tileEntity instanceof IMultiStateBlockEntity)
        {
            ((IMultiStateBlockEntity) tileEntity).setInAreaTarget(blockState, inAreaTarget);
            return;
        }

        final BlockState currentState = getWorld().getBlockState(getPos());
        if (!currentState.isAir())
        {
            throw new SpaceOccupiedException();
        }

        final Optional<Block> optionalWithConvertedBlock = IConversionManager.getInstance().getChiseledVariantOf(blockState);
        if (optionalWithConvertedBlock.isPresent())
        {
            final Block convertedBlock = optionalWithConvertedBlock.get();
            getWorld().setBlock(
              getPos(),
              convertedBlock.defaultBlockState(),
              Block.UPDATE_ALL
            );

            final BlockEntity convertedTileEntity = getWorld().getBlockEntity(getPos());
            if (convertedTileEntity instanceof IMultiStateBlockEntity)
            {
                ((IMultiStateBlockEntity) convertedTileEntity).initializeWith(currentState);
                ((IMultiStateBlockEntity) convertedTileEntity).setInAreaTarget(blockState, inAreaTarget);
                return;
            }

            throw new IllegalStateException("Conversion of the existing block of type: " + currentState + " into a chiseled variant failed.");
        }
    }

    @Override
    public void setInBlockTarget(final BlockState blockState, final BlockPos inAreaBlockPosOffset, final Vec3 inBlockTarget) throws SpaceOccupiedException
    {
        if (!inAreaBlockPosOffset.equals(BlockPos.ZERO))
        {
            throw new IllegalArgumentException("The chisel adapting world mutator can only mutate the given single block!");
        }

        this.setInAreaTarget(blockState, inBlockTarget);
    }

    /**
     * Clears the current area, using the offset from the area as well as the in area target offset.
     *
     * @param inAreaTarget The in area offset.
     */
    @SuppressWarnings("deprecation")
    @Override
    public void clearInAreaTarget(final Vec3 inAreaTarget)
    {
        if (getWorld().isOutsideBuildHeight(getPos())) {
            return;
        }

        if (inAreaTarget.x() < 0 ||
              inAreaTarget.y() < 0 ||
              inAreaTarget.z() < 0)
        {
            throw new IllegalArgumentException(
              "The chisel adapting world mutator can only mutate blocks with an in area offset greater or equal to 0. Requested was: " + inAreaTarget);
        }

        if (inAreaTarget.x() > 1 ||
              inAreaTarget.y() > 1 ||
              inAreaTarget.z() > 1)
        {
            throw new IllegalArgumentException(
              "The chisel adapting world mutator can only mutate blocks with an in area offset smaller then 1. Requested was: " + inAreaTarget);
        }

        final BlockEntity tileEntity = getWorld().getBlockEntity(getPos());
        if (tileEntity instanceof IMultiStateBlockEntity)
        {
            ((IMultiStateBlockEntity) tileEntity).clearInAreaTarget(inAreaTarget);
            return;
        }

        final BlockState currentState = getWorld().getBlockState(getPos());
        if (currentState.isAir())
        {
            return;
        }

        final Optional<Block> optionalWithConvertedBlock = IConversionManager.getInstance().getChiseledVariantOf(currentState);
        if (optionalWithConvertedBlock.isPresent())
        {
            final Block convertedBlock = optionalWithConvertedBlock.get();
            getWorld().setBlock(
              getPos(),
              convertedBlock.defaultBlockState(),
              Block.UPDATE_ALL
            );

            final BlockEntity convertedTileEntity = getWorld().getBlockEntity(getPos());
            if (convertedTileEntity instanceof IMultiStateBlockEntity)
            {
                ((IMultiStateBlockEntity) convertedTileEntity).initializeWith(currentState);
                ((IMultiStateBlockEntity) convertedTileEntity).clearInAreaTarget(inAreaTarget);
                return;
            }

            throw new IllegalStateException("Conversion of the existing block of type: " + currentState + " into a chiseled variant failed.");
        }
    }

    /**
     * Clears the current area, using the in area block position offset as well as the in block target offset to calculate the in area offset for setting.
     *
     * @param inAreaBlockPosOffset The offset of blocks in the current area.
     * @param inBlockTarget        The offset in the targeted block.
     */
    @Override
    public void clearInBlockTarget(final BlockPos inAreaBlockPosOffset, final Vec3 inBlockTarget)
    {
        if (!inAreaBlockPosOffset.equals(BlockPos.ZERO))
        {
            throw new IllegalArgumentException("The chisel adapting world mutator can only mutate the given single block!");
        }

        this.clearInAreaTarget(inBlockTarget);
    }

    /**
     * Returns all entries in the current area in a mutable fashion. Includes all empty areas as areas containing an air state.
     *
     * @return A stream with a mutable state entry info for each mutable section in the area.
     */
    @SuppressWarnings("deprecation")
    @Override
    public Stream<IInWorldMutableStateEntryInfo> inWorldMutableStream()
    {
        if (getWorld().isOutsideBuildHeight(getPos())) {
            return Stream.empty();
        }

        final BlockEntity tileEntity = getWorld().getBlockEntity(getPos());
        if (tileEntity instanceof IMultiStateBlockEntity)
        {
            return ((IMultiStateBlockEntity) tileEntity).inWorldMutableStream();
        }

        final BlockState currentState = getWorld().getBlockState(getPos());
        if (IEligibilityManager.getInstance().canBeChiseled(currentState) ||
            currentState.isAir())
        {
            return BlockPosStreamProvider.getForRange(StateEntrySize.current().getBitsPerBlockSide())
                     .map(blockPos -> new MutablePreAdaptedStateEntry(
                         currentState,
                         getWorld(),
                         getPos(),
                         blockPos,
                         this::setInAreaTarget,
                         this::clearInAreaTarget
                       )
                     );
        }

        return Stream.empty();
    }

    /**
     * Trigger a batch mutation start.
     * <p>
     * As long as at least one batch mutation is still running no changes are transmitted to the client.
     *
     * @return The batch mutation lock.
     */
    @SuppressWarnings("deprecation")
    @Override
    public IBatchMutation batch()
    {
        if (getWorld().isOutsideBuildHeight(getPos())) {
            return () -> {
                //Noop
            };
        }

        final BlockEntity tileEntity = getWorld().getBlockEntity(getPos());
        if (tileEntity instanceof IMultiStateBlockEntity)
        {
            return ((IMultiStateBlockEntity) tileEntity).batch();
        }

        BlockState currentState = getWorld().getBlockState(getPos());
        BlockState initializationState = currentState;
        if (currentState.isAir())
        {
            //This happens when placing into an empty blockspace.
            //We will assume a simple rock as the base material. The TE will fix itself after the placement.
            currentState = DEFAULT_STATE;
            initializationState = Blocks.AIR.defaultBlockState();
        }

        if (!IEligibilityManager.getInstance().canBeChiseled(currentState) && !currentState.isAir())
        {
            return () -> {
                //Noop
            };
        }

        final Optional<Block> optionalWithConvertedBlock = IConversionManager.getInstance().getChiseledVariantOf(currentState);
        if (optionalWithConvertedBlock.isPresent())
        {
            final Block convertedBlock = optionalWithConvertedBlock.get();
            getWorld().setBlock(
              getPos(),
              convertedBlock.defaultBlockState(),
              Block.UPDATE_ALL
            );

            final BlockEntity convertedTileEntity = getWorld().getBlockEntity(getPos());
            if (convertedTileEntity instanceof IMultiStateBlockEntity)
            {
                final IBatchMutation batchMutation = ((IMultiStateBlockEntity) convertedTileEntity).batch();
                ((IMultiStateBlockEntity) convertedTileEntity).initializeWith(initializationState);
                return batchMutation;
            }

            throw new IllegalStateException("Conversion of the existing block of type: " + currentState + " into a chiseled variant failed.");
        }

        return () -> {
            //Noop
        };
    }

    @Override
    public IBatchMutation batch(final IChangeTracker changeTracker)
    {
        if (getWorld().isOutsideBuildHeight(getPos())) {
            return () -> {
                //Noop
            };
        }

        final BlockState currentState = getWorld().getBlockState(getPos());
        if (!IEligibilityManager.getInstance().canBeChiseled(currentState) && !currentState.isAir())
        {
            return () -> {
                //Noop
            };
        }

        final IBatchMutation innerMutation = batch();
        final BlockEntity tileEntity = getWorld().getBlockEntity(getPos());
        if (tileEntity instanceof IMultiStateBlockEntity) {
            final IMultiStateSnapshot before = ((IMultiStateBlockEntity) tileEntity).createSnapshot();
            return () -> {
                final IMultiStateSnapshot after = ((IMultiStateBlockEntity) tileEntity).createSnapshot();
                innerMutation.close();
                changeTracker.onBlockUpdated(getPos(), before, after);
            };
        }
        return innerMutation;
    }

    private static class MutablePreAdaptedStateEntry implements IInWorldMutableStateEntryInfo
    {

        private final BlockState blockState;
        private final LevelAccessor     world;
        private final Vec3     startPoint;
        private final Vec3     endPoint;
        private final BlockPos     blockPos;

        private final StateSetter  setCallback;
        private final StateClearer clearCallback;

        public MutablePreAdaptedStateEntry(
          final BlockState blockState,
          final LevelAccessor world,
          final BlockPos blockPos,
          final Vec3i inBlockOffset,
          final StateSetter setCallback,
          final StateClearer clearCallback)
        {
            this.blockState = blockState;
            this.world = world;
            this.blockPos = blockPos;
            this.startPoint = Vec3.atLowerCornerOf(inBlockOffset).multiply(StateEntrySize.current().getSizePerBit(), StateEntrySize.current().getSizePerBit(), StateEntrySize.current().getSizePerBit());
            this.setCallback = setCallback;
            this.clearCallback = clearCallback;
            this.endPoint = this.startPoint.add(StateEntrySize.current().getSizePerBit(), StateEntrySize.current().getSizePerBit(), StateEntrySize.current().getSizePerBit());
        }

        /**
         * The state that this entry represents.
         *
         * @return The state.
         */
        @Override
        public BlockState getState()
        {
            return blockState;
        }

        /**
         * The start (lowest on all three axi) position of the state that this entry occupies.
         *
         * @return The start position of this entry in the given block.
         */
        @Override
        public Vec3 getStartPoint()
        {
            return startPoint;
        }

        /**
         * The end (highest on all three axi) position of the state that this entry occupies.
         *
         * @return The start position of this entry in the given block.
         */
        @Override
        public Vec3 getEndPoint()
        {
            return endPoint;
        }

        /**
         * The world, in the form of a block reader, that this entry info resides in.
         *
         * @return The world.
         */
        @Override
        public LevelAccessor getWorld()
        {
            return world;
        }

        /**
         * The position of the block that this state entry is part of.
         *
         * @return The in world block position.
         */
        @Override
        public BlockPos getBlockPos()
        {
            return blockPos;
        }

        /**
         * Sets the current entries state.
         *
         * @param blockState The new blockstate of the entry.
         */
        @Override
        public void setState(final BlockState blockState) throws SpaceOccupiedException
        {
            setCallback.accept(blockState, getStartPoint());
        }

        /**
         * Clears the current state entries blockstate. Effectively setting the current blockstate to air.
         */
        @Override
        public void clear()
        {
            clearCallback.accept(getStartPoint());
        }
    }

    private static class PreAdaptedShapeIdentifier implements ISingleStateAreaShareIdentifier
    {
        private final int blockState;

        private PreAdaptedShapeIdentifier(final BlockState blockState) {this.blockState = IBlockStateIdManager.getInstance().getIdFrom(blockState);}

        @Override
        public boolean equals(final Object o)
        {
            if (this == o)
            {
                return true;
            }
            if (!(o instanceof final PreAdaptedShapeIdentifier that))
            {
                return false;
            }
            return blockState == that.blockState;
        }

        @Override
        public int hashCode()
        {
            return Objects.hash(blockState);
        }
    }
}
