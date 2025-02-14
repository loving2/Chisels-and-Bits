package mod.chiselsandbits.block.entities.storage;

import com.google.common.collect.Maps;
import com.google.common.math.LongMath;
import com.mojang.datafixers.util.Pair;
import mod.chiselsandbits.api.block.storage.IStateEntryStorage;
import mod.chiselsandbits.api.config.IServerConfiguration;
import mod.chiselsandbits.api.multistate.StateEntrySize;
import mod.chiselsandbits.api.util.BlockPosStreamProvider;
import mod.chiselsandbits.api.util.VectorUtils;
import mod.chiselsandbits.platforms.core.util.constants.NbtConstants;
import mod.chiselsandbits.utils.ByteArrayUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.NotNull;

import java.math.RoundingMode;
import java.util.Arrays;
import java.util.Map;
import java.util.function.BiConsumer;

public class SimpleStateEntryStorage implements IStateEntryStorage
{

    private final int size;
    private final SimpleStateEntryPalette palette;

    private byte[] data = new byte[0];
    private int entryWidth = 0;
    private boolean isDeserializing = false;

    public SimpleStateEntryStorage()
    {
        this(IServerConfiguration.getInstance().getBitSize().get().getBitsPerBlockSide());
    }

    private SimpleStateEntryStorage(final SimpleStateEntryStorage stateEntryStorage) {
        this.size = stateEntryStorage.size;
        this.palette = new SimpleStateEntryPalette(this::onPaletteResize, stateEntryStorage.palette);
        this.data = stateEntryStorage.getRawData(); // Automatically copies the data.
        this.entryWidth = stateEntryStorage.entryWidth;
    }

    public SimpleStateEntryStorage(final int size) {
        this.size = size;
        this.palette = new SimpleStateEntryPalette(this::onPaletteResize);
    }

    @Override
    public int getSize()
    {
        return size;
    }

    private int getTotalEntryCount() {
        return size * size * size;
    }

    @Override
    public void clear()
    {
        this.data = new byte[0];
        this.entryWidth = 0;
        this.palette.clear();
    }

    private void resetDataArray() {
        this.data = new byte[data.length];
    }

    @Override
    public void initializeWith(final BlockState currentState)
    {
        clear();
        if (currentState == Blocks.AIR.defaultBlockState())
        {
            return;
        }

        final int blockStateId = palette.getIndex(currentState);
        this.data = ByteArrayUtils.fill(blockStateId, entryWidth, getTotalEntryCount());
    }

    @Override
    public void loadFromChunkSection(final LevelChunkSection chunkSection)
    {
        if (this.size != StateEntrySize.ONE_SIXTEENTH.getBitsPerBlockSide())
            throw new IllegalStateException("Updating to the new storage format is only possible on the default 1/16th size.");

        this.clear();

        BlockPosStreamProvider.getForRange(StateEntrySize.ONE_SIXTEENTH.getBitsPerBlockSide())
          .forEach(position -> setBlockState(
            position.getX(),
            position.getY(),
            position.getZ(),
            chunkSection.getBlockState(position.getX(), position.getY(), position.getZ())
          ));
    }

    @Override
    public BlockState getBlockState(final int x, final int y, final int z)
    {
        final int offSetIndex = doCalculatePositionIndex(x, y, z);
        final int blockStateId = ByteArrayUtils.getValueAt(data, entryWidth, offSetIndex);

        return palette.getBlockState(blockStateId);
    }

    @Override
    public void setBlockState(final int x, final int y, final int z, final BlockState blockState)
    {
        final int offSetIndex = doCalculatePositionIndex(x, y, z);
        final int blockStateId = palette.getIndex(blockState);

        ensureCapacity();

        ByteArrayUtils.setValueAt(data, blockStateId, entryWidth, offSetIndex);
    }

    private void ensureCapacity() {
        final int requiredSize = (int) Math.ceil((getTotalEntryCount() * entryWidth) / (float) Byte.SIZE);
        if (data.length < requiredSize) {
            final byte[] rawData = getRawData();
            final byte[] newData = new byte[requiredSize];
            System.arraycopy(rawData, 0, newData, 0, rawData.length);
            this.data = newData;
        }
    }

    private int doCalculatePositionIndex(final int x, final int y, final int z)
    {
        return x * size * size + y * size + z;
    }

    @Override
    public void count(final BiConsumer<BlockState, Integer> storageConsumer)
    {
        final Map<BlockState, Integer> countMap = Maps.newHashMap();

        BlockPosStreamProvider.getForRange(this.getSize())
          .map(position -> getBlockState(position.getX(), position.getY(), position.getZ()))
          .forEach(blockState -> countMap.compute(blockState, (state, count) -> count == null ? 1 : count + 1));

        countMap.forEach(storageConsumer);
    }

    @Override
    public byte[] getRawData()
    {
        return Arrays.copyOf(this.data, data.length);
    }

    @Override
    public IStateEntryStorage createSnapshot()
    {
        return new SimpleStateEntryStorage(this);
    }

    @Override
    public void fillFromBottom(final BlockState state, final int entries)
    {
        clear();
        final int loopCount = Math.max(0, Math.min(entries, StateEntrySize.current().getBitsPerBlock()));
        if (loopCount == 0)
            return;

        int count = 0;
        for (int y = 0; y < StateEntrySize.current().getBitsPerBlockSide(); y++)
        {
            for (int x = 0; x < StateEntrySize.current().getBitsPerBlockSide(); x++)
            {
                for (int z = 0; z < StateEntrySize.current().getBitsPerBlockSide(); z++)
                {
                    setBlockState(
                      x, y, z,
                      state
                    );

                    count++;
                    if (count == loopCount)
                        return;
                }
            }
        }
    }

    @Override
    public void rotate(final Direction.Axis axis, final int rotationCount)
    {
        if (rotationCount == 0)
            return;

        final IStateEntryStorage clone = this.createSnapshot();
        resetDataArray();

        final Vec3 centerVector = new Vec3(7.5d, 7.5d, 7.5d);

        for (int x = 0; x < 16; x++)
        {
            for (int y = 0; y < 16; y++)
            {
                for (int z = 0; z < 16; z++)
                {
                    final Vec3 workingVector = new Vec3(x, y, z);
                    Vec3 rotatedVector = workingVector.subtract(centerVector);
                    for (int i = 0; i < rotationCount; i++)
                    {
                        rotatedVector = VectorUtils.rotate90Degrees(rotatedVector, axis);
                    }

                    final BlockPos sourcePos = new BlockPos(workingVector);
                    final Vec3 offsetPos = rotatedVector.add(centerVector).multiply(1000,1000,1000);
                    final BlockPos targetPos = new BlockPos(new Vec3(Math.round(offsetPos.x()), Math.round(offsetPos.y()), Math.round(offsetPos.z())).multiply(1/1000d,1/1000d,1/1000d));

                    this.setBlockState(
                      targetPos.getX(),
                      targetPos.getY(),
                      targetPos.getZ(),
                      clone.getBlockState(
                        sourcePos.getX(),
                        sourcePos.getY(),
                        sourcePos.getZ()
                      )
                    );
                }
            }
        }
    }

    @Override
    public void mirror(final Direction.Axis axis)
    {
        final IStateEntryStorage clone = this.createSnapshot();
        resetDataArray();

        for (int y = 0; y < StateEntrySize.current().getBitsPerBlockSide(); y++)
        {
            for (int x = 0; x < StateEntrySize.current().getBitsPerBlockSide(); x++)
            {
                for (int z = 0; z < StateEntrySize.current().getBitsPerBlockSide(); z++)
                {
                    final BlockState blockState = clone.getBlockState(x, y, z);

                    final int mirroredX = axis == Direction.Axis.X ? (StateEntrySize.current().getBitsPerBlockSide() - x - 1) : x;
                    final int mirroredY = axis == Direction.Axis.Y ? (StateEntrySize.current().getBitsPerBlockSide() - y - 1) : y;
                    final int mirroredZ = axis == Direction.Axis.Z ? (StateEntrySize.current().getBitsPerBlockSide() - z - 1) : z;

                    this.setBlockState(
                      mirroredX, mirroredY, mirroredZ,
                      blockState
                    );
                }
            }
        }
    }

    @Override
    public CompoundTag serializeNBT()
    {
        final CompoundTag result = new CompoundTag();

        result.put(NbtConstants.PALETTE, this.palette.serializeNBT());
        result.putByteArray(NbtConstants.DATA, this.getRawData());

        return result;
    }

    @Override
    public void deserializeNBT(final CompoundTag nbt)
    {
        clear();

        this.isDeserializing = true;

        this.palette.deserializeNBT(nbt.getList(NbtConstants.PALETTE, Tag.TAG_STRING));
        this.data = nbt.getByteArray(NbtConstants.DATA);

        this.isDeserializing = false;
    }

    @Override
    public void serializeInto(final @NotNull FriendlyByteBuf packetBuffer)
    {
        this.palette.serializeInto(packetBuffer);
        packetBuffer.writeByteArray(this.data);
    }

    @Override
    public void deserializeFrom(final @NotNull FriendlyByteBuf packetBuffer)
    {
        clear();

        this.isDeserializing = true;

        this.palette.deserializeFrom(packetBuffer);
        this.data = packetBuffer.readByteArray();

        this.isDeserializing = false;
    }

    private void onPaletteResize(final int newSize) {
        final int currentEntryWidth = this.entryWidth;
        this.entryWidth = LongMath.log2(newSize, RoundingMode.CEILING);

        if (!this.isDeserializing && this.entryWidth != currentEntryWidth) {
            //We need to update the data array to match the new palette size
            final byte[] rawData = getRawData();

            final int requiredSize = (int) Math.ceil((getTotalEntryCount() * entryWidth) / (float) Byte.SIZE);
            this.data = new byte[requiredSize];
            BlockPosStreamProvider.getForRange(getSize())
              .mapToInt(pos -> doCalculatePositionIndex(pos.getX(), pos.getY(), pos.getZ()))
              .mapToObj(index -> Pair.of(index, ByteArrayUtils.getValueAt(rawData, currentEntryWidth, index)))
              .forEach(pair -> ByteArrayUtils.setValueAt(this.data, pair.getSecond(), this.entryWidth, pair.getFirst()));
        }
    }
}
