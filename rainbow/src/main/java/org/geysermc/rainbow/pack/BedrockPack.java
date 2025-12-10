package org.geysermc.rainbow.pack;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.datafixers.util.Pair;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponentPatch;
import net.minecraft.core.component.DataComponents;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomModelData;
import org.geysermc.rainbow.CodecUtil;
import org.geysermc.rainbow.PackConstants;
import org.geysermc.rainbow.Rainbow;
import org.geysermc.rainbow.RainbowIO;
import org.geysermc.rainbow.mapping.AssetResolver;
import org.geysermc.rainbow.mapping.BedrockItemMapper;
import org.geysermc.rainbow.mapping.PackContext;
import org.geysermc.rainbow.mapping.PackSerializer;
import org.geysermc.rainbow.mapping.geometry.GeometryRenderer;
import org.geysermc.rainbow.definition.GeyserMappings;
import org.geysermc.rainbow.mapping.texture.TextureHolder;
import org.jetbrains.annotations.NotNull;

import java.io.ByteArrayInputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.function.UnaryOperator;

public class BedrockPack {
    private final String name;
    private final Optional<PackManifest> manifest;
    private final PackPaths paths;
    private final PackSerializer serializer;

    private final BedrockTextures.Builder itemTextures = BedrockTextures.builder();
    private final Set<BedrockItem> bedrockItems = new HashSet<>();
    private final Set<ResourceLocation> modelsMapped = new HashSet<>();
    private final Set<Pair<Item, Integer>> customModelDataMapped = new HashSet<>();

    private final PackContext context;
    private final ProblemReporter reporter;

    public BedrockPack(String name, Optional<PackManifest> manifest, PackPaths paths, PackSerializer serializer, AssetResolver assetResolver,
                       Optional<GeometryRenderer> geometryRenderer, ProblemReporter reporter,
                       boolean reportSuccesses) {
        this.name = name;
        this.manifest = manifest;
        this.paths = paths;
        this.serializer = serializer;

        // Not reading existing item mappings/texture atlas for now since that doesn't work all that well yet
        this.context = new PackContext(new GeyserMappings(), paths, item -> {
            itemTextures.withItemTexture(item);
            bedrockItems.add(item);
        }, assetResolver, geometryRenderer, reportSuccesses);
        this.reporter = reporter;
    }

    public String name() {
        return name;
    }

    public MappingResult map(ItemStack stack) {
        if (stack.isEmpty()) {
            return MappingResult.NONE_MAPPED;
        }

        AtomicBoolean problems = new AtomicBoolean();
        ProblemReporter mapReporter = new ProblemReporter() {

            @Override
            public @NotNull ProblemReporter forChild(PathElement child) {
                return reporter.forChild(child);
            }

            @Override
            public void report(Problem problem) {
                problems.set(true);
                reporter.report(problem);
            }
        };

        Optional<? extends ResourceLocation> patchedModel = stack.getComponentsPatch().get(DataComponents.ITEM_MODEL);
        //noinspection OptionalAssignedToNull - annoying Mojang
        if (patchedModel == null || patchedModel.isEmpty()) {
            CustomModelData customModelData = stack.get(DataComponents.CUSTOM_MODEL_DATA);
            Float firstNumber;
            if (customModelData == null || (firstNumber = customModelData.getFloat(0)) == null
                    || !customModelDataMapped.add(Pair.of(stack.getItem(), firstNumber.intValue()))) {
                return MappingResult.NONE_MAPPED;
            }

            BedrockItemMapper.tryMapStack(stack, firstNumber.intValue(), mapReporter, context);
        } else {
            ResourceLocation model = patchedModel.get();
            if (!modelsMapped.add(model)) {
                return MappingResult.NONE_MAPPED;
            }

            BedrockItemMapper.tryMapStack(stack, model, mapReporter, context);
        }

        return problems.get() ? MappingResult.PROBLEMS_OCCURRED : MappingResult.MAPPED_SUCCESSFULLY;
    }

    public MappingResult map(Holder<Item> item, DataComponentPatch patch) {
        ItemStack stack = new ItemStack(item);
        stack.applyComponents(patch);
        return map(stack);
    }

    public CompletableFuture<?> save() {
        List<CompletableFuture<?>> futures = new ArrayList<>();

        futures.add(serializer.saveJson(GeyserMappings.CODEC, context.mappings(), paths.mappings()));
        manifest.ifPresent(manifest -> futures.add(serializer.saveJson(PackManifest.CODEC, manifest, paths.manifest())));
        TextureSizeTracker textureSizeTracker = new TextureSizeTracker();

        Function<TextureHolder, CompletableFuture<?>> textureSaver = texture -> {
            ResourceLocation textureLocation = Rainbow.decorateTextureLocation(texture.location());
            return textureSizeTracker.saveAndTrack(texture, serializer, paths.packRoot().resolve(textureLocation.getPath()));
        };

        for (BedrockItem item : bedrockItems) {
            futures.add(item.save(serializer, paths.attachables(), paths.geometry(), paths.animation(), textureSaver));
        }

        BedrockTextureAtlas itemAtlas = BedrockTextureAtlas.itemAtlas(name, itemTextures, textureSizeTracker.textureWidth(), textureSizeTracker.textureHeight());
        futures.add(serializer.saveJson(BedrockTextureAtlas.CODEC, itemAtlas, paths.itemAtlas()));

        if (reporter instanceof AutoCloseable closeable) {
            try {
                closeable.close();
            } catch (Exception ignored) {}
        }

        CompletableFuture<?> packSerializingFinished = CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new));
        if (paths.zipOutput().isPresent()) {
            return packSerializingFinished.thenAcceptAsync(object -> RainbowIO.safeIO(() -> CodecUtil.tryZipDirectory(paths.packRoot(), paths.zipOutput().get())));
        }
        return packSerializingFinished;
    }

    public int getMappings() {
        return context.mappings().size();
    }

    public Set<BedrockItem> getBedrockItems() {
        return Set.copyOf(bedrockItems);
    }

    public int getItemTextureAtlasSize() {
        return itemTextures.build().size();
    }

    public ProblemReporter getReporter() {
        return reporter;
    }

    public static Builder builder(String name, Path mappingsPath, Path packRootPath, PackSerializer packSerializer, AssetResolver assetResolver) {
        return new Builder(name, mappingsPath, packRootPath, packSerializer, assetResolver);
    }

    private class TextureSizeTracker {
        private static final int DEFAULT_TEXTURE_SIZE = 16;

        private final AtomicInteger textureWidth = new AtomicInteger(DEFAULT_TEXTURE_SIZE);
        private final AtomicInteger textureHeight = new AtomicInteger(DEFAULT_TEXTURE_SIZE);
        private final Set<ResourceLocation> trackedTextures = ConcurrentHashMap.newKeySet();

        public int textureWidth() {
            return textureWidth.get();
        }

        public int textureHeight() {
            return textureHeight.get();
        }

        public CompletableFuture<?> saveAndTrack(TextureHolder texture, PackSerializer serializer, Path path) {
            Optional<byte[]> loadedTexture = texture.load(context.assetResolver(), reporter);
            loadedTexture.ifPresent(bytes -> trackTexture(texture, bytes));
            return loadedTexture.map(bytes -> serializer.saveTexture(bytes, path)).orElseGet(() -> CompletableFuture.completedFuture(null));
        }

        private void trackTexture(TextureHolder holder, byte[] texture) {
            if (!trackedTextures.add(holder.location())) {
                return;
            }

            RainbowIO.safeIO(() -> {
                try (NativeImage image = NativeImage.read(new ByteArrayInputStream(texture))) {
                    updateSize(image.getWidth(), image.getHeight());
                } catch (Exception exception) {
                    reporter.report(() -> "failed to read texture size for " + holder.location() + ": " + exception.getMessage());
                }
                return null;
            });
        }

        private void updateSize(int width, int height) {
            textureWidth.getAndUpdate(previous -> Math.max(previous, width));
            textureHeight.getAndUpdate(previous -> Math.max(previous, height));
        }
    }

    public static class Builder {
        private static final Path ATTACHABLES_DIRECTORY = Path.of("attachables");
        private static final Path GEOMETRY_DIRECTORY = Path.of("models/entity");
        private static final Path ANIMATION_DIRECTORY = Path.of("animations");

        private static final Path MANIFEST_FILE = Path.of("manifest.json");
        private static final Path ITEM_ATLAS_FILE = Path.of("textures/item_texture.json");

        private final String name;
        private final Path mappingsPath;
        private final Path packRootPath;
        private final PackSerializer packSerializer;
        private final AssetResolver assetResolver;
        private PackManifest manifest;
        private UnaryOperator<Path> attachablesPath = resolve(ATTACHABLES_DIRECTORY);
        private UnaryOperator<Path> geometryPath = resolve(GEOMETRY_DIRECTORY);
        private UnaryOperator<Path> animationPath = resolve(ANIMATION_DIRECTORY);
        private UnaryOperator<Path> manifestPath = resolve(MANIFEST_FILE);
        private UnaryOperator<Path> itemAtlasPath = resolve(ITEM_ATLAS_FILE);
        private Path packZipFile = null;
        private GeometryRenderer geometryRenderer = null;
        private Function<ProblemReporter.PathElement, ProblemReporter> reporter;
        private boolean reportSuccesses = false;

        public Builder(String name, Path mappingsPath, Path packRootPath, PackSerializer packSerializer, AssetResolver assetResolver) {
            this.name = name;
            this.mappingsPath = mappingsPath;
            this.packRootPath = packRootPath;
            this.reporter = ProblemReporter.Collector::new;
            this.packSerializer = packSerializer;
            this.assetResolver = assetResolver;
            manifest = defaultManifest(name);
        }

        public Builder withManifest(PackManifest manifest) {
            this.manifest = manifest;
            return this;
        }

        public Builder withAttachablesPath(Path absolute) {
            return withAttachablesPath(path -> absolute);
        }

        public Builder withAttachablesPath(UnaryOperator<Path> path) {
            attachablesPath = path;
            return this;
        }

        public Builder withGeometryPath(Path absolute) {
            return withGeometryPath(path -> absolute);
        }

        public Builder withGeometryPath(UnaryOperator<Path> path) {
            geometryPath = path;
            return this;
        }

        public Builder withAnimationPath(Path absolute) {
            return withAnimationPath(path -> absolute);
        }

        public Builder withAnimationPath(UnaryOperator<Path> path) {
            animationPath = path;
            return this;
        }

        public Builder withManifestPath(Path absolute) {
            return withManifestPath(path -> absolute);
        }

        public Builder withManifestPath(UnaryOperator<Path> path) {
            manifestPath = path;
            return this;
        }

        public Builder withItemAtlasPath(Path absolute) {
            return withItemAtlasPath(path -> absolute);
        }

        public Builder withItemAtlasPath(UnaryOperator<Path> path) {
            itemAtlasPath = path;
            return this;
        }

        public Builder withPackZipFile(Path absolute) {
            packZipFile = absolute;
            return this;
        }

        public Builder withGeometryRenderer(GeometryRenderer renderer) {
            geometryRenderer = renderer;
            return this;
        }

        public Builder withReporter(Function<ProblemReporter.PathElement, ProblemReporter> reporter) {
            this.reporter = reporter;
            return this;
        }

        public Builder reportSuccesses() {
            this.reportSuccesses = true;
            return this;
        }

        public BedrockPack build() {
            PackPaths paths = new PackPaths(mappingsPath, packRootPath, attachablesPath.apply(packRootPath),
                    geometryPath.apply(packRootPath), animationPath.apply(packRootPath), manifestPath.apply(packRootPath),
                    itemAtlasPath.apply(packRootPath), Optional.ofNullable(packZipFile));
            return new BedrockPack(name, Optional.ofNullable(manifest), paths, packSerializer, assetResolver, Optional.ofNullable(geometryRenderer),
                    reporter.apply(() -> "Bedrock pack " + name + " "), reportSuccesses);
        }

        private static UnaryOperator<Path> resolve(Path child) {
            return root -> root.resolve(child);
        }

        private static PackManifest defaultManifest(String name) {
            return PackManifest.create(name, PackConstants.DEFAULT_PACK_DESCRIPTION, UUID.randomUUID(), UUID.randomUUID(), BedrockVersion.of(0));
        }
    }

    public enum MappingResult {
        NONE_MAPPED,
        MAPPED_SUCCESSFULLY,
        PROBLEMS_OCCURRED
    }
}
