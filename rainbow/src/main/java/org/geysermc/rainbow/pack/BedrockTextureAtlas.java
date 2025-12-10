package org.geysermc.rainbow.pack;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.codecs.RecordCodecBuilder;

public record BedrockTextureAtlas(String resourcePackName, String atlasName, BedrockTextures textures, int textureWidth, int textureHeight) {
    public static final String ITEM_ATLAS = "atlas.items";
    private static final int DEFAULT_TEXTURE_SIZE = 16;
    public static final Codec<BedrockTextureAtlas> CODEC = RecordCodecBuilder.create(instance ->
            instance.group(
                    Codec.STRING.fieldOf("resource_pack_name").forGetter(BedrockTextureAtlas::resourcePackName),
                    Codec.STRING.fieldOf("texture_name").forGetter(BedrockTextureAtlas::atlasName),
                    BedrockTextures.CODEC.fieldOf("texture_data").forGetter(BedrockTextureAtlas::textures),
                    Codec.INT.optionalFieldOf("texture_width", DEFAULT_TEXTURE_SIZE).forGetter(BedrockTextureAtlas::textureWidth),
                    Codec.INT.optionalFieldOf("texture_height", DEFAULT_TEXTURE_SIZE).forGetter(BedrockTextureAtlas::textureHeight)
            ).apply(instance, BedrockTextureAtlas::new)
    );
    public static final Codec<BedrockTextureAtlas> ITEM_ATLAS_CODEC = CODEC.validate(atlas -> {
        if (!ITEM_ATLAS.equals(atlas.atlasName)) {
            return DataResult.error(() -> "Expected atlas to be " + ITEM_ATLAS + ", got " + atlas.atlasName);
        }
        return DataResult.success(atlas);
    });

    public static BedrockTextureAtlas itemAtlas(String resourcePackName, BedrockTextures.Builder textures, int textureWidth, int textureHeight) {
        return new BedrockTextureAtlas(resourcePackName, ITEM_ATLAS, textures.build(), textureWidth, textureHeight);
    }
}
