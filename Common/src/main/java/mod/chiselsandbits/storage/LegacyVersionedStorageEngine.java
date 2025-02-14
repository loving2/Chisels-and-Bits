package mod.chiselsandbits.storage;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import org.jetbrains.annotations.NotNull;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;

@Deprecated(since = "This is only related to legacy storage, and will be removed in a future version.", forRemoval = true)
final class LegacyVersionedStorageEngine implements IStorageEngine
{
    private final List<ILegacyStorageHandler> handlers;

    LegacyVersionedStorageEngine(final LinkedList<ILegacyStorageHandler> handlers) {
        this.handlers = Collections.unmodifiableList(handlers);
    }

    @Override
    public CompoundTag serializeNBT()
    {
        throw new IllegalStateException("Legacy storage can not write to NBT");
    }

    @Override
    public void serializeNBTInto(final CompoundTag tag)
    {
        throw new IllegalStateException("Legacy storage can not write to NBT");
    }

    @Override
    public void deserializeNBT(final CompoundTag nbt)
    {
        handlers.stream()
          .filter(handler -> handler.matches(nbt))
          .findFirst()
          .ifPresent(handler -> handler.deserializeNBT(nbt));
    }

    @Override
    public void serializeInto(final @NotNull FriendlyByteBuf packetBuffer)
    {
        throw new IllegalStateException("Legacy storage can not write to the network");
    }

    @Override
    public void deserializeFrom(final @NotNull FriendlyByteBuf packetBuffer)
    {
        throw new IllegalStateException("Legacy storage can not read from the network");
    }

    @Override
    public Collection<ILegacyStorageHandler> getHandlers()
    {
        return handlers;
    }
}
