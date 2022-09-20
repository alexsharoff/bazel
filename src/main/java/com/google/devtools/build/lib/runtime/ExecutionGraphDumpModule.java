// Copyright 2022 The Bazel Authors. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
package com.google.devtools.build.lib.runtime;

import static java.util.concurrent.TimeUnit.SECONDS;

import com.github.luben.zstd.ZstdOutputStream;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import com.google.common.base.Stopwatch;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;
import com.google.common.eventbus.AllowConcurrentEvents;
import com.google.common.eventbus.Subscribe;
import com.google.common.flogger.GoogleLogger;
import com.google.devtools.build.lib.actions.ActionInput;
import com.google.devtools.build.lib.actions.Artifact;
import com.google.devtools.build.lib.actions.ExecutionGraph;
import com.google.devtools.build.lib.actions.Spawn;
import com.google.devtools.build.lib.actions.SpawnExecutedEvent;
import com.google.devtools.build.lib.actions.SpawnMetrics;
import com.google.devtools.build.lib.actions.SpawnResult;
import com.google.devtools.build.lib.bugreport.BugReport;
import com.google.devtools.build.lib.bugreport.BugReporter;
import com.google.devtools.build.lib.buildeventstream.BuildEvent.LocalFile.LocalFileCompression;
import com.google.devtools.build.lib.buildeventstream.BuildEvent.LocalFile.LocalFileType;
import com.google.devtools.build.lib.buildeventstream.BuildEventArtifactUploader;
import com.google.devtools.build.lib.buildeventstream.BuildEventArtifactUploader.UploadContext;
import com.google.devtools.build.lib.buildeventstream.BuildEventProtocolOptions;
import com.google.devtools.build.lib.buildtool.BuildResult.BuildToolLogCollection;
import com.google.devtools.build.lib.buildtool.buildevent.BuildCompleteEvent;
import com.google.devtools.build.lib.buildtool.buildevent.ExecutionStartingEvent;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.exec.local.LocalExecutionOptions;
import com.google.devtools.build.lib.runtime.BuildEventArtifactUploaderFactory.InvalidPackagePathSymlinkException;
import com.google.devtools.build.lib.server.FailureDetails.BuildReport;
import com.google.devtools.build.lib.server.FailureDetails.BuildReport.Code;
import com.google.devtools.build.lib.server.FailureDetails.FailureDetail;
import com.google.devtools.build.lib.util.AbruptExitException;
import com.google.devtools.build.lib.util.DetailedExitCode;
import com.google.devtools.build.lib.util.InterruptedFailureDetails;
import com.google.devtools.build.lib.vfs.Path;
import com.google.devtools.common.options.EnumConverter;
import com.google.devtools.common.options.Option;
import com.google.devtools.common.options.OptionDocumentationCategory;
import com.google.devtools.common.options.OptionEffectTag;
import com.google.devtools.common.options.OptionsBase;
import com.google.devtools.common.options.OptionsParsingResult;
import com.google.protobuf.CodedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/** Blaze module that writes an partial execution graph with performance data. */
public class ExecutionGraphDumpModule extends BlazeModule {

  private static final String ACTION_DUMP_NAME = "execution_graph_dump.proto.zst";

  private static final GoogleLogger logger = GoogleLogger.forEnclosingClass();

  /** Options for the generated execution graph. */
  public static class ExecutionGraphDumpOptions extends OptionsBase {
    @Option(
        name = "experimental_execution_graph_log",
        documentationCategory = OptionDocumentationCategory.UNDOCUMENTED,
        effectTags = {OptionEffectTag.AFFECTS_OUTPUTS},
        defaultValue = "",
        help =
            "Enabling this flag makes Blaze write a file of all actions executed during a build. "
                + "Note that this dump may use a different granularity of actions than other APIs, "
                + "and may also contain additional information as necessary to reconstruct the "
                + "full dependency graph in combination with other sources of data.")
    public String executionGraphLogFile;

    @Option(
        name = "experimental_execution_graph_log_dep_type",
        converter = DependencyInfoConverter.class,
        documentationCategory = OptionDocumentationCategory.UNDOCUMENTED,
        effectTags = {OptionEffectTag.UNKNOWN},
        defaultValue = "none",
        help =
            "Selects what kind of dependency information is reported in the action dump. If 'all',"
                + " every inter-action edge will be reported.")
    public DependencyInfo depType;

    @Option(
        name = "experimental_execution_graph_log_queue_size",
        documentationCategory = OptionDocumentationCategory.UNDOCUMENTED,
        effectTags = {OptionEffectTag.UNKNOWN},
        defaultValue = "-1",
        help =
            "The size of the action dump queue, where actions are kept before writing. Larger"
                + " sizes will increase peak memory usage, but should decrease queue blocking. -1"
                + " means unbounded")
    public int queueSize;
  }

  /** What level of dependency information to include in the dump. */
  public enum DependencyInfo {
    NONE,
    RUNFILES,
    ALL;
  }

  /** Converter for dependency information level. */
  public static class DependencyInfoConverter extends EnumConverter<DependencyInfo> {
    public DependencyInfoConverter() {
      super(DependencyInfo.class, "dependency edge strategy");
    }
  }

  private ActionDumpWriter writer;
  private CommandEnvironment env;

  @Override
  public Iterable<Class<? extends OptionsBase>> getCommandOptions(Command command) {
    return "build".equals(command.name())
        ? ImmutableList.of(ExecutionGraphDumpOptions.class)
        : ImmutableList.of();
  }

  @VisibleForTesting
  public void setWriter(ActionDumpWriter writer) {
    this.writer = writer;
  }

  @Override
  public void beforeCommand(CommandEnvironment env) {
    this.env = env;

    if (env.getCommand().builds()) {
      ExecutionGraphDumpOptions options =
          Preconditions.checkNotNull(
              env.getOptions().getOptions(ExecutionGraphDumpOptions.class),
              "ExecutionGraphDumpOptions must be present for ExecutionGraphDumpModule");
      if (!options.executionGraphLogFile.isBlank()) {
        env.getEventBus().register(this);
      }
    }
  }

  @Subscribe
  public void executionPhaseStarting(ExecutionStartingEvent event) {
    try {
      // Defer creation of writer until the start of the execution phase. This is done for two
      // reasons:
      //   - The writer's consumer thread spends 4MB on buffer space, and this is wasted retained
      //     heap during the analysis phase.
      //   - We want to start the writer only when we have the guarantee we'll shut it down in
      //     #buildComplete. It'd be unsound to start the writer before BuildStartingEvent, and
      //     ExecutionStartingEvent definitely postdates that.
      writer = createActionDumpWriter(env);
    } catch (InvalidPackagePathSymlinkException e) {
      DetailedExitCode detailedExitCode =
          DetailedExitCode.of(makeReportUploaderNeedsPackagePathsDetail());
      env.getBlazeModuleEnvironment().exit(new AbruptExitException(detailedExitCode, e));
    } catch (ActionDumpFileCreationException e) {
      DetailedExitCode detailedExitCode = DetailedExitCode.of(makeReportWriteFailedDetail());
      env.getBlazeModuleEnvironment().exit(new AbruptExitException(detailedExitCode, e));
    } finally {
      env = null;
    }
  }

  @Subscribe
  public void buildComplete(BuildCompleteEvent event) {
    try {
      shutdown(event.getResult().getBuildToolLogCollection());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      // Env might be set to null by a concurrent call to shutdown (via afterCommand).
      CommandEnvironment localEnv = env;
      if (localEnv != null) {
        // Inform environment that we were interrupted: this can override the existing exit code
        // in some cases when the environment "finalizes" the exit code.
        localEnv
            .getBlazeModuleEnvironment()
            .exit(
                InterruptedFailureDetails.abruptExitException(
                    "action dump shutdown interrupted", e));
      }
    }
  }

  @Subscribe
  @AllowConcurrentEvents
  public void spawnExecuted(SpawnExecutedEvent event) {
    // Writer might be modified by a concurrent call to shutdown. See b/184943744.
    // It may be possible to get a BuildCompleteEvent before a duplicate Spawn that runs with a
    // dynamic execution strategy, in which case we wouldn't export that Spawn. That's ok, since it
    // didn't affect the latency of the build.
    ActionDumpWriter localWriter = writer;
    if (localWriter != null) {
      localWriter.enqueue(event);
    }
  }

  @Override
  public void afterCommand() throws AbruptExitException {
    // Defensively shut down in case we failed to do so under normal operation.
    try {
      shutdown(null);
    } catch (InterruptedException e) {
      throw InterruptedFailureDetails.abruptExitException("action dump shutdown interrupted", e);
    }
  }

  private void shutdown(BuildToolLogCollection logs) throws InterruptedException {
    // Writer might be set to null by a concurrent call to shutdown (via afterCommand).
    ActionDumpWriter localWriter = writer;
    try {
      // Writer might never have been set if the execution phase never happened (see
      // executionPhaseStarting).
      if (localWriter != null) {
        localWriter.shutdown(logs);
      }
    } finally {
      writer = null;
      env = null;
    }
  }

  /** An ActionDumpWriter writes action dump data to a given {@link OutputStream}. */
  @VisibleForTesting
  protected abstract static class ActionDumpWriter implements Runnable {

    private ExecutionGraph.Node toProto(SpawnExecutedEvent event) {
      ExecutionGraph.Node.Builder nodeBuilder = ExecutionGraph.Node.newBuilder();
      int index = nextIndex.getAndIncrement();
      Spawn spawn = event.getSpawn();
      long startMillis = event.getStartTimeInstant().toEpochMilli();
      SpawnResult spawnResult = event.getSpawnResult();
      nodeBuilder
          // TODO(vanja) consider switching prettyPrint() to description()
          .setDescription(event.getActionMetadata().prettyPrint())
          .setMnemonic(spawn.getMnemonic())
          .setRunner(spawnResult.getRunnerName())
          .setRunnerSubtype(spawnResult.getRunnerSubtype());

      if (depType != DependencyInfo.NONE) {
        nodeBuilder.setIndex(index);
      }
      Label ownerLabel = spawn.getResourceOwner().getOwner().getLabel();
      if (ownerLabel != null) {
        nodeBuilder.setTargetLabel(ownerLabel.toString());
      }

      SpawnMetrics metrics = spawnResult.getMetrics();
      spawnResult = null;
      long totalMillis = metrics.totalTime().toMillis();
      ExecutionGraph.Metrics.Builder metricsBuilder =
          ExecutionGraph.Metrics.newBuilder()
              .setStartTimestampMillis(startMillis)
              .setDurationMillis((int) totalMillis)
              .setFetchMillis((int) metrics.fetchTime().toMillis())
              .setParseMillis((int) metrics.parseTime().toMillis())
              .setProcessMillis((int) metrics.executionWallTime().toMillis())
              .setQueueMillis((int) metrics.queueTime().toMillis())
              .setRetryMillis((int) metrics.retryTime().toMillis())
              .setSetupMillis((int) metrics.setupTime().toMillis())
              .setUploadMillis((int) metrics.uploadTime().toMillis())
              .setNetworkMillis((int) metrics.networkTime().toMillis())
              .setOtherMillis((int) metrics.otherTime().toMillis())
              .setProcessOutputsMillis((int) metrics.processOutputsTime().toMillis());

      for (Map.Entry<Integer, Duration> entry : metrics.retryTimeByError().entrySet()) {
        metricsBuilder.putRetryMillisByError(entry.getKey(), (int) entry.getValue().toMillis());
      }
      metrics = null;
      // maybeAddEdges can take a while, so do it last and try to give up references to any objects
      // we won't need.
      maybeAddEdges(nodeBuilder, spawn, startMillis, totalMillis, index);
      return nodeBuilder.setMetrics(metricsBuilder).build();
    }

    private void maybeAddEdges(
        ExecutionGraph.Node.Builder nodeBuilder,
        Spawn spawn,
        long startMillis,
        long totalMillis,
        int index) {
      if (depType == DependencyInfo.NONE) {
        return;
      }

      // Spawn.getOutputFiles can be empty. For example, SpawnAction can be made to not report
      // outputs, and ExtraAction uses that. In that case, fall back to the owner's primary output.
      ActionInput primaryOutput = Iterables.getFirst(spawn.getOutputFiles(), null);
      if (primaryOutput == null) {
        // Despite the stated contract of getPrimaryOutput(), it can return null, like in
        // GrepIncludesAction.
        primaryOutput = spawn.getResourceOwner().getPrimaryOutput();
      }
      if (primaryOutput != null) {
        // If primaryOutput is null, then we know that spawn.getOutputFiles() is also empty, and we
        // don't need to do any of the following.
        SpawnInfo previousAttempt = outputFileToSpawn.get(primaryOutput);
        if (previousAttempt != null) {
          // The same action may issue multiple spawns for various reasons:
          //
          // Different "primary output" for each spawn (hence not entering this if condition):
          //   - Actions with multiple spawns (e.g. inputs discovering actions).
          //   - Remote execution splitting the spawn into multiple ones (spawns generating tree
          //     artifacts).
          //
          // Running sequentially:
          //   - Test retries.
          //   - Java compilation (fallback) after an attempt with a reduced classpath.
          //   - Retry of a spawn after remote execution failure when using `--local_fallback`.
          //
          /// Running in parallel:
          //   - Dynamic execution with `--experimental_local_lockfree_output`--with that setting,
          //     it is possible for both local and remote spawns to finish and send a corresponding
          //     event.
          if (previousAttempt.finishMs <= startMillis) {
            nodeBuilder.setRetryOf(previousAttempt.index);
          } else if (localLockFreeOutputEnabled) {
            // Special case what could be dynamic execution with
            // `--experimental_local_lockfree_output`, skip adding the dependencies for the second
            // spawn, but report both spawns.
            return;
          } else {
            // TODO(b/227635546): Remove the bug report once we capture all cases when it can
            //  fire.
            bugReporter.sendNonFatalBugReport(
                new IllegalStateException(
                    String.format(
                        "See b/227635546. Multiple spawns produced '%s' with overlapping execution"
                            + " time. Previous index: %s. Current index: %s",
                        primaryOutput.getExecPathString(), previousAttempt.index, index)));
          }
        }

        SpawnInfo currentAttempt = new SpawnInfo(index, startMillis + totalMillis);
        for (ActionInput output : spawn.getOutputFiles()) {
          outputFileToSpawn.put(output, currentAttempt);
        }
        // Some actions, like tests, don't have their primary output in getOutputFiles().
        outputFileToSpawn.put(primaryOutput, currentAttempt);
      }

      // Don't store duplicate deps. This saves some storage space, and uses less memory when the
      // action dump is parsed. Using a TreeSet is not slower than a HashSet, and it seems that
      // keeping the deps ordered compresses better. See cl/377153712.
      Set<Integer> deps = new TreeSet<>();
      for (Artifact runfilesInput : spawn.getRunfilesSupplier().getArtifacts().toList()) {
        SpawnInfo dep = outputFileToSpawn.get(runfilesInput);
        if (dep != null) {
          deps.add(dep.index);
        }
      }

      if (depType == DependencyInfo.ALL) {
        for (ActionInput input : spawn.getInputFiles().toList()) {
          SpawnInfo dep = outputFileToSpawn.get(input);
          if (dep != null) {
            deps.add(dep.index);
          }
        }
      }
      nodeBuilder.addAllDependentIndex(deps);
    }

    private static final class SpawnInfo {
      private final int index;
      private final long finishMs;

      private SpawnInfo(int index, long finishMs) {
        this.index = index;
        this.finishMs = finishMs;
      }
    }

    private final BugReporter bugReporter;
    private final boolean localLockFreeOutputEnabled;
    private final Map<ActionInput, SpawnInfo> outputFileToSpawn = new ConcurrentHashMap<>();
    private final DependencyInfo depType;
    private final AtomicInteger nextIndex = new AtomicInteger(0);

    // At larger capacities, ArrayBlockingQueue uses slightly less peak memory, but it doesn't
    // matter at lower capacities. Wall time performance is the same either way.
    // In benchmarks, capacities under 100 start increasing wall time and between 1000 and 100000
    // seem to have roughly the same wall time and memory usage. In the real world, using a queue
    // of size 10000 causes many builds to block for a total of more than 100ms. The queue
    // entries should be about 256 bytes, so a queue size of 1_000_000 will use up to 256MB,
    // but the vast majority of builds don't have that many actions.
    private final BlockingQueue<byte[]> queue;
    private final AtomicLong blockedMillis = new AtomicLong(0);
    private final OutputStream outStream;
    private final Thread thread;

    // This queue entry signals that there are no more entries that need to be written.
    private static final byte[] INVOCATION_COMPLETED = new byte[0];

    // Based on benchmarks. 2Mib buffers seem sufficient, and buffers bigger than that don't
    // provide much benefit.
    private static final int OUTPUT_BUFFER_SIZE = 1 << 21;

    ActionDumpWriter(
        BugReporter bugReporter,
        boolean localLockFreeOutputEnabled,
        OutputStream outStream,
        UUID commandId,
        DependencyInfo depType,
        int queueSize) {
      this.bugReporter = bugReporter;
      this.localLockFreeOutputEnabled = localLockFreeOutputEnabled;
      this.outStream = outStream;
      this.depType = depType;
      if (queueSize < 0) {
        queue = new LinkedBlockingQueue<>();
      } else {
        queue = new LinkedBlockingQueue<>(queueSize);
      }
      this.thread = new Thread(this, "action-graph-writer");
      this.thread.start();
    }

    private static final class ActionDumpQueueFullException extends RuntimeException {
      ActionDumpQueueFullException(long blockedMs) {
        super("Action dump queue was full and put() blocked for " + blockedMs + "ms.");
      }
    }

    void enqueue(byte[] entry) {
      if (queue.offer(entry)) {
        return;
      }
      Stopwatch sw = Stopwatch.createStarted();
      try {
        queue.put(entry);
      } catch (InterruptedException e) {
        logger.atWarning().atMostEvery(10, SECONDS).withCause(e).log(
            "Interrupted while trying to put to queue");
        Thread.currentThread().interrupt();
      }
      blockedMillis.addAndGet(sw.elapsed().toMillis());
    }

    public void enqueue(SpawnExecutedEvent event) {
      enqueue(toProto(event).toByteArray());
    }

    void shutdown(BuildToolLogCollection logs) throws InterruptedException {
      enqueue(INVOCATION_COMPLETED);
      long blockedMs = blockedMillis.get();
      if (blockedMs > 100) {
        BugReport.sendBugReport(new ActionDumpQueueFullException(blockedMs));
      }
      thread.join();
      if (logs != null) {
        updateLogs(logs);
      }
    }

    protected abstract void updateLogs(BuildToolLogCollection logs);

    /** Test hook to allow injecting failures in tests. */
    @VisibleForTesting
    ZstdOutputStream createCompressingOutputStream() throws IOException {
      // zstd compression at the default level produces 20% smaller outputs than gzip, while being
      // faster to compress and decompress. Higher levels get slower quickly, without much benefit
      // in size. For example, level 4 produces 1% smaller outputs, but takes twice as long to
      // compress in standalone benchmarks. Lower levels quickly increase size, without much benefit
      // in speed. For example, level -3 produces 60% bigger outputs, but only runs 10% faster in
      // standalone benchmarks.
      return new ZstdOutputStream(outStream);
    }

    /**
     * Saves all gathered information from taskQueue queue to the file. Method is invoked internally
     * by the Timer-based thread and at the end of profiling session.
     */
    @Override
    public void run() {
      try {
        // Track when we receive the last entry in case there's a failure in the implied #close()
        // call on the OutputStream.
        boolean receivedLastEntry = false;
        try (OutputStream out = createCompressingOutputStream()) {
          CodedOutputStream codedOut = CodedOutputStream.newInstance(out, OUTPUT_BUFFER_SIZE);
          byte[] data;
          while ((data = queue.take()) != INVOCATION_COMPLETED) {
            codedOut.writeByteArrayNoTag(data);
          }
          receivedLastEntry = true;
          codedOut.flush();
        } catch (IOException e) {
          // Fixing b/117951060 should mitigate, but may happen regardless.
          logger.atWarning().withCause(e).log("Failure writing action dump");
          if (!receivedLastEntry) {
            while (queue.take() != INVOCATION_COMPLETED) {
              // We keep emptying the queue to avoid OOMs or blocking, but we can't write anything.
            }
          }
        }
      } catch (InterruptedException e) {
        // This thread exits immediately, so there's nothing checking this bit. Just exit silently.
        Thread.currentThread().interrupt();
      }
    }
  }

  private static BuildEventArtifactUploader newUploader(
      CommandEnvironment env, BuildEventProtocolOptions bepOptions)
      throws InvalidPackagePathSymlinkException {
    return env.getRuntime()
        .getBuildEventArtifactUploaderFactoryMap()
        .select(bepOptions.buildEventUploadStrategy)
        .create(env);
  }

  private ActionDumpWriter createActionDumpWriter(CommandEnvironment env)
      throws InvalidPackagePathSymlinkException, ActionDumpFileCreationException {
    OptionsParsingResult parsingResult = env.getOptions();
    BuildEventProtocolOptions bepOptions =
        Preconditions.checkNotNull(parsingResult.getOptions(BuildEventProtocolOptions.class));
    ExecutionGraphDumpOptions executionGraphDumpOptions =
        Preconditions.checkNotNull(parsingResult.getOptions(ExecutionGraphDumpOptions.class));
    if (bepOptions.streamingLogFileUploads) {
      return new StreamingActionDumpWriter(
          env.getRuntime().getBugReporter(),
          env.getOptions().getOptions(LocalExecutionOptions.class).localLockfreeOutput,
          newUploader(env, bepOptions).startUpload(LocalFileType.PERFORMANCE_LOG, null),
          env.getCommandId(),
          executionGraphDumpOptions.depType,
          executionGraphDumpOptions.queueSize);
    }

    Path actionGraphFile =
        env.getWorkingDirectory().getRelative(executionGraphDumpOptions.executionGraphLogFile);
    try {
      return new FilesystemActionDumpWriter(
          env.getRuntime().getBugReporter(),
          env.getOptions().getOptions(LocalExecutionOptions.class).localLockfreeOutput,
          actionGraphFile,
          env.getCommandId(),
          executionGraphDumpOptions.depType,
          executionGraphDumpOptions.queueSize);
    } catch (IOException e) {
      throw new ActionDumpFileCreationException(actionGraphFile, e);
    }
  }

  private static final class FilesystemActionDumpWriter extends ActionDumpWriter {
    private final Path actionGraphFile;

    public FilesystemActionDumpWriter(
        BugReporter bugReporter,
        boolean localLockFreeOutputEnabled,
        Path actionGraphFile,
        UUID uuid,
        DependencyInfo depType,
        int queueSize)
        throws IOException {
      super(
          bugReporter,
          localLockFreeOutputEnabled,
          actionGraphFile.getOutputStream(),
          uuid,
          depType,
          queueSize);
      this.actionGraphFile = actionGraphFile;
    }

    @Override
    protected void updateLogs(BuildToolLogCollection logs) {
      logs.addLocalFile(
          ACTION_DUMP_NAME,
          actionGraphFile,
          LocalFileType.PERFORMANCE_LOG,
          LocalFileCompression.NONE);
    }
  }

  private static FailureDetail makeReportUploaderNeedsPackagePathsDetail() {
    return FailureDetail.newBuilder()
        .setMessage("could not create action dump uploader due to failed package path resolution")
        .setBuildReport(
            BuildReport.newBuilder().setCode(Code.BUILD_REPORT_UPLOADER_NEEDS_PACKAGE_PATHS))
        .build();
  }

  private static FailureDetail makeReportWriteFailedDetail() {
    return FailureDetail.newBuilder()
        .setMessage("could not open action dump file for writing")
        .setBuildReport(
            BuildReport.newBuilder().setCode(Code.BUILD_REPORT_WRITE_FAILED))
        .build();
  }

  private static class StreamingActionDumpWriter extends ActionDumpWriter {
    private final UploadContext uploadContext;

    public StreamingActionDumpWriter(
        BugReporter bugReporter,
        boolean localLockFreeOutputEnabled,
        UploadContext uploadContext,
        UUID commandId,
        DependencyInfo depType,
        int queueSize) {
      super(
          bugReporter,
          localLockFreeOutputEnabled,
          uploadContext.getOutputStream(),
          commandId,
          depType,
          queueSize);
      this.uploadContext = uploadContext;
    }

    @Override
    protected void updateLogs(BuildToolLogCollection logs) {
      logs.addUriFuture(ACTION_DUMP_NAME, uploadContext.uriFuture());
    }
  }

  /** Exception thrown when a FilesystemActionDumpWriter cannot create its output file. */
  private static class ActionDumpFileCreationException extends IOException {
    ActionDumpFileCreationException(Path path, IOException e) {
      super("could not create new action dump file on filesystem at path: " + path, e);
    }
  }
}