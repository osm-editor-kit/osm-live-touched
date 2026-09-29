package org.openstreetmap.josm.plugins.livetouched;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.openstreetmap.josm.plugins.livetouched.Model.Hint;
import org.openstreetmap.josm.plugins.livetouched.Model.LiveItem;
import org.openstreetmap.josm.plugins.livetouched.Model.LiveState;
import org.openstreetmap.josm.plugins.livetouched.Model.LocalObject;
import org.openstreetmap.josm.plugins.livetouched.Model.OsmType;
import org.openstreetmap.josm.plugins.livetouched.Model.Status;

/**
 * Runs the real HTTP client and two sessions against a local key-value-db (wrangler dev) whose
 * OSM_API_BASE points to a stub that accepts tokens "tok-<uid>". Skipped unless LT_KV_BASE_URL
 * and LT_KV_API_KEY are set; the local project must allow the origin https://josm.openstreetmap.de.
 */
@EnabledIfEnvironmentVariable(named = "LT_KV_BASE_URL", matches = ".+")
class LocalBackendContractTest {
    private static final double[] VIEW = {13.37, 52.51, 13.39, 52.52};
    private static final LocalObject WAY =
            new LocalObject(OsmType.WAY, 4242, 7, new double[] {13.3777, 52.5163, 13.378, 52.5165});

    private static KvApi api(long uid) {
        return new KvApi(System.getenv("LT_KV_BASE_URL"), "live-touched", System.getenv("LT_KV_API_KEY"),
                "https://josm.openstreetmap.de", "live-touched-test", "JOSM test", () -> "tok-" + uid);
    }

    private static LiveState await(AtomicReference<LiveState> ref, Predicate<LiveState> ok) throws InterruptedException {
        long end = System.currentTimeMillis() + 25_000;
        while (System.currentTimeMillis() < end) {
            LiveState s = ref.get();
            if (s != null && ok.test(s)) return s;
            Thread.sleep(200);
        }
        return ref.get();
    }

    @Test
    void twoJosmUsersSeeEachOther() throws Exception {
        KvApi aliceApi = api(401);
        KvApi bobApi = api(402);
        assertEquals(401, aliceApi.me());
        aliceApi.removeMine();
        bobApi.removeMine();

        AtomicReference<LiveState> aliceState = new AtomicReference<>();
        AtomicReference<LiveState> bobState = new AtomicReference<>();
        Session alice = new Session(aliceApi, (t, id) -> null, aliceState::set);
        Session bob = new Session(bobApi, (t, id) -> 7, bobState::set);
        try {
            CompletableFuture<String> err = new CompletableFuture<>();
            alice.enable(err::complete);
            bob.enable(err::complete);
            alice.setViewport(VIEW);
            bob.setViewport(VIEW);

            alice.setModified(List.of(WAY));
            LiveState seen = await(bobState, s -> s.items.size() == 1);
            assertTrue(!err.isDone(), () -> "enable failed: " + err.getNow(""));
            LiveItem item = seen.items.get(0);
            assertEquals(Status.TOUCHED, item.status);
            assertEquals(401, item.userId);
            assertEquals("user401", item.userName);

            bob.setModified(List.of(WAY));
            seen = await(bobState, s -> !s.items.isEmpty() && s.items.get(0).hint == Hint.PARALLEL);
            assertEquals(Hint.PARALLEL, seen.items.get(0).hint);
            assertNotNull(await(aliceState, s -> s.items.size() == 1).items.get(0));

            alice.markSaved(java.util.Map.of("w4242", 99L));
            seen = await(bobState, s -> !s.items.isEmpty() && s.items.get(0).status == Status.SAVED);
            assertEquals(Status.SAVED, seen.items.get(0).status);
            assertEquals(8, seen.items.get(0).newVersion);
            assertEquals(Hint.OUTDATED, seen.items.get(0).hint);

            CompletableFuture<Integer> deleted = new CompletableFuture<>();
            alice.nukeMyData(deleted::complete, msg -> deleted.completeExceptionally(new AssertionError(msg)));
            assertEquals(1, deleted.get(10, TimeUnit.SECONDS));
            seen = await(bobState, s -> s.items.isEmpty());
            assertTrue(seen.items.isEmpty());
        } finally {
            alice.shutdown();
            bob.shutdown();
        }
    }
}
