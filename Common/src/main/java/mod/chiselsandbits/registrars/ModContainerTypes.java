package mod.chiselsandbits.registrars;

import mod.chiselsandbits.inventory.scanner.ScannerMenu;
import mod.chiselsandbits.platforms.core.util.constants.Constants;
import mod.chiselsandbits.container.BagContainer;
import mod.chiselsandbits.container.ChiseledPrinterContainer;
import mod.chiselsandbits.container.ModificationTableContainer;
import mod.chiselsandbits.platforms.core.registries.deferred.IRegistrar;
import mod.chiselsandbits.platforms.core.registries.deferred.IRegistryObject;
import net.minecraft.world.inventory.MenuType;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public final class ModContainerTypes
{
    private static final Logger LOGGER = LogManager.getLogger();

    private static final IRegistrar<MenuType<?>> CONTAINER_TYPE_REGISTRAR = IRegistrar.create(MenuType.class, Constants.MOD_ID);

    private ModContainerTypes()
    {
        throw new IllegalStateException("Can not instantiate an instance of: ModContainers. This is a utility class");
    }

    public static final IRegistryObject<MenuType<BagContainer>> BIT_BAG = CONTAINER_TYPE_REGISTRAR.register(
      "bag",
      () -> new MenuType<>(BagContainer::new)
    );

    public static final IRegistryObject<MenuType<ModificationTableContainer>> MODIFICATION_TABLE = CONTAINER_TYPE_REGISTRAR.register(
      "modification_table",
      () -> new MenuType<>(ModificationTableContainer::new)
    );

    public static final IRegistryObject<MenuType<ChiseledPrinterContainer>> CHISELED_PRINTER_CONTAINER = CONTAINER_TYPE_REGISTRAR.register(
      "chiseled_printer",
      () -> new MenuType<>(ChiseledPrinterContainer::new)
    );

    public static final IRegistryObject<MenuType<ScannerMenu>> PATTERN_SCANNER_CONTAINER = CONTAINER_TYPE_REGISTRAR.register(
      "pattern_scanner",
      () -> new MenuType<>(ScannerMenu::new)
    );

    public static void onModConstruction()
    {
        LOGGER.info("Loaded container type configuration.");
    }

}
