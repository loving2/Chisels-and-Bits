package mod.chiselsandbits.block.entities;

import com.google.common.collect.HashMultimap;
import com.google.common.collect.Maps;
import com.google.common.collect.Multimap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import mod.chiselsandbits.ChiselsAndBits;
import mod.chiselsandbits.api.block.entity.IMultiStateBlockEntity;
import mod.chiselsandbits.api.block.state.id.IBlockStateIdManager;
import mod.chiselsandbits.api.exceptions.SpaceOccupiedException;
import mod.chiselsandbits.api.multistate.accessor.IAreaShapeIdentifier;
import mod.chiselsandbits.api.multistate.accessor.IStateEntryInfo;
import mod.chiselsandbits.api.multistate.accessor.sortable.IPositionMutator;
import mod.chiselsandbits.api.multistate.mutator.IMutableStateEntryInfo;
import mod.chiselsandbits.api.multistate.mutator.batched.IBatchMutation;
import mod.chiselsandbits.api.multistate.mutator.callback.StateClearer;
import mod.chiselsandbits.api.multistate.mutator.callback.StateSetter;
import mod.chiselsandbits.api.multistate.mutator.world.IInWorldMutableStateEntryInfo;
import mod.chiselsandbits.api.multistate.snapshot.IMultiStateSnapshot;
import mod.chiselsandbits.api.multistate.statistics.IMultiStateObjectStatistics;
import mod.chiselsandbits.api.util.*;
import mod.chiselsandbits.api.util.constants.NbtConstants;
import mod.chiselsandbits.network.packets.TileEntityUpdatedPacket;
import mod.chiselsandbits.registrars.ModTileEntityTypes;
import mod.chiselsandbits.utils.ChunkSectionUtils;
import mod.chiselsandbits.utils.MultiStateSnapshotUtils;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.nbt.CompoundNBT;
import net.minecraft.nbt.ListNBT;
import net.minecraft.nbt.NBTUtil;
import net.minecraft.network.NetworkManager;
import net.minecraft.network.PacketBuffer;
import net.minecraft.network.play.server.SUpdateTileEntityPacket;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.Direction;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.vector.Vector3d;
import net.minecraft.util.math.vector.Vector3i;
import net.minecraft.world.IWorld;
import net.minecraft.world.IWorldReader;
import net.minecraft.world.chunk.ChunkSection;
import net.minecraftforge.common.util.Constants;
import net.minecraftforge.common.util.INBTSerializable;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.*;
import java.util.function.Supplier;
import java.util.stream.Stream;

@SuppressWarnings("deprecation")
public class ChiseledBlockEntity extends TileEntity implements IMultiStateBlockEntity
{
    public static final int   BITS_PER_BLOCK_SIDE = 16;
    public static final int   BITS_PER_BLOCK = BITS_PER_BLOCK_SIDE * BITS_PER_BLOCK_SIDE * BITS_PER_BLOCK_SIDE;
    public static final int   BITS_PER_LAYER = BITS_PER_BLOCK_SIDE * BITS_PER_BLOCK_SIDE;
    public static final float SIZE_PER_BIT        = 1/16f;
    public static final float SIZE_PER_HALF_BIT = 1/32f;
    public static final float ONE_THOUSANDS = 1 / 1000f;

    private ChunkSection compressedSection;
    private final MutableStatistics mutableStatistics;

    private final Map<UUID, IBatchMutation> batchMutations = Maps.newConcurrentMap();

    public ChiseledBlockEntity()
    {
        super(ModTileEntityTypes.CHISELED.get());
        compressedSection = new ChunkSection(0); //We always use a minimal y level to lookup. Makes calculations internally easier.
        mutableStatistics = new MutableStatistics(this::getWorld, this::getPos);
    }

    @Override
    public IAreaShapeIdentifier createNewShapeIdentifier()
    {
        return new Identifier(this.compressedSection);
    }

    @Override
    public Stream<IStateEntryInfo> stream()
    {
        return BlockPosStreamProvider.getForRange(BITS_PER_BLOCK_SIDE)
          .map(blockPos -> new StateEntry(
            compressedSection.getBlockState(blockPos.getX(), blockPos.getY(), blockPos.getZ()),
            getWorld(),
            getPos(),
            blockPos,
            this::setInAreaTarget,
            this::clearInAreaTarget)
      );
    }

    @Override
    public Optional<IStateEntryInfo> getInAreaTarget(final Vector3d inAreaTarget)
    {
        if (inAreaTarget.getX() < 0 ||
              inAreaTarget.getY() < 0 ||
              inAreaTarget.getZ() < 0 ||
              inAreaTarget.getX() >= 1 ||
              inAreaTarget.getY() >= 1 ||
              inAreaTarget.getZ() >= 1) {
            throw new IllegalArgumentException("Target is not in the current area.");
        }

        final BlockPos inAreaPos = new BlockPos(inAreaTarget.mul(BITS_PER_BLOCK_SIDE, BITS_PER_BLOCK_SIDE, BITS_PER_BLOCK_SIDE));

        final BlockState currentState = this.compressedSection.getBlockState(
          inAreaPos.getX(),
          inAreaPos.getY(),
          inAreaPos.getZ()
        );

        // TODO: Replace in 1.17 with isAir()
        return currentState.isAir(new SingleBlockWorldReader(currentState, getPos(), getWorld()), getPos()) ? Optional.empty() : Optional.of(new StateEntry(
          currentState,
          getWorld(),
          getPos(),
          inAreaPos,
          this::setInAreaTarget,
          this::clearInAreaTarget)
        );
    }

    @Override
    public Optional<IStateEntryInfo> getInBlockTarget(final BlockPos inAreaBlockPosOffset, final Vector3d inBlockTarget)
    {
        if (!inAreaBlockPosOffset.equals(BlockPos.ZERO))
        {
            throw new IllegalStateException(String.format("The given in area block pos offset is not inside the current block: %s", inAreaBlockPosOffset));
        }

        return this.getInAreaTarget(
          inBlockTarget
        );
    }

    /**
     * Indicates if the given target is inside of the current accessor.
     *
     * @param inAreaTarget The area target to check.
     * @return True when inside, false when not.
     */
    @Override
    public boolean isInside(final Vector3d inAreaTarget)
    {
        return !(inAreaTarget.getX() < 0) &&
                 !(inAreaTarget.getY() < 0) &&
                 !(inAreaTarget.getZ() < 0) &&
                 !(inAreaTarget.getX() >= 1) &&
                 !(inAreaTarget.getY() >= 1) &&
                 !(inAreaTarget.getZ() >= 1);
    }

    /**
     * Indicates if the given target (with the given block position offset) is inside of the current accessor.
     *
     * @param inAreaBlockPosOffset The offset of blocks in the current area.
     * @param inBlockTarget        The offset in the targeted block.
     * @return True when inside, false when not.
     */
    @Override
    public boolean isInside(final BlockPos inAreaBlockPosOffset, final Vector3d inBlockTarget)
    {
        if (!inAreaBlockPosOffset.equals(BlockPos.ZERO))
        {
            return false;
        }

        return this.isInside(
          inBlockTarget
        );
    }

    @Override
    public IMultiStateSnapshot createSnapshot()
    {
        return MultiStateSnapshotUtils.createFromSection(this.compressedSection);
    }

    @SuppressWarnings("ConstantConditions")
    @Override
    public void read(@Nullable final BlockState state, @NotNull final CompoundNBT nbt)
    {
        super.read(state, nbt);

        this.deserializeNBT(nbt);
    }

    @Override
    public void deserializeNBT(final CompoundNBT nbt)
    {
        final CompoundNBT chiselBlockData = nbt.getCompound(NbtConstants.CHISEL_BLOCK_ENTITY_DATA);
        final CompoundNBT compressedSectionData = chiselBlockData.getCompound(NbtConstants.COMPRESSED_STORAGE);
        final CompoundNBT statisticsData = chiselBlockData.getCompound(NbtConstants.STATISTICS);

        ChunkSectionUtils.deserializeNBT(
          this.compressedSection,
          compressedSectionData
        );

        mutableStatistics.deserializeNBT(statisticsData);
    }

    @NotNull
    @Override
    public CompoundNBT write(@NotNull final CompoundNBT compound)
    {
        final CompoundNBT nbt = super.write(compound);
        final CompoundNBT chiselBlockData = new CompoundNBT();
        final CompoundNBT compressedSectionData = ChunkSectionUtils.serializeNBT(this.compressedSection);
        chiselBlockData.put(NbtConstants.COMPRESSED_STORAGE, compressedSectionData);
        chiselBlockData.put(NbtConstants.STATISTICS, mutableStatistics.serializeNBT());

        nbt.put(NbtConstants.CHISEL_BLOCK_ENTITY_DATA, chiselBlockData);

        return nbt;
    }

    @Override
    public CompoundNBT serializeNBT()
    {
        final CompoundNBT nbt = super.serializeNBT();
        return write(nbt);
    }

    @Nullable
    @Override
    public SUpdateTileEntityPacket getUpdatePacket()
    {
        return new SUpdateTileEntityPacket(pos, 255, getUpdateTag());
    }

    @NotNull
    @Override
    public CompoundNBT getUpdateTag()
    {
        //Special compound version which just contains the bit array!
        final CompoundNBT updateTag = super.getUpdateTag();

        final ByteBuf buffer = Unpooled.buffer();
        final PacketBuffer innerPacketBuffer = new PacketBuffer(buffer);
        this.serializeInto(innerPacketBuffer);
        final byte[] data = buffer.array();
        buffer.release();

        updateTag.putByteArray(NbtConstants.CHISEL_BLOCK_ENTITY_DATA, data);

        return updateTag;
    }

    @Override
    public void onDataPacket(final NetworkManager net, final SUpdateTileEntityPacket pkt)
    {
        handleUpdateTag(Objects.requireNonNull(getWorld()).getBlockState(getPos()), pkt.getNbtCompound());
    }

    @Override
    public void handleUpdateTag(final BlockState state, final CompoundNBT tag)
    {
        final byte[] compressedStorageData = tag.getByteArray(NbtConstants.CHISEL_BLOCK_ENTITY_DATA);

        final ByteBuf buffer = Unpooled.wrappedBuffer(compressedStorageData);
        this.deserializeFrom(new PacketBuffer(buffer));
        buffer.release();
    }

    @Override
    public void serializeInto(@NotNull final PacketBuffer packetBuffer)
    {
        compressedSection.write(packetBuffer);
        mutableStatistics.serializeInto(packetBuffer);
    }

    @Override
    public void deserializeFrom(@NotNull final PacketBuffer packetBuffer)
    {
        compressedSection.read(packetBuffer);
        mutableStatistics.deserializeFrom(packetBuffer);
    }

    @Override
    public Vector3d getInWorldStartPoint()
    {
        return Vector3d.copy(getPos());
    }

    @Override
    public Vector3d getInWorldEndPoint()
    {
        return getInWorldStartPoint().add(1, 1, 1).subtract(ONE_THOUSANDS, ONE_THOUSANDS, ONE_THOUSANDS);
    }

    /**
     * Returns all entries in the current area in a mutable fashion. Includes all empty areas as areas containing an air state.
     *
     * @return A stream with a mutable state entry info for each mutable section in the area.
     */
    @Override
    public Stream<IMutableStateEntryInfo> mutableStream()
    {
        return BlockPosStreamProvider.getForRange(BITS_PER_BLOCK_SIDE)
                 .map(blockPos -> new StateEntry(
                   compressedSection.getBlockState(blockPos.getX(), blockPos.getY(), blockPos.getZ()),
                   getWorld(),
                   getPos(),
                   blockPos,
                   this::setInAreaTarget,
                   this::clearInAreaTarget)
                 );
    }

    @SuppressWarnings("deprecation")
    @Override
    public void setInAreaTarget(final BlockState blockState, final Vector3d inAreaTarget) throws SpaceOccupiedException
    {
        if (inAreaTarget.getX() < 0 ||
              inAreaTarget.getY() < 0 ||
              inAreaTarget.getZ() < 0 ||
              inAreaTarget.getX() >= 1 ||
              inAreaTarget.getY() >= 1 ||
              inAreaTarget.getZ() >= 1) {
            throw new IllegalArgumentException("Target is not in the current area.");
        }

        final BlockPos inAreaPos = new BlockPos(inAreaTarget.mul(BITS_PER_BLOCK_SIDE, BITS_PER_BLOCK_SIDE, BITS_PER_BLOCK_SIDE));

        final BlockState currentState = this.compressedSection.getBlockState(
          inAreaPos.getX(),
          inAreaPos.getY(),
          inAreaPos.getZ()
        );

        if (!currentState.isAir(new SingleBlockBlockReader(
          currentState,
          getPos(),
          getWorld()
        ), getPos()))
            throw new SpaceOccupiedException();

        if (getWorld() == null || getWorld().isRemote())
            return;

        this.compressedSection.setBlockState(
          inAreaPos.getX(),
          inAreaPos.getY(),
          inAreaPos.getZ(),
          blockState,
          true
        );

        if (blockState.isAir() && !currentState.isAir()) {
            mutableStatistics.onBlockStateRemoved(currentState, inAreaPos);
        } else if (!blockState.isAir() && currentState.isAir()) {
            mutableStatistics.onBlockStateAdded(blockState, inAreaPos);
        } else if (!blockState.isAir() && !currentState.isAir()) {
            mutableStatistics.onBlockStateReplaced(currentState, blockState, inAreaPos);
        }

        if (getWorld() != null)
        {
            markDirty();
        }
    }

    @Override
    public void setInBlockTarget(final BlockState blockState, final BlockPos inAreaBlockPosOffset, final Vector3d inBlockTarget) throws SpaceOccupiedException
    {
        if (!inAreaBlockPosOffset.equals(BlockPos.ZERO))
        {
            throw new IllegalStateException(String.format("The given in area block pos offset is not inside the current block: %s", inAreaBlockPosOffset));
        }

        this.setInAreaTarget(
          blockState,
          inBlockTarget
        );
    }

    /**
     * Clears the current area, using the offset from the area as well as the in area target offset.
     *
     * @param inAreaTarget The in area offset.
     */
    @Override
    public void clearInAreaTarget(final Vector3d inAreaTarget)
    {
        if (inAreaTarget.getX() < 0 ||
              inAreaTarget.getY() < 0 ||
              inAreaTarget.getZ() < 0 ||
              inAreaTarget.getX() >= 1 ||
              inAreaTarget.getY() >= 1 ||
              inAreaTarget.getZ() >= 1) {
            throw new IllegalArgumentException("Target is not in the current area.");
        }

        final BlockPos inAreaPos = new BlockPos(inAreaTarget.mul(BITS_PER_BLOCK_SIDE, BITS_PER_BLOCK_SIDE, BITS_PER_BLOCK_SIDE));

        if (getWorld() == null || getWorld().isRemote())
            return;

        final BlockState currentState = this.compressedSection.getBlockState(
          inAreaPos.getX(),
          inAreaPos.getY(),
          inAreaPos.getZ()
        );
        final BlockState blockState = Blocks.AIR.getDefaultState();

        this.compressedSection.setBlockState(
          inAreaPos.getX(),
          inAreaPos.getY(),
          inAreaPos.getZ(),
          blockState,
          true
        );

        if (blockState.isAir() && !currentState.isAir()) {
            mutableStatistics.onBlockStateRemoved(currentState, inAreaPos);
        } else if (!blockState.isAir() && currentState.isAir()) {
            mutableStatistics.onBlockStateAdded(blockState, inAreaPos);
        } else if (!blockState.isAir() && !currentState.isAir()) {
            mutableStatistics.onBlockStateReplaced(currentState, blockState, inAreaPos);
        }

        if (getWorld() != null)
        {
            markDirty();
        }
    }

    /**
     * Clears the current area, using the in area block position offset as well as the in block target offset to calculate the in area offset for setting.
     *
     * @param inAreaBlockPosOffset The offset of blocks in the current area.
     * @param inBlockTarget        The offset in the targeted block.
     */
    @Override
    public void clearInBlockTarget(final BlockPos inAreaBlockPosOffset, final Vector3d inBlockTarget)
    {
        if (!inAreaBlockPosOffset.equals(BlockPos.ZERO))
        {
            throw new IllegalStateException(String.format("The given in area block pos offset is not inside the current block: %s", inAreaBlockPosOffset));
        }

        this.clearInAreaTarget(
          inBlockTarget
        );
    }

    @Override
    public IMultiStateObjectStatistics getStatistics()
    {
        return mutableStatistics;
    }

    @Override
    public void rotate(final Direction.Axis axis, final int rotationCount)
    {
        if (getWorld() == null || getWorld().isRemote())
            return;

        this.compressedSection = ChunkSectionUtils.rotate90Degrees(
          this.compressedSection,
          axis,
          rotationCount
        );
        this.mutableStatistics.clear();

        BlockPosStreamProvider.getForRange(BITS_PER_BLOCK_SIDE)
          .forEach(position -> this.mutableStatistics.onBlockStateAdded(
            this.compressedSection.getBlockState(position.getX(), position.getY(), position.getZ()),
            position
          ));
    }

    /**
     * Initializes the block entity so that all its state entries have the given state as their state.
     *
     * @param currentState The new initial state.
     */
    @Override
    public void initializeWith(final BlockState currentState)
    {
        if (getWorld() == null || getWorld().isRemote())
            return;

        BlockPosStreamProvider.getForRange(BITS_PER_BLOCK_SIDE)
          .forEach(blockPos -> this.compressedSection.setBlockState(
            blockPos.getX(),
            blockPos.getY(),
            blockPos.getZ(),
            currentState
          ));

        this.mutableStatistics.initializeWith(currentState);
    }

    /**
     * Returns all entries in the current area in a mutable fashion. Includes all empty areas as areas containing an air state.
     *
     * @return A stream with a mutable state entry info for each mutable section in the area.
     */
    @Override
    public Stream<IInWorldMutableStateEntryInfo> inWorldMutableStream()
    {
        return BlockPosStreamProvider.getForRange(BITS_PER_BLOCK_SIDE)
                 .map(blockPos -> new StateEntry(
                   compressedSection.getBlockState(blockPos.getX(), blockPos.getY(), blockPos.getZ()),
                   getWorld(),
                   getPos(),
                   blockPos,
                   this::setInAreaTarget,
                   this::clearInAreaTarget)
                 );
    }

    @Override
    public Stream<IStateEntryInfo> streamWithPositionMutator(final IPositionMutator positionMutator)
    {
        return BlockPosStreamProvider.getForRange(BITS_PER_BLOCK_SIDE)
                 .map(positionMutator::mutate)
                 .map(blockPos -> new StateEntry(
                   compressedSection.getBlockState(blockPos.getX(), blockPos.getY(), blockPos.getZ()),
                   getWorld(),
                   getPos(),
                   blockPos,
                   this::setInAreaTarget,
                   this::clearInAreaTarget)
                 );
    }

    /**
     * For tile entities, ensures the chunk containing the tile entity is saved to disk later - the game won't think it hasn't changed and skip it.
     */
    @Override
    public void markDirty()
    {
        if (getWorld() != null && !getWorld().isRemote() && this.batchMutations.isEmpty()) {
            super.markDirty();

            getWorld().notifyBlockUpdate(getPos(), Blocks.AIR.getDefaultState(), getBlockState(), Constants.BlockFlags.DEFAULT);

            ChiselsAndBits.getInstance().getNetworkChannel().sendToTrackingChunk(
              new TileEntityUpdatedPacket(this),
              getWorld().getChunkAt(getPos())
            );
        }
    }

    @Override
    public IBatchMutation batch()
    {
        final UUID id = UUID.randomUUID();

        this.batchMutations.put(id, new BatchMutationLock(() -> {
            this.batchMutations.remove(id);

            if (this.batchMutations.isEmpty())
                markDirty();
        }));
        return this.batchMutations.get(id);
    }

    private static final class StateEntry implements IInWorldMutableStateEntryInfo {

        private final BlockState state;
        private final IWorld     reader;
        private final BlockPos   blockPos;
        private final Vector3d startPoint;
        private final Vector3d endPoint;

        private final StateSetter stateSetter;
        private final StateClearer stateClearer;

        public StateEntry(
          final BlockState state,
          final IWorld reader,
          final BlockPos blockPos,
          final Vector3i startPoint,
          final StateSetter stateSetter,
          final StateClearer stateClearer)
        {
            this(
              state,
              reader,
              blockPos,
              Vector3d.copy(startPoint).mul(SIZE_PER_BIT, SIZE_PER_BIT, SIZE_PER_BIT),
              Vector3d.copy(startPoint).mul(SIZE_PER_BIT, SIZE_PER_BIT, SIZE_PER_BIT).add(SIZE_PER_BIT, SIZE_PER_BIT, SIZE_PER_BIT),
              stateSetter, stateClearer);
        }

        private StateEntry(
          final BlockState state,
          final IWorld reader,
          final BlockPos blockPos,
          final Vector3d startPoint,
          final Vector3d endPoint,
          final StateSetter stateSetter,
          final StateClearer stateClearer) {
            this.state = state;
            this.reader = reader;
            this.blockPos = blockPos;
            this.startPoint = startPoint;
            this.endPoint = endPoint;
            this.stateSetter = stateSetter;
            this.stateClearer = stateClearer;
        }

        @Override
        public BlockState getState()
        {
            return state;
        }

        @Override
        public IWorld getWorld()
        {
            return reader;
        }

        @Override
        public BlockPos getBlockPos()
        {
            return blockPos;
        }

        @Override
        public Vector3d getStartPoint()
        {
            return startPoint;
        }

        @Override
        public Vector3d getEndPoint()
        {
            return endPoint;
        }

        /**
         * Sets the current entries state.
         *
         * @param blockState The new blockstate of the entry.
         */
        @Override
        public void setState(final BlockState blockState) throws SpaceOccupiedException
        {
            stateSetter.accept(blockState, getStartPoint());
        }

        /**
         * Clears the current state entries blockstate. Effectively setting the current blockstate to air.
         */
        @Override
        public void clear()
        {
            stateClearer.accept(getStartPoint());
        }
    }

    private static final class MutableStatistics implements IMultiStateObjectStatistics, INBTSerializable<CompoundNBT>, IPacketBufferSerializable {

        private final Supplier<IWorldReader> worldReaderSupplier;
        private final Supplier<BlockPos> positionSupplier;

        private BlockState primaryState = Blocks.AIR.getDefaultState();
        private final Map<BlockState, Integer> countMap = Maps.newConcurrentMap();

        private final Multimap<Vector2i, Integer> columnBlockedMap = HashMultimap.create();

        private int totalUsedBlockCount = 0;
        private int totalUsedChecksWeakPowerCount = 0;
        private float totalUpperSurfaceSlipperiness = 0f;
        private int totalLightLevel = 0;

        private MutableStatistics(final Supplier<IWorldReader> worldReaderSupplier, final Supplier<BlockPos> positionSupplier) {this.worldReaderSupplier = worldReaderSupplier;
            this.positionSupplier = positionSupplier;
        }

        @Override
        public BlockState getPrimaryState()
        {
            return primaryState;
        }

        @Override
        public Map<BlockState, Integer> getStateCounts()
        {
            return Collections.unmodifiableMap(countMap);
        }

        @Override
        public boolean shouldCheckWeakPower() {
            return totalUsedChecksWeakPowerCount == BITS_PER_BLOCK;
        }

        @Override
        public float getFullnessFactor() {
            return totalUsedBlockCount / (float) BITS_PER_BLOCK;
        }

        @Override
        public float getSlipperiness()
        {
            return totalUpperSurfaceSlipperiness / (float) BITS_PER_LAYER;
        }

        @Override
        public float getLightEmissionFactor()
        {
            return this.totalLightLevel / (float) this.totalUsedBlockCount;
        }

        @Override
        public float getRelativeBlockHardness(final PlayerEntity player)
        {
            return (float) (this.countMap.entrySet().stream()
                          .mapToDouble(entry -> (double) entry.getKey().getPlayerRelativeBlockHardness(
                            player,
                            new SingleBlockWorldReader(
                              entry.getKey(),
                              this.positionSupplier.get(),
                              this.worldReaderSupplier.get()
                            ),
                            this.positionSupplier.get()
                          ) * entry.getValue())
                          .sum() / this.totalUsedBlockCount);
        }

        @Override
        public boolean canPropagateSkylight()
        {
            for (int x = 0; x < BITS_PER_BLOCK_SIDE; x++)
            {
                for (int y = 0; y < BITS_PER_BLOCK_SIDE; y++)
                {
                    final Vector2i coordinate = new Vector2i(x, y);

                    if (!this.columnBlockedMap.containsKey(coordinate))
                        return true;
                }
            }

            return false;
        }

        private void clear() {
            this.primaryState = Blocks.AIR.getDefaultState();

            this.countMap.clear();
            this.columnBlockedMap.clear();

            this.totalUsedBlockCount = 0;
            this.totalUsedChecksWeakPowerCount = 0;
            this.totalUpperSurfaceSlipperiness = 0;
            this.totalLightLevel = 0;
        }

        private void onBlockStateAdded(final BlockState blockState, final BlockPos pos) {
            countMap.putIfAbsent(blockState, 0);
            countMap.computeIfPresent(blockState, (state, currentCount) -> currentCount + 1);
            updatePrimaryState();

            this.totalUsedBlockCount++;

            if (blockState.shouldCheckWeakPower(
              new SingleBlockWorldReader(
                blockState,
                this.positionSupplier.get(),
                this.worldReaderSupplier.get()
                ),
              this.positionSupplier.get(),
              Direction.NORTH
            )) {
                this.totalUsedChecksWeakPowerCount++;
            }

            if (pos.getY() == 15) {
                this.totalUpperSurfaceSlipperiness += blockState.getSlipperiness(
                  new SingleBlockWorldReader(
                    blockState,
                    this.positionSupplier.get(),
                    this.worldReaderSupplier.get()
                  ),
                  this.positionSupplier.get(),
                  null
                );
            }

            this.totalLightLevel += blockState.getLightValue(
              new SingleBlockWorldReader(
                blockState,
                this.positionSupplier.get(),
                this.worldReaderSupplier.get()
              ),
              this.positionSupplier.get()
            );

            this.columnBlockedMap.put(
              new Vector2i(pos.getX(), pos.getZ()),
              pos.getY()
            );
        }

        private void onBlockStateRemoved(final BlockState blockState, final BlockPos pos) {
            countMap.computeIfPresent(blockState, (state, currentCount) -> currentCount - 1);
            countMap.remove(blockState, 0);
            updatePrimaryState();

            this.totalUsedBlockCount--;

            if (blockState.shouldCheckWeakPower(
              new SingleBlockWorldReader(
                blockState,
                this.positionSupplier.get(),
                this.worldReaderSupplier.get()
              ),
              this.positionSupplier.get(),
              Direction.NORTH
            )) {
                this.totalUsedChecksWeakPowerCount--;
            }

            if (pos.getY() == 15) {
                this.totalUpperSurfaceSlipperiness -= blockState.getSlipperiness(
                  new SingleBlockWorldReader(
                    blockState,
                    this.positionSupplier.get(),
                    this.worldReaderSupplier.get()
                  ),
                  this.positionSupplier.get(),
                  null
                );
            }

            this.totalLightLevel -= blockState.getLightValue(
              new SingleBlockWorldReader(
                blockState,
                this.positionSupplier.get(),
                this.worldReaderSupplier.get()
              ),
              this.positionSupplier.get()
            );

            this.columnBlockedMap.remove(
              new Vector2i(pos.getX(), pos.getZ()),
              pos.getY()
            );

            this.columnBlockedMap.remove(
              new Vector2i(pos.getX(), pos.getZ()),
              pos.getY()
            );
        }

        private void onBlockStateReplaced(final BlockState currentState, final BlockState newState, final BlockPos pos) {
            countMap.computeIfPresent(currentState, (state, currentCount) -> currentCount - 1);
            countMap.remove(currentState, 0);
            countMap.putIfAbsent(newState, 0);
            countMap.computeIfPresent(newState, (state, currentCount) -> currentCount + 1);
            updatePrimaryState();

            if (currentState.shouldCheckWeakPower(
              new SingleBlockWorldReader(
                currentState,
                this.positionSupplier.get(),
                this.worldReaderSupplier.get()
              ),
              this.positionSupplier.get(),
              Direction.NORTH
            )) {
                this.totalUsedChecksWeakPowerCount--;
            }

            if (newState.shouldCheckWeakPower(
              new SingleBlockWorldReader(
                newState,
                this.positionSupplier.get(),
                this.worldReaderSupplier.get()
              ),
              this.positionSupplier.get(),
              Direction.NORTH
            )) {
                this.totalUsedChecksWeakPowerCount++;
            }

            if (pos.getY() == 15) {
                this.totalUpperSurfaceSlipperiness -= currentState.getSlipperiness(
                  new SingleBlockWorldReader(
                    currentState,
                    this.positionSupplier.get(),
                    this.worldReaderSupplier.get()
                  ),
                  this.positionSupplier.get(),
                  null
                );

                this.totalUpperSurfaceSlipperiness += newState.getSlipperiness(
                  new SingleBlockWorldReader(
                    newState,
                    this.positionSupplier.get(),
                    this.worldReaderSupplier.get()
                  ),
                  this.positionSupplier.get(),
                  null
                );
            }

            this.totalLightLevel -= currentState.getLightValue(
              new SingleBlockWorldReader(
                currentState,
                this.positionSupplier.get(),
                this.worldReaderSupplier.get()
              ),
              this.positionSupplier.get()
            );

            this.totalLightLevel += newState.getLightValue(
              new SingleBlockWorldReader(
                newState,
                this.positionSupplier.get(),
                this.worldReaderSupplier.get()
              ),
              this.positionSupplier.get()
            );
        }

        private void updatePrimaryState() {
            primaryState = this.countMap.entrySet().stream().min((o1, o2) -> -1 * (o1.getValue() - o2.getValue()))
              .map(Map.Entry::getKey)
              .orElseGet(Blocks.AIR::getDefaultState);
        }

        @Override
        public void serializeInto(@NotNull final PacketBuffer packetBuffer)
        {
            packetBuffer.writeVarInt(IBlockStateIdManager.getInstance().getIdFrom(this.primaryState));

            packetBuffer.writeVarInt(this.countMap.size());
            for (final Map.Entry<BlockState, Integer> blockStateIntegerEntry : this.countMap.entrySet())
            {
                packetBuffer.writeVarInt(IBlockStateIdManager.getInstance().getIdFrom(blockStateIntegerEntry.getKey()));
                packetBuffer.writeVarInt(blockStateIntegerEntry.getValue());
            }
            packetBuffer.writeVarInt(this.columnBlockedMap.size());
            for (final Map.Entry<Vector2i, Integer> vector2iIntegerEntry : this.columnBlockedMap.entries())
            {
                packetBuffer.writeVarInt(vector2iIntegerEntry.getKey().getX());
                packetBuffer.writeVarInt(vector2iIntegerEntry.getKey().getY());
                packetBuffer.writeVarInt(vector2iIntegerEntry.getValue());
            }

            packetBuffer.writeVarInt(this.totalUsedBlockCount);
            packetBuffer.writeVarInt(this.totalUsedChecksWeakPowerCount);
            packetBuffer.writeFloat(this.totalUpperSurfaceSlipperiness);
            packetBuffer.writeVarInt(this.totalLightLevel);
        }

        @Override
        public void deserializeFrom(@NotNull final PacketBuffer packetBuffer)
        {
            this.countMap.clear();
            this.columnBlockedMap.clear();

            this.primaryState = IBlockStateIdManager.getInstance().getBlockStateFrom(packetBuffer.readVarInt());

            final int stateCount = packetBuffer.readVarInt();
            for (int i = 0; i < stateCount; i++)
            {
                this.countMap.put(
                  IBlockStateIdManager.getInstance().getBlockStateFrom(packetBuffer.readVarInt()),
                  packetBuffer.readVarInt()
                );
            }
            final int columnBlockCount = packetBuffer.readVarInt();
            for (int i = 0; i < columnBlockCount; i++)
            {
                this.columnBlockedMap.put(
                  new Vector2i(
                    packetBuffer.readVarInt(),
                    packetBuffer.readVarInt()
                  ),
                  packetBuffer.readVarInt()
                );
            }

            this.totalUsedBlockCount = packetBuffer.readVarInt();
            this.totalUsedChecksWeakPowerCount = packetBuffer.readVarInt();
            this.totalUpperSurfaceSlipperiness = packetBuffer.readFloat();
            this.totalLightLevel = packetBuffer.readVarInt();
        }

        @Override
        public CompoundNBT serializeNBT()
        {
            final CompoundNBT nbt = new CompoundNBT();

            nbt.put(NbtConstants.PRIMARY_STATE, NBTUtil.writeBlockState(this.primaryState));

            final ListNBT blockStateList = new ListNBT();
            for (final Map.Entry<BlockState, Integer> blockStateIntegerEntry : this.countMap.entrySet())
            {
                final CompoundNBT stateNbt = new CompoundNBT();

                stateNbt.put(NbtConstants.BLOCK_STATE, NBTUtil.writeBlockState(blockStateIntegerEntry.getKey()));
                stateNbt.putInt(NbtConstants.COUNT, blockStateIntegerEntry.getValue());

                blockStateList.add(stateNbt);
            }
            final ListNBT columnBlockList = new ListNBT();
            for (final Map.Entry<Vector2i, Integer> vector2iIntegerEntry : this.columnBlockedMap.entries())
            {
                final CompoundNBT columnBlockNbt = new CompoundNBT();
                final CompoundNBT coordinateNbt = new CompoundNBT();

                coordinateNbt.putInt(NbtConstants.X_COORDINATE, vector2iIntegerEntry.getKey().getX());
                coordinateNbt.putInt(NbtConstants.Y_COORDINATE, vector2iIntegerEntry.getKey().getY());

                columnBlockNbt.put(NbtConstants.COORDINATE, coordinateNbt);
                columnBlockNbt.putInt(NbtConstants.VALUE, vector2iIntegerEntry.getValue());

                columnBlockList.add(columnBlockNbt);
            }

            nbt.put(NbtConstants.BLOCK_STATES, blockStateList);
            nbt.put(NbtConstants.COLUMN_BLOCK_LIST, columnBlockList);

            nbt.putInt(NbtConstants.TOTAL_BLOCK_COUNT, totalUsedBlockCount);
            nbt.putInt(NbtConstants.TOTAL_SHOULD_CHECK_WEAK_POWER_COUNT, totalUsedChecksWeakPowerCount);
            nbt.putFloat(NbtConstants.TOTAL_UPPER_LEVEL_SLIPPERINESS, totalUpperSurfaceSlipperiness);
            nbt.putInt(NbtConstants.TOTAL_LIGHT_LEVEL, totalLightLevel);

            return nbt;
        }

        @Override
        public void deserializeNBT(final CompoundNBT nbt)
        {
            this.countMap.clear();

            this.primaryState = NBTUtil.readBlockState(nbt.getCompound(NbtConstants.PRIMARY_STATE));

            final ListNBT blockStateList = nbt.getList(NbtConstants.BLOCK_STATES, Constants.NBT.TAG_COMPOUND);
            for (int i = 0; i < blockStateList.size(); i++)
            {
                final CompoundNBT stateNbt = blockStateList.getCompound(i);

                this.countMap.put(
                  NBTUtil.readBlockState(stateNbt.getCompound(NbtConstants.BLOCK_STATE)),
                  stateNbt.getInt(NbtConstants.COUNT)
                );
            }

            final ListNBT columnBlockList = nbt.getList(NbtConstants.COLUMN_BLOCK_LIST, Constants.NBT.TAG_COMPOUND);
            for (int i = 0; i < columnBlockList.size(); i++)
            {
                final CompoundNBT columnBlockNbt = columnBlockList.getCompound(i);
                final CompoundNBT coordinateNbt = columnBlockNbt.getCompound(NbtConstants.COORDINATE);

                this.columnBlockedMap.put(
                  new Vector2i(
                    coordinateNbt.getInt(NbtConstants.X_COORDINATE),
                    coordinateNbt.getInt(NbtConstants.Y_COORDINATE)
                  ),
                  columnBlockNbt.getInt(NbtConstants.VALUE)
                );
            }

            this.totalUsedBlockCount = nbt.getInt(NbtConstants.TOTAL_BLOCK_COUNT);
            this.totalUsedChecksWeakPowerCount = nbt.getInt(NbtConstants.TOTAL_SHOULD_CHECK_WEAK_POWER_COUNT);
            this.totalUpperSurfaceSlipperiness = nbt.getFloat(NbtConstants.TOTAL_UPPER_LEVEL_SLIPPERINESS);
            this.totalLightLevel = nbt.getInt(NbtConstants.TOTAL_LIGHT_LEVEL);
        }

        public void initializeWith(final BlockState blockState) {
            clear();
            this.primaryState = blockState;
            this.countMap.put(blockState, BITS_PER_BLOCK);

            this.totalUsedBlockCount = BITS_PER_BLOCK;

            if (blockState.shouldCheckWeakPower(
              new SingleBlockWorldReader(
                blockState,
                this.positionSupplier.get(),
                this.worldReaderSupplier.get()
              ),
              this.positionSupplier.get(),
              Direction.NORTH
            )) {
                this.totalUsedChecksWeakPowerCount = BITS_PER_BLOCK;
            }

            this.totalLightLevel += (blockState.getLightValue(
              new SingleBlockWorldReader(
                blockState,
                this.positionSupplier.get(),
                this.worldReaderSupplier.get()
              ),
              this.positionSupplier.get()
            ) * BITS_PER_BLOCK);

            this.totalUpperSurfaceSlipperiness += (blockState.getSlipperiness(
              new SingleBlockWorldReader(
                blockState,
                this.positionSupplier.get(),
                this.worldReaderSupplier.get()
              ),
              this.positionSupplier.get(),
              null
            ) * BITS_PER_LAYER);

            BlockPosStreamProvider.getForRange(BITS_PER_BLOCK_SIDE)
              .forEach(pos -> columnBlockedMap.put(new Vector2i(pos.getX(), pos.getZ()), pos.getY()));
        }
    }

    private static final class Identifier implements IAreaShapeIdentifier {

        private final long[] identifyingPayload;

        private Identifier(final ChunkSection section) {
            this.identifyingPayload = Arrays.copyOf(
              section.getData().storage.getBackingLongArray(),
              section.getData().storage.getBackingLongArray().length
            );
        }

        @Override
        public boolean equals(final Object o)
        {
            if (this == o)
            {
                return true;
            }
            if (!(o instanceof Identifier))
            {
                return false;
            }
            final Identifier that = (Identifier) o;
            return Arrays.equals(identifyingPayload, that.identifyingPayload);
        }

        @Override
        public int hashCode()
        {
            return Arrays.hashCode(identifyingPayload);
        }
    }

    private static final class BatchMutationLock implements IBatchMutation {

        private final Runnable closeCallback;

        private BatchMutationLock(final Runnable closeCallback) {this.closeCallback = closeCallback;}

        @Override
        public void close()
        {
            this.closeCallback.run();
        }
    }
}