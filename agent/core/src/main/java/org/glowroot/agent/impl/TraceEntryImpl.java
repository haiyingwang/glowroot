/*
 * Copyright 2011-2016 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.glowroot.agent.impl;

import java.util.concurrent.TimeUnit;

import javax.annotation.Nullable;

import com.google.common.base.Strings;
import com.google.common.base.Ticker;
import com.google.common.collect.ImmutableList;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.glowroot.agent.model.AsyncTimerImpl;
import org.glowroot.agent.model.DetailMapWriter;
import org.glowroot.agent.model.ErrorMessage;
import org.glowroot.agent.model.QueryData;
import org.glowroot.agent.model.QueryEntryBase;
import org.glowroot.agent.plugin.api.AsyncQueryEntry;
import org.glowroot.agent.plugin.api.MessageSupplier;
import org.glowroot.agent.plugin.api.ThreadContext;
import org.glowroot.agent.plugin.api.Timer;
import org.glowroot.agent.plugin.api.internal.NopTransactionService.NopTimer;
import org.glowroot.agent.plugin.api.internal.ReadableMessage;
import org.glowroot.agent.util.Tickers;
import org.glowroot.wire.api.model.Proto;
import org.glowroot.wire.api.model.TraceOuterClass.Trace;

import static com.google.common.base.Preconditions.checkNotNull;

// this supports updating by a single thread and reading by multiple threads
class TraceEntryImpl extends QueryEntryBase implements AsyncQueryEntry, Timer {

    private static final Logger logger = LoggerFactory.getLogger(TraceEntryImpl.class);
    private static final Ticker ticker = Tickers.getTicker();

    private final ThreadContextImpl threadContext;
    private final @Nullable TraceEntryImpl parentTraceEntry;
    private final @Nullable MessageSupplier messageSupplier;

    // volatile so it can be set from another thread (needed for async trace entries)
    private volatile @Nullable ErrorMessage errorMessage;

    private final long startTick;

    // these fields are not volatile, so depends on memory barrier in Transaction for visibility
    private long revisedStartTick;
    private int selfNestingLevel;
    private long endTick;
    private boolean initialComplete;

    // this is for maintaining linear list of trace entries
    private @Nullable TraceEntryImpl nextTraceEntry;

    // only null for trace entries added using addEntryEntry()
    private final @Nullable TimerImpl syncTimer;
    private final @Nullable AsyncTimerImpl asyncTimer;
    // not volatile, so depends on memory barrier in Transaction for visibility
    private @Nullable ImmutableList<StackTraceElement> stackTrace;

    // only used by transaction thread
    private long stackTraceThreshold;
    // only used by transaction thread
    private @MonotonicNonNull TimerImpl extendedTimer;

    TraceEntryImpl(ThreadContextImpl threadContext,
            @Nullable TraceEntryImpl parentTraceEntry, @Nullable MessageSupplier messageSupplier,
            @Nullable QueryData queryData, long queryExecutionCount, long startTick,
            @Nullable TimerImpl syncTimer, @Nullable AsyncTimerImpl asyncTimer) {
        super(queryData);
        this.threadContext = threadContext;
        this.parentTraceEntry = parentTraceEntry;
        this.messageSupplier = messageSupplier;
        this.startTick = startTick;
        this.syncTimer = syncTimer;
        this.asyncTimer = asyncTimer;
        revisedStartTick = startTick;
        selfNestingLevel = 1;
        if (queryData != null) {
            queryData.start(startTick, queryExecutionCount);
        }
    }

    @Override
    public @Nullable MessageSupplier getMessageSupplier() {
        return messageSupplier;
    }

    @Nullable
    ErrorMessage getErrorMessage() {
        return errorMessage;
    }

    Trace.Entry toProto(int depth, long transactionStartTick, long captureTick) {
        long offsetNanos = startTick - transactionStartTick;
        long durationNanos;
        boolean active;
        if (isCompleted() && Tickers.lessThanOrEqual(endTick, captureTick)) {
            // total time is calculated relative to revised start tick
            durationNanos = endTick - revisedStartTick;
            active = false;
        } else {
            // total time is calculated relative to revised start tick
            // max with zero to prevent negative value which is possible here under race condition
            durationNanos = Math.max(captureTick - revisedStartTick, 0);
            active = true;
        }
        MessageSupplier messageSupplier = getMessageSupplier();
        ReadableMessage message =
                messageSupplier == null ? null : (ReadableMessage) messageSupplier.get();

        Trace.Entry.Builder builder = Trace.Entry.newBuilder()
                .setDepth(depth)
                .setStartOffsetNanos(offsetNanos)
                .setDurationNanos(durationNanos)
                .setActive(active);

        // async root entry always has empty message and empty detail
        if (message == null) {
            builder.setMessage("");
        } else {
            String rowCountSuffix = getRowCountSuffix();
            if (rowCountSuffix.isEmpty()) {
                // optimization to avoid creating new string when concatenating empty string
                // since message text is often long sql
                builder.setMessage(message.getText());
            } else {
                builder.setMessage(message.getText() + rowCountSuffix);
            }
        }
        if (message != null) {
            builder.addAllDetailEntry(DetailMapWriter.toProto(message.getDetail()));
        }
        ErrorMessage errorMessage = this.errorMessage;
        if (errorMessage != null) {
            Trace.Error.Builder errorBuilder = builder.getErrorBuilder();
            errorBuilder.setMessage(errorMessage.message());
            Proto.Throwable throwable = errorMessage.throwable();
            if (throwable != null) {
                errorBuilder.setException(throwable);
            }
            errorBuilder.build();
        }
        if (stackTrace != null) {
            for (StackTraceElement stackTraceElement : stackTrace) {
                builder.addLocationStackTraceElementBuilder()
                        .setClassName(stackTraceElement.getClassName())
                        .setMethodName(Strings.nullToEmpty(stackTraceElement.getMethodName()))
                        .setFileName(Strings.nullToEmpty(stackTraceElement.getFileName()))
                        .setLineNumber(stackTraceElement.getLineNumber())
                        .build();
            }
        }
        return builder.build();
    }

    long getStartTick() {
        return startTick;
    }

    @Override
    public void end() {
        if (initialComplete) {
            // this guards against end*() being called multiple times on async trace entries
            return;
        }
        long endTick = ticker.read();
        endInternal(endTick, null);
    }

    @Override
    public void endWithStackTrace(long threshold, TimeUnit unit) {
        if (threshold < 0) {
            logger.error("endWithStackTrace(): argument 'threshold' must be non-negative");
            end();
            return;
        }
        if (initialComplete) {
            // this guards against end*() being called multiple times on async trace entries
            return;
        }
        if (isAsync()) {
            // it is not helpful to capture stack trace at end of async trace entry since it is
            // ended by a different thread (and by not capturing, it reduces thread safety needs)
            endInternal(endTick, null);
            return;
        }
        long endTick = ticker.read();
        long thresholdNanos = unit.toNanos(threshold);
        if (endTick - startTick >= thresholdNanos) {
            StackTraceElement[] stackTrace = Thread.currentThread().getStackTrace();
            // need to strip back a few stack calls:
            // skip i=0 which is "java.lang.Thread.getStackTrace()"
            // skip i=1 which is "...TraceEntry.endWithStackTrace()"
            // skip i=2 which is the plugin advice
            this.stackTrace = ImmutableList.copyOf(stackTrace).subList(3, stackTrace.length);
        } else {
            // store threshold in case this trace entry is extended, see extend() below
            stackTraceThreshold = thresholdNanos;
        }
        endInternal(endTick, null);
    }

    @Override
    public void endWithError(Throwable t) {
        if (initialComplete) {
            // this guards against end*() being called multiple times on async trace entries
            return;
        }
        endWithErrorInternal(null, t);
    }

    @Override
    public void endWithError(@Nullable String message) {
        if (initialComplete) {
            // this guards against end*() being called multiple times on async trace entries
            return;
        }
        endWithErrorInternal(message, null);
    }

    @Override
    public void endWithError(@Nullable String message, Throwable t) {
        if (initialComplete) {
            // this guards against end*() being called multiple times on async trace entries
            return;
        }
        endWithErrorInternal(message, t);
    }

    @Override
    public void endWithInfo(Throwable t) {
        if (initialComplete) {
            // this guards against end*() being called multiple times on async trace entries
            return;
        }
        endWithErrorInternal(null, t);
    }

    // for async trace entries, extend must be called by the same thread that started the async
    // trace entry
    @Override
    public Timer extend() {
        // timer is only null for trace entries added using addEntryEntry(), and these trace entries
        // are not returned from plugin api so no way for extend() to be called when timer is null
        checkNotNull(syncTimer);
        if (selfNestingLevel++ == 0) {
            long priorDurationNanos = endTick - revisedStartTick;
            long currTick = ticker.read();
            revisedStartTick = currTick - priorDurationNanos;
            extendedTimer = syncTimer.extend(currTick);
            extendQueryData(currTick);
        }
        return this;
    }

    // this is called for stopping an extension
    @Override
    public void stop() {
        // the timer interface for this class is only expose through return value of extend()
        if (--selfNestingLevel == 0) {
            endTick = ticker.read();
            checkNotNull(extendedTimer);
            extendedTimer.end(endTick);
            endQueryData(endTick);
            // it is not helpful to capture stack trace at end of async trace entry since it is
            // ended by a different thread (and by not capturing, it reduces thread safety needs)
            if (!isAsync() && stackTrace == null && stackTraceThreshold != 0
                    && endTick - revisedStartTick >= stackTraceThreshold) {
                StackTraceElement[] stackTrace = Thread.currentThread().getStackTrace();
                // need to strip back a few stack calls:
                // skip i=0 which is "java.lang.Thread.getStackTrace()"
                // skip i=1 which is "...Timer.stop()"
                // skip i=2 which is the plugin advice
                this.stackTrace = ImmutableList.copyOf(stackTrace).subList(3, stackTrace.length);
            }
        }
    }

    void setStackTrace(ImmutableList<StackTraceElement> stackTrace) {
        this.stackTrace = stackTrace;
    }

    ThreadContextImpl getThreadContext() {
        return threadContext;
    }

    @Nullable
    TraceEntryImpl getParentTraceEntry() {
        return parentTraceEntry;
    }

    @Nullable
    TraceEntryImpl getNextTraceEntry() {
        return nextTraceEntry;
    }

    void setNextTraceEntry(TraceEntryImpl nextTraceEntry) {
        this.nextTraceEntry = nextTraceEntry;
    }

    void immediateEndAsErrorEntry(ErrorMessage errorMessage, long endTick) {
        this.errorMessage = errorMessage;
        this.endTick = endTick;
        selfNestingLevel--;
        initialComplete = true;
    }

    boolean isAuxThreadRoot() {
        // TODO this is a little hacky depending on timer name
        return syncTimer != null && syncTimer.getName().equals("auxiliary thread");
    }

    private boolean isCompleted() {
        // initialComplete is needed for async trace entries which have selfNestingLevel = 0 after
        // calling stopSyncTimer(), but are not complete until end() is called
        return initialComplete && selfNestingLevel == 0;
    }

    private boolean isAsync() {
        return asyncTimer != null;
    }

    private void endWithErrorInternal(@Nullable String message, @Nullable Throwable t) {
        ErrorMessage errorMessage = ErrorMessage.from(message, t,
                threadContext.getTransaction().getThrowableFrameLimitCounter());
        endInternal(ticker.read(), errorMessage);
        // it is not helpful to capture stack trace at end of async trace entry since it is
        // ended by a different thread (and by not capturing, it reduces thread safety needs)
        if (!isAsync() && t == null) {
            StackTraceElement[] stackTrace = Thread.currentThread().getStackTrace();
            // need to strip back a few stack calls:
            // skip i=0 which is "java.lang.Thread.getStackTrace()"
            // skip i=1 which is "...TraceEntryImpl.endWithErrorInternal()"
            // skip i=2 which is "...TraceEntryImpl.endWithError()"
            // skip i=3 which is the plugin advice
            setStackTrace(ImmutableList.copyOf(stackTrace).subList(4, stackTrace.length));
        }
    }

    private void endInternal(long endTick, @Nullable ErrorMessage errorMessage) {
        // timer is only null for trace entries added using addEntryEntry(), and these trace entries
        // are not returned from plugin api so no way for end...() to be called
        checkNotNull(syncTimer);
        if (asyncTimer == null) {
            syncTimer.end(endTick);
        } else {
            asyncTimer.end(endTick);
        }
        endQueryData(endTick);
        this.errorMessage = errorMessage;
        this.endTick = endTick;
        if (isAsync()) {
            threadContext.getTransaction().memoryBarrierWrite();
        } else {
            selfNestingLevel--;
            threadContext.popEntry(this, endTick);
        }
        initialComplete = true;
    }

    private String getRowCountSuffix() {
        if (!isRowNavigationAttempted()) {
            return "";
        }
        long rowCount = getRowCount();
        if (rowCount == 1) {
            return " => 1 row";
        } else {
            return " => " + rowCount + " rows";
        }
    }

    @Override
    public void stopSyncTimer() {
        // syncTimer is only null for trace entries added using addEntryEntry(), and these trace
        // entries are not returned from plugin api so no way for stopSyncTimer() to be called
        checkNotNull(syncTimer);
        syncTimer.stop();
        selfNestingLevel--;
        threadContext.popNonRootEntry(this);
    }

    @Override
    public Timer extendSyncTimer(ThreadContext currThreadContext) {
        if (currThreadContext != threadContext) {
            return NopTimer.INSTANCE;
        }
        // syncTimer is only null for trace entries added using addEntryEntry(), and these trace
        // entries are not returned from plugin api so no way for extendSyncTimer() to be called
        checkNotNull(syncTimer);
        return syncTimer.extend();
    }

    // this is used for debugging, in particular in TraceEntryComponent.popEntryBailout()
    @Override
    public String toString() {
        if (messageSupplier != null) {
            return ((ReadableMessage) messageSupplier.get()).getText();
        }
        if (errorMessage != null) {
            return errorMessage.message();
        }
        return super.toString();
    }
}
