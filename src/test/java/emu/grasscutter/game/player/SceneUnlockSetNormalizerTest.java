package emu.grasscutter.game.player;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mongodb.client.MongoClients;

import dev.morphia.Morphia;
import dev.morphia.annotations.Entity;
import dev.morphia.annotations.Id;
import dev.morphia.mapping.MapperOptions;

import emu.grasscutter.utils.CompactIntSet;

import org.bson.BsonDocument;
import org.bson.BsonDocumentReader;
import org.bson.BsonDocumentWriter;
import org.bson.BsonValue;
import org.bson.codecs.Codec;
import org.bson.codecs.DecoderContext;
import org.bson.codecs.EncoderContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class SceneUnlockSetNormalizerTest {
    @Entity(value = "compact_set_codec_test", useDiscriminator = false)
    public static class Probe {
        @Id public String id = "codec-only";
        public Map<Integer, Set<Integer>> unlockedSceneAreas = new HashMap<>();

        public Probe() {}
    }

    @Test
    @DisplayName("null scene maps and null scene entries remain unchanged")
    public void nullMapsAndEntries() {
        SceneUnlockSetNormalizer.compact((Map<Integer, Set<Integer>>) null);
        var scenes = new HashMap<Integer, Set<Integer>>();
        scenes.put(3, null);
        scenes.put(200, new HashSet<>());
        SceneUnlockSetNormalizer.compact(scenes);
        assertEquals(Set.of(3, 200), scenes.keySet());
        assertNull(scenes.get(3));
        assertInstanceOf(CompactIntSet.class, scenes.get(200));
        assertTrue(scenes.get(200).isEmpty());
    }

    @Test
    @DisplayName("normalization preserves IDs and does not copy already compact sets")
    public void normalizationIsIdempotent() {
        var original = new HashSet<>(Arrays.asList(null, -1, 0, 4095, 4096, Integer.MAX_VALUE));
        var alreadyCompact = new CompactIntSet(List.of(1, 2, 3));
        var scenes = new HashMap<Integer, Set<Integer>>();
        scenes.put(3, original);
        scenes.put(200, alreadyCompact);
        SceneUnlockSetNormalizer.compact(scenes);
        var first = scenes.get(3);
        assertInstanceOf(CompactIntSet.class, first);
        assertEquals(original, first);
        assertSame(alreadyCompact, scenes.get(200));

        SceneUnlockSetNormalizer.compact(scenes);
        assertSame(first, scenes.get(3));
        assertSame(alreadyCompact, scenes.get(200));
        assertEquals(original, first);
        original.clear();
        assertEquals(6, first.size(), "normalization must not share mutable backing storage");
    }

    @Test
    @DisplayName(
            "Morphia BSON round trips preserve array layout and all unlock IDs without database"
                + " writes")
    public void morphiaCodecRoundTrip() {
        // Datastore creation supplies the real codec registry; only in-memory codecs are invoked.
        try (var client = MongoClients.create("mongodb://127.0.0.1:27017")) {
            var options = MapperOptions.builder().storeEmpties(true).storeNulls(false).build();
            var datastore = Morphia.createDatastore(client, "compact_set_codec_test", options);
            datastore.getMapper().map(Probe.class);
            Codec<Probe> codec = datastore.getMapper().getCodecRegistry().get(Probe.class);
            var old = new Probe();
            old.unlockedSceneAreas.put(
                    3,
                    new HashSet<>(
                            Arrays.asList(
                                    null,
                                    -1,
                                    0,
                                    1,
                                    999,
                                    1000,
                                    1363,
                                    4095,
                                    4096,
                                    65536,
                                    Integer.MAX_VALUE)));
            old.unlockedSceneAreas.put(200, new HashSet<>(List.of(1, 2, 3)));
            old.unlockedSceneAreas.put(201, new HashSet<>());
            BsonDocument baseline = encode(codec, old);
            Probe loaded = decode(codec, baseline);
            SceneUnlockSetNormalizer.compact(loaded.unlockedSceneAreas);
            assertEquals(old.unlockedSceneAreas, loaded.unlockedSceneAreas);
            assertInstanceOf(CompactIntSet.class, loaded.unlockedSceneAreas.get(3));
            BsonDocument compact = encode(codec, loaded);
            assertEquals(baseline.keySet(), compact.keySet());
            BsonDocument field = compact.getDocument("unlockedSceneAreas");
            assertTrue(field.getArray("201").isEmpty(), "empty scene sets must remain BSON arrays");
            assertEquals(baseline.getDocument("unlockedSceneAreas").keySet(), field.keySet());
            for (BsonValue value : field.values()) {
                assertTrue(value.isArray(), "unlock sets must remain BSON arrays");
                for (BsonValue id : value.asArray()) {
                    assertTrue(
                            id.isInt32() || id.isNull(),
                            "unlock IDs must remain BSON int32 or null");
                }
            }
            Probe reloaded = decode(codec, compact);
            assertEquals(old.unlockedSceneAreas, reloaded.unlockedSceneAreas);
            SceneUnlockSetNormalizer.compact(reloaded.unlockedSceneAreas);
            assertEquals(loaded.unlockedSceneAreas, reloaded.unlockedSceneAreas);
            assertInstanceOf(CompactIntSet.class, reloaded.unlockedSceneAreas.get(3));
        }
    }

    private static Probe decode(Codec<Probe> codec, BsonDocument document) {
        return codec.decode(new BsonDocumentReader(document), DecoderContext.builder().build());
    }

    private static BsonDocument encode(Codec<Probe> codec, Probe probe) {
        BsonDocument document = new BsonDocument();
        codec.encode(new BsonDocumentWriter(document), probe, EncoderContext.builder().build());
        return document;
    }
}
