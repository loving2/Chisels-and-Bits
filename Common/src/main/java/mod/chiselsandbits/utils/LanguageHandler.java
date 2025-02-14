package mod.chiselsandbits.utils;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import mod.chiselsandbits.platforms.core.dist.Dist;
import mod.chiselsandbits.platforms.core.dist.DistExecutor;
import mod.chiselsandbits.platforms.core.util.constants.Constants;
import net.minecraft.client.Minecraft;
import net.minecraft.locale.Language;
import net.minecraft.network.chat.TranslatableComponent;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Helper class for localization and sending player messages.
 */
public final class LanguageHandler
{
    private static final Logger LOGGER = LogManager.getLogger();

    /**
     * Private constructor to hide implicit one.
     */
    private LanguageHandler()
    {
        // Intentionally left empty.
    }

    /**
     * Localize a string and use String.format().
     *
     * @param inputKey translation key.
     * @param args     Objects for String.format().
     * @return Localized string.
     */
    public static String format(final String inputKey, final Object... args)
    {
        final String key = inputKey.toLowerCase(Locale.US);
        final String result;
        if (args.length == 0)
        {
            result = new TranslatableComponent(key).getContents();
        }
        else
        {
            result = new TranslatableComponent(key, args).getContents();
        }
        return result.isEmpty() ? key : result;
    }

    /**
     * Translates key to readable string.
     *
     * @param key translation key
     * @return readable string
     */
    public static String translateKey(final String key)
    {
        return LanguageCache.getInstance().translateKey(key.toLowerCase(Locale.US));
    }

    public static void loadLangPath(final String path)
    {
        LanguageCache.getInstance().load(path);
    }

    private static class LanguageCache
    {
        private static LanguageCache       instance;
        private        Map<String, String> languageMap;

        private LanguageCache()
        {
            final String fileLoc = "assets/" + Constants.MOD_ID + "/lang/%s.json";
            load(fileLoc);
        }

        private void load(final String path)
        {
            final String defaultLocale = "en_us";

            //noinspection ConstantConditions Trust me, Minecraft.getInstance() can be null, when you run Data Generators!
            String locale = DistExecutor.unsafeCallWhenOn(Dist.CLIENT, () -> () -> (Minecraft.getInstance() == null || Minecraft.getInstance().options == null) ? defaultLocale : Minecraft.getInstance().options.languageCode);

            if (locale == null)
            {
                locale = defaultLocale;
            }

            InputStream is = Thread.currentThread().getContextClassLoader().getResourceAsStream(String.format(path, locale));
            if (is == null)
            {
                is = Thread.currentThread().getContextClassLoader().getResourceAsStream(String.format(path, defaultLocale));
            }

            try
            {
                languageMap = new Gson().fromJson(new InputStreamReader(Objects.requireNonNull(is), StandardCharsets.UTF_8), new TypeToken<Map<String, String>>()
                {}.getType());
                is.close();
            }
            catch (IOException | NullPointerException e)
            {
                LOGGER.error("Could not load language.", e);
            }
        }

        private static LanguageCache getInstance()
        {
            return instance == null ? instance = new LanguageCache() : instance;
        }

        private String translateKey(final String key)
        {
            boolean isMCloaded = false;
            if (isMCloaded)
            {
                return Language.getInstance().getOrDefault(key);
            }
            else
            {
                final String res = languageMap.get(key);
                return res == null ? key : res;
            }
        }
    }
}
