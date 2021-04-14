package mod.chiselsandbits.item;

import mod.chiselsandbits.api.chiseling.IChiselMode;
import mod.chiselsandbits.api.chiseling.IChiselingContext;
import mod.chiselsandbits.api.chiseling.IChiselingManager;
import mod.chiselsandbits.api.item.chisel.IChiselingItem;
import mod.chiselsandbits.api.item.click.ClickProcessingState;
import mod.chiselsandbits.api.util.constants.NbtConstants;
import mod.chiselsandbits.registrars.ModBlocks;
import mod.chiselsandbits.utils.TranslationUtils;
import net.minecraft.client.util.ITooltipFlag;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.IItemTier;
import net.minecraft.item.ItemStack;
import net.minecraft.item.ToolItem;
import net.minecraft.nbt.CompoundNBT;
import net.minecraft.util.Direction;
import net.minecraft.util.Hand;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.text.ITextComponent;
import net.minecraft.world.World;
import net.minecraftforge.fml.RegistryObject;
import net.minecraftforge.registries.ForgeRegistry;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

public class ChiselItem extends ToolItem implements IChiselingItem
{

    private static final Logger LOGGER = LogManager.getLogger();

    public ChiselItem(
      final IItemTier tier,
      final Properties builderIn)
    {
        super(
          0.1F,
          -2.8F,
          tier,
          ModBlocks.MATERIAL_TO_BLOCK_CONVERSIONS.values().stream().map(RegistryObject::get).collect(Collectors.toSet()),
          builderIn
        );
    }

    @Override
    public void addInformation(
      @NotNull final ItemStack stack, @Nullable final World worldIn, final List<ITextComponent> tooltip, @NotNull final ITooltipFlag flagIn)
    {
        tooltip.add(TranslationUtils.build("chiselmode.mode", getMode(stack).getDisplayName()));

        super.addInformation(stack, worldIn, tooltip, flagIn);
    }

    @NotNull
    @Override
    public IChiselMode getMode(final ItemStack stack)
    {
        final CompoundNBT stackNbt = stack.getOrCreateTag();
        if (stackNbt.contains(NbtConstants.CHISEL_MODE))
        {
            final String chiselModeName = stackNbt.getString(NbtConstants.CHISEL_MODE);
            try {
                final IChiselMode registryMode = IChiselMode.getRegistry().getValue(new ResourceLocation(chiselModeName));
                if (registryMode == null)
                {
                    return IChiselMode.getDefaultMode();
                }

                return registryMode;
            }
            catch (IllegalArgumentException illegalArgumentException) {
                LOGGER.error(String.format("An ItemStack got loaded with a name that is not a valid chisel mode: %s", chiselModeName));
                this.setMode(stack, IChiselMode.getDefaultMode());
            }
        }

        return IChiselMode.getDefaultMode();
    }

    @Override
    public void setMode(final ItemStack stack, final IChiselMode mode)
    {
        stack.getOrCreateTag().putString(NbtConstants.CHISEL_MODE, Objects.requireNonNull(mode.getRegistryName()).toString());
    }

    @NotNull
    @Override
    public Collection<IChiselMode> getPossibleModes()
    {
        return IChiselMode.getRegistry().getValues().stream().sorted(Comparator.comparing(((ForgeRegistry<IChiselMode>) IChiselMode.getRegistry())::getID)).collect(Collectors.toList());
    }

    @Override
    public ClickProcessingState handleLeftClickProcessing(
      final PlayerEntity playerEntity,
      final Hand hand,
      final BlockPos position,
      final Direction face,
      final ClickProcessingState currentState
    ) {
        final ItemStack itemStack = playerEntity.getHeldItem(hand);
        if (itemStack.isEmpty() || itemStack.getItem() != this)
            return currentState;

        final IChiselingItem chiselingItem = (IChiselingItem) itemStack.getItem();
        final IChiselMode chiselMode = chiselingItem.getMode(itemStack);

        final IChiselingContext context = IChiselingManager.getInstance().getOrCreateContext(
          playerEntity,
          chiselMode
        );

        return chiselMode.onLeftClickBy(
          playerEntity,
          context
        );
    }
}