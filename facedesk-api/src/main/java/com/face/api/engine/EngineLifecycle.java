package com.face.api.engine;

import java.time.Instant;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.*;
import java.util.function.Supplier;

/** 暂停阻止新任务，重载等待正在执行的整笔识别完成；旧排队任务不跨代执行。 */
public final class EngineLifecycle {
    public enum State { RUNNING, PAUSING, PAUSED, RELOADING, FAILED }
    private final FaceEngine engine;
    private final String expectedSpace;
    private final long timeoutMs;
    // 识别持读锁，暂停或重载持写锁：整笔识别及底库写入完成后才能切换状态。
    private final ReadWriteLock gate = new ReentrantReadWriteLock(true);
    private final ReentrantLock control = new ReentrantLock();
    private final AtomicLong generation = new AtomicLong();
    private final Instant startedAt = Instant.now();
    private volatile State state = State.RUNNING;
    private volatile String lastError;
    private volatile Instant changedAt = startedAt;

    public EngineLifecycle(FaceEngine engine, long timeoutMs) {
        this.engine = engine; this.timeoutMs = timeoutMs; this.expectedSpace = engine.featureSpaceId();
    }
    // 排队前取代次号；任务真正执行时再次核验，防止旧任务跨越暂停/重载后继续运行。
    public long ticket() {
        if (state != State.RUNNING) throw new FaceBusyException("引擎已暂停或正在重载，请先恢复引擎");
        return generation.get();
    }
    public <T> T execute(long ticket, Supplier<T> action) {
        gate.readLock().lock();
        try {
            if (state != State.RUNNING || generation.get() != ticket)
                throw new FaceBusyException("引擎状态已变化，请在引擎恢复后重新提交");
            return action.get();
        } finally { gate.readLock().unlock(); }
    }
    public void pause() { transition("pause"); }
    public void resume() { transition("resume"); }
    public void reload() { transition("reload"); }
    private void transition(String operation) {
        if (!control.tryLock()) throw new FaceBusyException("已有引擎控制操作正在执行，请稍后重试");
        State previous = state;
        boolean acquired = false;
        try {
            if (operation.equals("pause") && state == State.PAUSED) return;
            if (operation.equals("resume") && state == State.RUNNING) return;
            state = operation.equals("pause") ? State.PAUSING : State.RELOADING;
            // 先使旧票据失效，再等待在途任务，排队任务无需被逐个从线程池移除。
            generation.incrementAndGet(); changedAt = Instant.now();
            acquired = gate.writeLock().tryLock(timeoutMs, TimeUnit.MILLISECONDS);
            if (!acquired) {
                state = previous;
                throw new FaceBusyException("正在执行的任务尚未结束，本次控制操作超时，请稍后重试");
            }
            if (operation.equals("pause")) {
                state = State.PAUSED;
            } else if (operation.equals("resume") && engine.isAvailable()) {
                state = State.RUNNING;
            } else {
                engine.close();
                engine.initialize();
                // 热重载不能迁移底库，模型指纹变化时停止服务；原图迁移只在启动流程执行。
                if (!java.util.Objects.equals(expectedSpace, engine.featureSpaceId())) {
                    engine.close();
                    throw new FaceEngineException("模型内容发生变化，与当前底库不匹配。请恢复原模型，或迁移原图到新底库后重启服务");
                }
                state = operation.equals("reload") && previous == State.PAUSED ? State.PAUSED : State.RUNNING;
            }
            lastError = null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt(); state = previous;
            lastError = "引擎控制已中断"; throw new FaceBusyException(lastError);
        } catch (RuntimeException e) {
            if (acquired) state = State.FAILED;
            lastError = e.getMessage(); throw e;
        } finally {
            changedAt = Instant.now();
            if (acquired) gate.writeLock().unlock();
            control.unlock();
        }
    }
    public State getState() { return state; }
    public boolean isAvailable() { return state == State.RUNNING && engine.isAvailable(); }
    public String getLastError() { return lastError; }
    public Instant getStartedAt() { return startedAt; }
    public Instant getChangedAt() { return changedAt; }
    public String getFeatureSpaceId() { return expectedSpace; }
}
