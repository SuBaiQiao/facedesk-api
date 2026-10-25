package com.face.api;

import com.face.api.engine.*;
import org.junit.jupiter.api.Test;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class EngineLifecycleTest {
    FaceEngine engine() {
        FaceEngine engine = mock(FaceEngine.class);
        when(engine.featureSpaceId()).thenReturn("model-v1"); when(engine.isAvailable()).thenReturn(true);
        return engine;
    }
    @Test void pauseWaitsForCurrentTaskAndInvalidatesPreviouslyQueuedTasks() throws Exception {
        FaceEngine engine = engine(); EngineLifecycle lifecycle = new EngineLifecycle(engine, 2000);
        ExecutorService threads = Executors.newFixedThreadPool(2);
        CountDownLatch entered = new CountDownLatch(1), finish = new CountDownLatch(1);
        long oldTicket = lifecycle.ticket();
        try {
            Future<String> work = threads.submit(() -> lifecycle.execute(oldTicket, () -> {
                entered.countDown(); try { finish.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                return "finished";
            }));
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            Future<?> paused = threads.submit(lifecycle::pause);
            assertTimeoutPreemptively(java.time.Duration.ofSeconds(2), () -> {
                while (lifecycle.getState() == EngineLifecycle.State.RUNNING) Thread.sleep(5);
            });
            assertEquals(EngineLifecycle.State.PAUSING, lifecycle.getState()); assertFalse(paused.isDone());
            assertThrows(FaceBusyException.class, lifecycle::ticket);
            finish.countDown(); assertEquals("finished", work.get(2, TimeUnit.SECONDS)); paused.get(2, TimeUnit.SECONDS);
            assertEquals(EngineLifecycle.State.PAUSED, lifecycle.getState()); assertFalse(lifecycle.isAvailable());
            verify(engine, never()).close(); lifecycle.resume(); assertTrue(lifecycle.isAvailable());
            assertThrows(FaceBusyException.class, () -> lifecycle.execute(oldTicket, () -> "stale"));
            assertEquals("new", lifecycle.execute(lifecycle.ticket(), () -> "new"));
        } finally { finish.countDown(); threads.shutdownNow(); }
    }
    @Test void reloadPreservesPausedStateAndRejectsDifferentModelSpace() {
        FaceEngine engine = engine(); EngineLifecycle lifecycle = new EngineLifecycle(engine, 1000);
        lifecycle.pause(); lifecycle.reload();
        verify(engine).close(); verify(engine).initialize(); assertEquals(EngineLifecycle.State.PAUSED, lifecycle.getState());
        lifecycle.resume(); when(engine.featureSpaceId()).thenReturn("wrong-model");
        assertThrows(FaceEngineException.class, lifecycle::reload);
        assertEquals(EngineLifecycle.State.FAILED, lifecycle.getState()); assertFalse(lifecycle.isAvailable());
        assertTrue(lifecycle.getLastError().contains("底库不匹配"));
    }
    @Test void loadFailureStaysUnavailableAndCanRecover() {
        FaceEngine engine = engine(); EngineLifecycle lifecycle = new EngineLifecycle(engine, 1000);
        doThrow(new FaceEngineException("模型缺失")).when(engine).initialize();
        assertThrows(FaceEngineException.class, lifecycle::reload); assertEquals(EngineLifecycle.State.FAILED, lifecycle.getState());
        when(engine.isAvailable()).thenReturn(false); doNothing().when(engine).initialize();
        lifecycle.resume(); assertEquals(EngineLifecycle.State.RUNNING, lifecycle.getState()); assertNull(lifecycle.getLastError());
        verify(engine, times(2)).initialize();
    }
}
