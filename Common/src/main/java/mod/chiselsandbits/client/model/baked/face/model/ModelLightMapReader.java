package mod.chiselsandbits.client.model.baked.face.model;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.blaze3d.vertex.VertexFormatElement;
import org.jetbrains.annotations.NotNull;

public class ModelLightMapReader extends BaseModelReader
{
    private       int          lv          = 0;
    private VertexFormat format      = DefaultVertexFormat.BLOCK;
    private boolean      hasLightMap = false;

    public ModelLightMapReader()
    {
    }

    public int getLv()
    {
        return lv;
    }

    public void setVertexFormat(
      VertexFormat format )
    {
        hasLightMap = false;

        int eCount = format.getVertexSize();
        for ( int x = 0; x < eCount; x++ )
        {
            VertexFormatElement e = format.getElements().get(x);
            if ( e.getUsage() == VertexFormatElement.Usage.UV && e.getIndex() == 1 && e.getType() == VertexFormatElement.Type.SHORT )
            {
                hasLightMap = true;
            }
        }

        this.format = format;
    }

    @NotNull
    @Override
    public VertexFormat getVertexFormat()
    {
        return format;
    }

    @Override
    public void put(
      final int element,
      @NotNull final float... data )
    {
        final VertexFormatElement e = getVertexFormat().getElements().get(element);

        if ( e.getUsage() == VertexFormatElement.Usage.UV && e.getIndex() == 1 && e.getType() == VertexFormatElement.Type.SHORT && data.length >= 2 && hasLightMap )
        {
            final float maxLightmap = 32.0f / 0xffff;
            final int lvFromData_sky = (int) ( data[0] / maxLightmap) & 0xf;
            final int lvFromData_block = (int) ( data[1] / maxLightmap) & 0xf;

            lv = Math.max( lvFromData_sky, lv );
            lv = Math.max( lvFromData_block, lv );
        }
    }

}