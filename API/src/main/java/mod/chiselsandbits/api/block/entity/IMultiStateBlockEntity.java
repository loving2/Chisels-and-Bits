package mod.chiselsandbits.api.block.entity;

import mod.chiselsandbits.api.block.IMultiStateBlock;
import mod.chiselsandbits.api.multistate.mutator.IGenerallyModifiableAreaMutator;
import mod.chiselsandbits.api.multistate.mutator.batched.IBatchedAreaMutator;
import mod.chiselsandbits.api.multistate.statistics.IMultiStateObjectStatistics;
import mod.chiselsandbits.api.multistate.accessor.world.IWorldAreaAccessor;
import mod.chiselsandbits.api.multistate.mutator.world.IWorldAreaMutator;
import mod.chiselsandbits.api.util.IPacketBufferSerializable;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.core.Direction;
import mod.chiselsandbits.api.util.INBTSerializable;

/**
 * Represents the block entity with the state data, which under-ly the information
 * provided by the {@link IMultiStateBlock} blocks.
 */
public interface IMultiStateBlockEntity extends IWorldAreaAccessor,
                                                          IWorldAreaMutator,
                                                          INBTSerializable<CompoundTag>,
                                                          IPacketBufferSerializable,
                                                          IBatchedAreaMutator,
                                                  IGenerallyModifiableAreaMutator
{

    /**
     * The statistics of this block.
     *
     * @return The statistics.
     */
    IMultiStateObjectStatistics getStatistics();

    /**
     * Rotates the current multistate block 90 degrees around the given axis with the given rotation count.
     *
     * @param axis The axis to rotate around.
     * @param rotationCount The amount of times to rotate the
     */
    void rotate(final Direction.Axis axis, final int rotationCount);

    /**
     * Initializes the block entity so that all its state entries
     * have the given state as their state.
     *
     * @param currentState The new initial state.
     */
    void initializeWith(BlockState currentState);
}
