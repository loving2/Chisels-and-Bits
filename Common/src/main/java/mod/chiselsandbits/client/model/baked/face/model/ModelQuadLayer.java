package mod.chiselsandbits.client.model.baked.face.model;

import mod.chiselsandbits.api.block.state.id.IBlockStateIdManager;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;

public class ModelQuadLayer
{

    private float[]            uvs;
    private TextureAtlasSprite sprite;
    private int light;
    private int color;
    private int tint;

    public float[] getUvs()
    {
        return uvs;
    }

    public void setUvs(final float[] uvs)
    {
        this.uvs = uvs;
    }

    public TextureAtlasSprite getSprite()
    {
        return sprite;
    }

    public void setSprite(final TextureAtlasSprite sprite)
    {
        this.sprite = sprite;
    }

    public int getLight()
    {
        return light;
    }

    public void setLight(final int light)
    {
        this.light = light;
    }

    public int getColor()
    {
        return color;
    }

    public void setColor(final int color)
    {
        this.color = color;
    }

    public int getTint()
    {
        return tint;
    }

    public void setTint(final int tint)
    {
        this.tint = tint;
    }

    public static class ModelQuadLayerBuilder
    {
        private final ModelQuadLayer cache = new ModelQuadLayer();
        private final ModelLightMapReader lightValueExtractor;
        private ModelUVReader uvExtractor;

        public ModelQuadLayerBuilder(
          final TextureAtlasSprite sprite,
          final int uCoord,
          final int vCoord )
        {
            getCache().sprite = sprite;
            lightValueExtractor = new ModelLightMapReader();
            setUvExtractor(new ModelUVReader( sprite, uCoord, vCoord ));
        }

        public ModelQuadLayer build(
          final BlockState state,
          final int color,
          final int lightValue )
        {
            getCache().light = Math.max( lightValue, getLightValueExtractor().getLv() );
            getCache().uvs = getUvExtractor().getQuadUVs();
            getCache().color = getCache().tint != -1 ? color : 0xffffffff;

            if ( 0x00 <= getCache().tint && getCache().tint <= 0xff )
            {
                getCache().color = 0xffffffff;
                getCache().tint = ( IBlockStateIdManager.getInstance().getIdFrom(state) << 8 ) | getCache().tint;
            }
            else
            {
                getCache().tint = -1;
            }

            return getCache();
        }

        public ModelQuadLayer getCache() {
            return cache;
        }

        public ModelLightMapReader getLightValueExtractor() {
            return lightValueExtractor;
        }

        public ModelUVReader getUvExtractor() {
            return uvExtractor;
        }

        public void setUvExtractor(ModelUVReader uvExtractor) {
            this.uvExtractor = uvExtractor;
        }
    };

}