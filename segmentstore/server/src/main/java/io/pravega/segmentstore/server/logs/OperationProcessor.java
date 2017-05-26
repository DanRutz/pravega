/**
 * Copyright (c) 2017 Dell Inc., or its subsidiaries. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package io.pravega.segmentstore.server.logs;

import com.google.common.base.Preconditions;
import io.pravega.common.ExceptionHelpers;
import io.pravega.common.ObjectClosedException;
import io.pravega.common.concurrent.AbstractThreadPoolService;
import io.pravega.common.concurrent.FutureHelpers;
import io.pravega.common.function.CallbackHelpers;
import io.pravega.common.util.BlockingDrainingQueue;
import io.pravega.segmentstore.server.DataCorruptionException;
import io.pravega.segmentstore.server.IllegalContainerStateException;
import io.pravega.segmentstore.server.UpdateableContainerMetadata;
import io.pravega.segmentstore.server.logs.operations.CompletableOperation;
import io.pravega.segmentstore.server.logs.operations.Operation;
import io.pravega.segmentstore.storage.DataLogWriterNotPrimaryException;
import io.pravega.segmentstore.storage.DurableDataLog;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledExecutorService;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import javax.annotation.concurrent.GuardedBy;
import javax.annotation.concurrent.ThreadSafe;
import lombok.Builder;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import lombok.val;

/**
 * Single-thread Processor for Operations. Queues all incoming entries in a BlockingDrainingQueue, then picks them all
 * at once, generates DataFrames from them and commits them to the DataFrameLog, one by one, in sequence.
 */
@Slf4j
class OperationProcessor extends AbstractThreadPoolService implements AutoCloseable {
    //region Members

    private final Config config;
    @GuardedBy("stateLock")
    private final OperationMetadataUpdater metadataUpdater;
    private final DurableDataLog durableDataLog;
    private final BlockingDrainingQueue<CompletableOperation> operationQueue;
    private final Object stateLock = new Object();
    private final QueueProcessingState state;
    @GuardedBy("stateLock")
    private DataFrameBuilder<Operation> dataFrameBuilder;

    //endregion

    //region Constructor

    /**
     * Creates a new instance of the OperationProcessor class.
     *
     * @param config           OperationProcessor Configuration.
     * @param metadata         The ContainerMetadata for the Container to process operations for.
     * @param stateUpdater     A MemoryStateUpdater that is used to update in-memory structures upon successful Operation committal.
     * @param durableDataLog   The DataFrameLog to write DataFrames to.
     * @param checkpointPolicy The Checkpoint Policy for Metadata.
     * @param executor         An Executor to use for async operations.
     * @throws NullPointerException If any of the arguments are null.
     */
    OperationProcessor(Config config, UpdateableContainerMetadata metadata, MemoryStateUpdater stateUpdater,
                       DurableDataLog durableDataLog, MetadataCheckpointPolicy checkpointPolicy, ScheduledExecutorService executor) {
        super(String.format("OperationProcessor[%d]", metadata.getContainerId()), executor);
        this.config = Preconditions.checkNotNull(config, "config");
        this.metadataUpdater = new OperationMetadataUpdater(metadata);
        this.durableDataLog = Preconditions.checkNotNull(durableDataLog, "durableDataLog");
        this.operationQueue = new BlockingDrainingQueue<>();
        this.state = new QueueProcessingState(this.metadataUpdater, stateUpdater, checkpointPolicy, this::errorHandler,
                this.stateLock, this.traceObjectId);
        this.dataFrameBuilder = null;
    }

    //endregion

    //region AbstractThreadPoolService Implementation

    @Override
    protected Duration getShutdownTimeout() {
        return this.config.shutdownTimeout;
    }

    @Override
    protected CompletableFuture<Void> doRun() {
        return FutureHelpers.loop(
                this::isRunning,
                () -> this.operationQueue
                        .take(this.config.maxReadAtOnce)
                        .thenAcceptAsync(this::processOperations, this.executor),
                this.executor);
    }

    @Override
    protected void doStop() {
        // We need to first stop the queue, which will prevent any new items from being processed.
        closeQueue(null);

        // Then close the DataFrameBuilder, which will await any pending data frames to be flushed away.
        val builder = getDataFrameBuilder(false);
        if (builder != null) {
            builder.close();
        }

        super.doStop();
    }

    @Override
    protected void errorHandler(Throwable ex) {
        ex = ExceptionHelpers.getRealException(ex);
        closeQueue(ex);
        if (!(ex instanceof CancellationException)) {
            // CancellationException means we are already stopping, so no need to do anything else. For all other cases,
            // record the failure and then stop the OperationProcessor.
            super.errorHandler(ex);
            stopAsync();
        }
    }

    //endregion

    //region Operations

    /**
     * Processes the given Operation. This method returns when the given Operation has been added to the internal queue.
     *
     * @param operation The Operation to process.
     * @return A CompletableFuture that, when completed, will indicate the Operation has finished processing. If the
     * Operation completed successfully, the Future will contain the Sequence Number of the Operation. If the Operation
     * failed, it will contain the exception that caused the failure.
     * @throws IllegalContainerStateException If the OperationProcessor is not running.
     */
    public CompletableFuture<Long> process(Operation operation) {
        CompletableFuture<Long> result = new CompletableFuture<>();
        if (!isRunning()) {
            result.completeExceptionally(new IllegalContainerStateException("OperationProcessor is not running."));
        } else {
            log.debug("{}: process {}.", this.traceObjectId, operation);
            try {
                this.operationQueue.add(new CompletableOperation(operation, result));
            } catch (Throwable e) {
                if (ExceptionHelpers.mustRethrow(e)) {
                    throw e;
                }

                result.completeExceptionally(e);
            }
        }

        return result;
    }

    //endregion

    //region Queue Processing

    private DataFrameBuilder<Operation> getDataFrameBuilder(boolean recover) {
        synchronized (this.stateLock) {
            if (this.dataFrameBuilder == null || this.dataFrameBuilder.failureCause() != null) {
                // Builder is not created or in a failed state.
                if (recover) {
                    // If instructed to recover, recreate a new one.
                    val args = new DataFrameBuilder.Args(this.config.maxConcurrentWrites, this.state::checkpoint, this.state::commit, this.state::fail, this.executor);
                    this.dataFrameBuilder = new DataFrameBuilder<>(this.durableDataLog, args);
                } else {
                    this.dataFrameBuilder = null;
                }
            }

            return this.dataFrameBuilder;
        }
    }

    /**
     * Processes a set of pending operations (essentially a single iteration of the QueueProcessor).
     * Steps:
     * <ol>
     * <li> Picks the next items from the queue
     * <li> Creates a DataFrameBuilder and starts appending items to it.
     * <li> As the DataFrameBuilder acknowledges DataFrames being published, acknowledge the corresponding Operations as well.
     * <li> If at the end, the Queue still has items to process, processes those as well.
     * </ol>
     *
     * @param operations The initial set of operations to process (in order). Multiple operations may be processed eventually
     *                   depending on how the operationQueue changes while this is processing.
     */
    @SneakyThrows
    private void processOperations(Queue<CompletableOperation> operations) {
        log.debug("{}: processOperations (OperationCount = {}).", this.traceObjectId, operations.size());

        // Process the operations in the queue. This loop will ensure we do continuous processing in case new items
        // arrived while we were busy handling the current items.
        while (!operations.isEmpty()) {
            try {
                // Get the DataFrameBuilder, and recover it if necessary.
                val builder = getDataFrameBuilder(true);

                // Process the current set of operations.
                while (!operations.isEmpty()) {
                    CompletableOperation o = operations.poll();
                    if (processOperation(o, builder)) {
                        // Add the operation as 'pending', only if we were able to successfully append it to a data frame.
                        // We only commit data frames when we attempt to start a new record (if it's full) or if we try to
                        // close it, so we will not miss out on it.
                        this.state.addPending(o);
                    }
                }

                // Check if there are more operations to process. If so, it's more efficient to process them now (no thread
                // context switching, better DataFrame occupancy optimization) rather than by going back to run().
                if (operations.isEmpty()) {
                    operations = this.operationQueue.poll(this.config.maxReadAtOnce);
                    if (operations.isEmpty()) {
                        log.debug("{}: processOperations (Flush).", this.traceObjectId);
                        builder.flush();
                    } else {
                        log.debug("{}: processOperations (Add OperationCount = {}).", this.traceObjectId, operations.size());
                    }
                }
            } catch (Throwable ex) {
                // Fail ALL the operations that haven't been checkpointed yet.
                Throwable realCause = ExceptionHelpers.getRealException(ex);
                this.state.fail(realCause, null);

                if (isFatalException(realCause)) {
                    // If we encountered a fatal exception, it means we detected something that we cannot possibly recover from.
                    // We need to shutdown right away (this will be done by the main loop).

                    // But first, fail any Operations that we did not have a chance to process yet.
                    cancelIncompleteOperations(operations, realCause);
                    throw realCause;
                }
            }
        }
    }

    /**
     * Processes a single operation.
     * Steps:
     * <ol>
     * <li> Pre-processes operation (in MetadataUpdater).
     * <li> Assigns Sequence Number.
     * <li> Appends to DataFrameBuilder.
     * <li> Accepts operation in MetadataUpdater.
     * </ol>
     * Any exceptions along the way will result in the immediate failure of the operation. Exceptions do not bubble out
     * of this method. The only way to determine whether the operation completed normally or not is to inspect the result.
     *
     * @param operation        The operation to process.
     * @param dataFrameBuilder The DataFrameBuilder to append the operation to.
     * @return True if processed successfully, false otherwise.
     */
    private boolean processOperation(CompletableOperation operation, DataFrameBuilder<Operation> dataFrameBuilder) throws DataCorruptionException {
        Preconditions.checkState(!operation.isDone(), "The Operation has already been processed.");

        Operation entry = operation.getOperation();
        if (!entry.canSerialize()) {
            // This operation cannot be serialized, so don't bother doing anything with it.
            return true;
        }

        try {
            synchronized (this.stateLock) {
                // Update Metadata and Operations with any missing data (offsets, lengths, etc) - the Metadata Updater has all the knowledge for that task.
                this.metadataUpdater.preProcessOperation(entry);

                // Entry is ready to be serialized; assign a sequence number.
                entry.setSequenceNumber(this.metadataUpdater.nextOperationSequenceNumber());
            }

            log.trace("{}: DataFrameBuilder.Append {}.", this.traceObjectId, operation.getOperation());
            dataFrameBuilder.append(operation.getOperation());
            synchronized (this.stateLock) {
                this.metadataUpdater.acceptOperation(entry);
            }
        } catch (ObjectClosedException ex) {
            // DataFrameBuilder is in a closed/failed state. We cannot proceed with this operation, or any other one,
            // so we need to re-throw the exception for it to be handled upstream.
            Throwable rootCause = dataFrameBuilder.failureCause();
            if (rootCause != null && rootCause != ex) {
                ex.addSuppressed(rootCause);
            }

            operation.fail(ex);
            throw ex;
        } catch (Exception ex) {
            operation.fail(ex);
            Throwable cause = ExceptionHelpers.getRealException(ex);
            if (cause instanceof DataCorruptionException) {
                // Besides failing the operation, DataCorruptionExceptions are pretty serious. We should shut down the
                // Operation Processor if we ever encounter one.
                throw (DataCorruptionException) cause;
            }

            return false;
        }

        return true;
    }

    /**
     * Closes the Operation Queue and fails all Operations in it with the given exception.
     *
     * @param causingException The exception to fail with. If null, it will default to ObjectClosedException.
     */
    private void closeQueue(Throwable causingException) {
        BlockingDrainingQueue<CompletableOperation> queue = this.operationQueue;
        if (queue != null) {
            // Close the queue and extract any outstanding Operations from it.
            Collection<CompletableOperation> remainingOperations = queue.close();
            if (remainingOperations != null && remainingOperations.size() > 0) {
                // If any outstanding Operations were left in the queue, they need to be failed.
                // If no other cause was passed, assume we are closing the queue because we are shutting down.
                Throwable failException = causingException != null ? causingException : new ObjectClosedException(this);
                cancelIncompleteOperations(remainingOperations, failException);
            }
        }
    }

    /**
     * Cancels those Operations in the given list that have not yet completed with the given exception.
     */
    private void cancelIncompleteOperations(Iterable<CompletableOperation> operations, Throwable failException) {
        assert failException != null : "no exception to set";
        int cancelCount = 0;
        for (CompletableOperation o : operations) {
            if (!o.isDone()) {
                o.fail(failException);
                cancelCount++;
            }
        }

        log.warn("{}: Cancelling {} operations with exception: {}.", this.traceObjectId, cancelCount, failException.toString());
    }

    /**
     * Determines whether the given Throwable is a fatal exception from which we cannot recover.
     */
    private static boolean isFatalException(Throwable ex) {
        return ex instanceof DataCorruptionException
                || ex instanceof DataLogWriterNotPrimaryException;
    }

    //endregion

    //region QueueProcessingState

    /**
     * Temporary State for the QueueProcessor. Keeps track of pending Operations and allows committing or failing all of them.
     */
    @Slf4j
    @ThreadSafe
    private static class QueueProcessingState {
        private final String traceObjectId;
        private final Object lock;
        @GuardedBy("lock")
        private final Deque<CompletableOperation> pendingOperations;
        @GuardedBy("lock")
        private final OperationMetadataUpdater metadataUpdater;
        @GuardedBy("lock")
        private final MemoryStateUpdater logUpdater;
        @GuardedBy("lock")
        private final MetadataCheckpointPolicy checkpointPolicy;
        private final Consumer<Throwable> fatalExceptionCallback;
        @GuardedBy("lock")
        private final HashMap<DataFrameBuilder.DataFrameCommitArgs, Long> metadataTransactions;
        @GuardedBy("lock")
        private long highestCommittedDataFrame;

        QueueProcessingState(OperationMetadataUpdater metadataUpdater, MemoryStateUpdater stateUpdater, MetadataCheckpointPolicy checkpointPolicy,
                             Consumer<Throwable> fatalExceptionCallback, Object lock, String traceObjectId) {
            this.metadataUpdater = Preconditions.checkNotNull(metadataUpdater, "metadataUpdater");
            this.logUpdater = Preconditions.checkNotNull(stateUpdater, "stateUpdater");
            this.checkpointPolicy = Preconditions.checkNotNull(checkpointPolicy, "checkpointPolicy");
            this.fatalExceptionCallback = Preconditions.checkNotNull(fatalExceptionCallback, "fatalExceptionCallback");
            this.lock = Preconditions.checkNotNull(lock, "lock");
            this.traceObjectId = traceObjectId;
            this.pendingOperations = new ArrayDeque<>();
            this.metadataTransactions = new HashMap<>();
            this.highestCommittedDataFrame = -1;
        }

        /**
         * Adds a new pending operation.
         *
         * @param operation The operation to append.
         */
        void addPending(CompletableOperation operation) {
            synchronized (this.lock) {
                this.pendingOperations.add(operation);
            }

            autoCompleteIfNeeded();
        }

        /**
         * Auto-completes any non-serialization operations at the beginning of the Pending Operations queue. Due to their
         * nature, these operations are at risk of never being completed, and, if there are no more pending operations
         * before that, they can be completed without further delay.
         */
        private void autoCompleteIfNeeded() {
            Collection<CompletableOperation> toComplete = null;
            synchronized (this.lock) {
                while (!this.pendingOperations.isEmpty() && !this.pendingOperations.peekFirst().getOperation().canSerialize()) {
                    if (toComplete == null) {
                        toComplete = new ArrayList<>();
                    }

                    toComplete.add(this.pendingOperations.pollFirst());
                }
            }

            if (toComplete != null) {
                toComplete.forEach(CompletableOperation::complete);
            }
        }

        /**
         * Creates a checkpoint keyed in with the given DataFrameCommitArgs. This indicates that the DataFrame represented
         * by the given args has been sealed and is about to be committed. This checkpoint will mark a point in the
         * OperationMetadataUpdater that corresponds to the end of that DataFrame.
         *
         * @param commitArgs The DataFrameCommitArgs to create a checkpoint for.
         */
        void checkpoint(DataFrameBuilder.DataFrameCommitArgs commitArgs) {
            synchronized (this.lock) {
                long transactionId = this.metadataUpdater.sealTransaction();
                this.metadataTransactions.put(commitArgs, transactionId);
            }
        }

        /**
         * Commits all pending Metadata changes, assigns a TruncationMarker and acknowledges all the pending operations.
         *
         * @param commitArgs The Data Frame Commit Args that triggered this action.
         */
        void commit(DataFrameBuilder.DataFrameCommitArgs commitArgs) {
            log.debug("{}: CommitSuccess ({}).", this.traceObjectId, commitArgs);

            synchronized (this.lock) {
                // Record the Truncation marker. The OperationMetadataUpdater will update the Metadata directly for this.
                this.metadataUpdater.recordTruncationMarker(commitArgs.getLastStartedSequenceNumber(), commitArgs.getLogAddress());
                if (commitArgs.getLogAddress().getSequence() <= this.highestCommittedDataFrame) {
                    // Ack came out of order (we already processed one with a higher SeqNo).
                    log.debug("{}: CommitRejected ({}, HighestCommittedDataFrame = ).", this.traceObjectId, commitArgs, this.highestCommittedDataFrame);
                    this.checkpointPolicy.recordCommit(commitArgs.getDataFrameLength());
                    return;
                }

                // Commit any changes to the metadata.
                final long checkpointId = getMetadataTransaction(commitArgs);
                assert checkpointId >= 0 : "No Metadata UpdateTransaction found for " + commitArgs;
                removeTransactions(c -> c <= checkpointId);
                this.metadataUpdater.commit(checkpointId);

                // Acknowledge all pending entries, in the order in which they are in the queue (ascending seq no).
                final long lastSeqNo = commitArgs.getLastFullySerializedSequenceNumber();
                while (!this.pendingOperations.isEmpty()
                        && this.pendingOperations.peekFirst().getOperation().getSequenceNumber() <= lastSeqNo) {
                    CompletableOperation e = this.pendingOperations.pollFirst();
                    try {
                        this.logUpdater.process(e.getOperation());
                    } catch (Throwable ex) {
                        // MemoryStateUpdater.process() should only throw DataCorruptionExceptions, but just in case it
                        // throws something else (i.e. NullPtr), we still need to handle it.
                        // First, fail the operation, since it has already been taken off the pending list.
                        log.error("{}: OperationCommitFailure ({}). {}", this.traceObjectId, e.getOperation(), ex);
                        e.fail(ex);

                        // Then fail the remaining operations (which also handles fatal errors) and bail out.
                        fail(ex, commitArgs);
                        return;
                    }

                    e.complete();
                }

                this.logUpdater.flush();
                this.checkpointPolicy.recordCommit(commitArgs.getDataFrameLength());
                this.highestCommittedDataFrame = commitArgs.getLogAddress().getSequence();
            }

            autoCompleteIfNeeded();
        }

        /**
         * Rolls back all pending Metadata changes and fails all pending operations.
         *
         * @param ex The cause of the failure. The operations will be failed with this as a cause.
         * @param commitArgs The Data Frame Commit Args that triggered this action.
         */
        void fail(Throwable ex, DataFrameBuilder.DataFrameCommitArgs commitArgs) {
            synchronized (this.lock) {
                // Discard all updates to the metadata.
                long updateTransactionId;
                if (commitArgs == null) {
                    updateTransactionId = 0;
                } else {
                    updateTransactionId = getMetadataTransaction(commitArgs);
                }

                removeTransactions(c -> c >= updateTransactionId);
                this.metadataUpdater.rollback(updateTransactionId);

                // Fail all pending entries.
                while (!this.pendingOperations.isEmpty()) {
                    CompletableOperation e = this.pendingOperations.pollLast();
                    e.fail(ex);
                }
            }

            if (isFatalException(ex)) {
                CallbackHelpers.invokeSafely(this.fatalExceptionCallback, ex, null);
            }

            autoCompleteIfNeeded();
        }

        @GuardedBy("lock")
        private long getMetadataTransaction(DataFrameBuilder.DataFrameCommitArgs commitArgs) {
            Long transactionId = this.metadataTransactions.remove(commitArgs);
            return transactionId == null ? -1 : transactionId;
        }

        @GuardedBy("lock")
        private void removeTransactions(Predicate<Long> tester) {
            val toRemove = this.metadataTransactions.entrySet().stream()
                                                    .filter(e -> tester.test(e.getValue()))
                                                    .map(Map.Entry::getKey)
                                                    .collect(Collectors.toList());
            toRemove.forEach(this.metadataTransactions::remove);
        }
    }

    //endregion

    @Builder
    static class Config {
        @Builder.Default
        private int maxConcurrentWrites = 1;
        @Builder.Default
        private int maxReadAtOnce = 1000;
        @Builder.Default
        private Duration shutdownTimeout = Duration.ofSeconds(10);
    }
}