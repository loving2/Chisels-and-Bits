package mod.chiselsandbits.neighborhood;

import mod.chiselsandbits.api.multistate.accessor.IAreaAccessor;
import mod.chiselsandbits.api.multistate.accessor.identifier.IAreaShapeIdentifier;
import net.minecraft.world.level.block.state.BlockState;

import java.util.Objects;

public final class BlockNeighborhoodEntry
{
    private final BlockState    blockState;
    private final IAreaAccessor accessor;
    private final IAreaShapeIdentifier identifier;

    public BlockNeighborhoodEntry(final BlockState blockState, final IAreaAccessor accessor)
    {
        this.blockState = blockState;
        this.accessor = accessor;
        this.identifier = this.accessor.createNewShapeIdentifier();
    }

    public BlockNeighborhoodEntry(final BlockState blockState)
    {
        this.blockState = blockState;
        this.accessor = null;
        this.identifier = IAreaShapeIdentifier.DUMMY;
    }

    @Override
    public int hashCode()
    {
        int result = blockState != null ? blockState.hashCode() : 0;
        result = 31 * result + (identifier != null ? identifier.hashCode() : 0);
        return result;
    }

    @Override
    public boolean equals(final Object o)
    {
        if (this == o)
        {
            return true;
        }
        if (!(o instanceof final BlockNeighborhoodEntry that))
        {
            return false;
        }

        if (!Objects.equals(blockState, that.blockState))
        {
            return false;
        }
        return Objects.equals(identifier, that.identifier);
    }

    public BlockState getBlockState()
    {
        return blockState;
    }

    public IAreaAccessor getAccessor()
    {
        return accessor;
    }

    public IAreaShapeIdentifier getIdentifier()
    {
        return identifier;
    }
}
