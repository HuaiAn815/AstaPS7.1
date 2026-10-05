package emu.grasscutter.database;

import static org.junit.jupiter.api.Assertions.*;

import com.mongodb.MongoClientSettings;
import com.mongodb.MongoCommandException;
import com.mongodb.ServerAddress;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import dev.morphia.Datastore;
import dev.morphia.annotations.Index;
import dev.morphia.annotations.Indexed;
import dev.morphia.annotations.Indexes;
import dev.morphia.utils.IndexDirection;
import dev.morphia.utils.IndexType;
import emu.grasscutter.game.Account;
import emu.grasscutter.game.achievement.Achievements;
import emu.grasscutter.game.activity.PlayerActivityData;
import emu.grasscutter.game.gacha.GachaRecord;
import emu.grasscutter.game.world.SceneGroupInstance;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.bson.BsonDocument;
import org.bson.Document;
import org.bson.conversions.Bson;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Checks the startup contract using local proxies, without creating a MongoDB client. */
class DatabaseCapacityIndexTest {
    @Test
    void tokenIndexIsSparseAndNonUnique() throws ReflectiveOperationException {
        assertSingleFieldIndex(Account.class, "token", "capacity_accounts_token_v1", true);
    }

    @Test
    void sessionKeyIndexIsSparseAndNonUnique() throws ReflectiveOperationException {
        assertSingleFieldIndex(
                Account.class, "sessionKey", "capacity_accounts_session_key_v1", true);
    }

    @Test
    void reservedPlayerIndexAllowsUnassignedAndLegacyDuplicateValues()
            throws ReflectiveOperationException {
        assertSingleFieldIndex(
                Account.class,
                "reservedPlayerId",
                "capacity_accounts_reserved_player_id_v1",
                false);
    }

    @Test
    void achievementIndexMatchesUidLookup() throws ReflectiveOperationException {
        assertSingleFieldIndex(Achievements.class, "uid", "capacity_achievements_uid_v1", false);
    }

    @Test
    void activityIndexUsesPlayerThenActivity() {
        assertCompoundIndex(
                PlayerActivityData.class,
                "capacity_activities_uid_activity_id_v1",
                List.of("uid", "activityId"),
                List.of(IndexType.ASC, IndexType.ASC));
    }

    @Test
    void groupIndexUsesOwnerThenGroup() {
        assertCompoundIndex(
                SceneGroupInstance.class,
                "capacity_group_instances_owner_group_v1",
                List.of("ownerUid", "groupId"),
                List.of(IndexType.ASC, IndexType.ASC));
    }

    @Test
    void gachaIndexSupportsEqualityFiltersAndDescendingDate() {
        assertCompoundIndex(
                GachaRecord.class,
                "capacity_gachas_owner_type_date_v1",
                List.of("ownerId", "gachaType", "transactionDate"),
                List.of(IndexType.ASC, IndexType.ASC, IndexType.DESC));
    }

    @Test
    void entityIndexesAreEnsuredExactlyOnceWithoutEnumeratingCollections() {
        var calls = new AtomicInteger();
        var datastore =
                proxy(
                        Datastore.class,
                        (ignored, method, args) -> {
                            assertEquals("ensureIndexes", method.getName());
                            assertNull(args);
                            calls.incrementAndGet();
                            return null;
                        });

        DatabaseManager.ensureIndexes(datastore);

        assertEquals(1, calls.get());
    }

    @ParameterizedTest
    @ValueSource(ints = {85, 86, 13})
    void entityIndexErrorsPreserveExistingIndexesAndFailClearly(int code) {
        var conflict = mongoIndexError(code);
        var calls = new AtomicInteger();
        var datastore =
                proxy(
                        Datastore.class,
                        (ignored, method, args) -> {
                            // Any getDatabase/list/drop/retry call fails this assertion.
                            assertEquals("ensureIndexes", method.getName());
                            assertEquals(1, calls.incrementAndGet());
                            throw conflict;
                        });

        var failure =
                assertThrows(
                        IllegalStateException.class,
                        () -> DatabaseManager.ensureIndexes(datastore));

        assertSame(conflict, failure.getCause());
        assertTrue(failure.getMessage().contains("existing indexes were preserved"));
        assertEquals(1, calls.get());
    }

    @Test
    void rawSpawnIndexIsNamedNonUniqueAndSupportsQueryPrefixes() {
        var creates = new AtomicInteger();
        var collection =
                proxy(
                        MongoCollection.class,
                        (ignored, method, args) -> {
                            assertEquals("createIndex", method.getName());
                            assertEquals(2, args.length);
                            BsonDocument keys =
                                    ((Bson) args[0])
                                            .toBsonDocument(
                                                    Document.class,
                                                    MongoClientSettings.getDefaultCodecRegistry());
                            assertEquals(
                                    List.of("ownerUid", "sceneId", "groupId", "configId"),
                                    new ArrayList<>(keys.keySet()));
                            keys.values().forEach(value -> assertEquals(1, value.asInt32().getValue()));
                            var options = (com.mongodb.client.model.IndexOptions) args[1];
                            assertEquals("capacity_open_world_spawns_identity_v1", options.getName());
                            assertFalse(options.isUnique());
                            assertFalse(options.isSparse());
                            creates.incrementAndGet();
                            return options.getName();
                        });

        DatabaseManager.ensureOpenWorldSpawnIndexes(spawnDatabase(collection));

        assertEquals(1, creates.get());
    }

    @ParameterizedTest
    @ValueSource(ints = {85, 86, 13})
    void rawSpawnIndexErrorsNeverDropOrRecreateExistingIndexes(int code) {
        var conflict = mongoIndexError(code);
        var calls = new AtomicInteger();
        var collection =
                proxy(
                        MongoCollection.class,
                        (ignored, method, args) -> {
                            assertEquals("createIndex", method.getName());
                            assertEquals(1, calls.incrementAndGet());
                            throw conflict;
                        });

        var failure =
                assertThrows(
                        IllegalStateException.class,
                        () -> DatabaseManager.ensureOpenWorldSpawnIndexes(spawnDatabase(collection)));

        assertSame(conflict, failure.getCause());
        assertTrue(failure.getMessage().contains("open_world_spawns"));
        assertTrue(failure.getMessage().contains("existing indexes were preserved"));
        assertEquals(1, calls.get());
    }

    private static void assertSingleFieldIndex(
            Class<?> type, String field, String name, boolean sparse)
            throws ReflectiveOperationException {
        var index = type.getDeclaredField(field).getAnnotation(Indexed.class);
        assertNotNull(index);
        assertEquals(IndexDirection.ASC, index.value());
        assertEquals(name, index.options().name());
        assertEquals(sparse, index.options().sparse());
        assertFalse(index.options().unique());
    }

    private static void assertCompoundIndex(
            Class<?> type, String name, List<String> fields, List<IndexType> directions) {
        var indexes = type.getAnnotation(Indexes.class);
        assertNotNull(indexes);
        assertEquals(1, indexes.value().length);
        Index index = indexes.value()[0];
        assertEquals(name, index.options().name());
        assertFalse(index.options().unique());
        assertFalse(index.options().sparse());
        assertEquals(fields.size(), index.fields().length);
        for (int i = 0; i < fields.size(); i++) {
            assertEquals(fields.get(i), index.fields()[i].value());
            assertEquals(directions.get(i), index.fields()[i].type());
        }
    }

    private static MongoDatabase spawnDatabase(MongoCollection<?> collection) {
        return proxy(
                MongoDatabase.class,
                (ignored, method, args) -> {
                    assertEquals("getCollection", method.getName());
                    assertEquals("open_world_spawns", args[0]);
                    return collection;
                });
    }

    private static MongoCommandException mongoIndexError(int code) {
        return new MongoCommandException(
                new BsonDocument()
                        .append("ok", new org.bson.BsonInt32(0))
                        .append("code", new org.bson.BsonInt32(code))
                        .append("errmsg", new org.bson.BsonString("index creation failed")),
                new ServerAddress("127.0.0.1", 27017));
    }

    private static <T> T proxy(Class<T> type, InvocationHandler handler) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type}, handler));
    }
}
