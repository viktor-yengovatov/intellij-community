// Copyright 2000-2024 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package org.jetbrains.jps.incremental.fs;

import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.util.Key;
import com.intellij.util.SmartList;
import com.intellij.util.containers.FileHashStrategy;
import com.intellij.util.containers.MultiMap;
import com.intellij.util.io.IOUtil;
import it.unimi.dsi.fastutil.objects.Object2LongOpenCustomHashMap;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.jps.ModuleChunk;
import org.jetbrains.jps.builders.*;
import org.jetbrains.jps.builders.impl.BuildTargetChunk;
import org.jetbrains.jps.incremental.*;
import org.jetbrains.jps.incremental.storage.StampsStorage;
import org.jetbrains.jps.model.JpsModel;

import java.io.DataInputStream;
import java.io.DataOutput;
import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.*;

/**
 * @author Eugene Zhuravlev
 */
public final class BuildFSState {
  public static final int VERSION = 3;
  private static final Logger LOG = Logger.getInstance(BuildFSState.class);
  private static final Key<Set<? extends BuildTarget<?>>> CONTEXT_TARGETS_KEY = Key.create("_fssfate_context_targets_");
  private static final Key<FilesDelta> NEXT_ROUND_DELTA_KEY = Key.create("_next_round_delta_");
  private static final Key<FilesDelta> CURRENT_ROUND_DELTA_KEY = Key.create("_current_round_delta_");

  // when true, will always determine dirty files by scanning FS and comparing timestamps
  // alternatively, when false, after first scan will rely on external notifications about changes
  private final boolean myAlwaysScanFS;
  private final Set<BuildTarget<?>> myInitialScanPerformed = Collections.synchronizedSet(new HashSet<>());
  @SuppressWarnings("SSBasedInspection")
  private final Object2LongOpenCustomHashMap<File> myRegistrationStamps = new Object2LongOpenCustomHashMap<>(FileHashStrategy.INSTANCE);
  private final Map<BuildTarget<?>, FilesDelta> myDeltas = Collections.synchronizedMap(new HashMap<>());

  public BuildFSState(boolean alwaysScanFS) {
    myAlwaysScanFS = alwaysScanFS;
  }

  public void save(DataOutput out) throws IOException {
    MultiMap<BuildTargetType<?>, BuildTarget<?>> targetsByType = new MultiMap<>();
    for (BuildTarget<?> target : myInitialScanPerformed) {
      targetsByType.putValue(target.getTargetType(), target);
    }
    out.writeInt(targetsByType.size());
    for (BuildTargetType<?> type : targetsByType.keySet()) {
      IOUtil.writeString(type.getTypeId(), out);
      Collection<BuildTarget<?>> targets = targetsByType.get(type);
      out.writeInt(targets.size());
      for (BuildTarget<?> target : targets) {
        IOUtil.writeString(target.getId(), out);
        getDelta(target).save(out);
      }
    }
  }

  public void load(DataInputStream in, JpsModel model, final BuildRootIndex buildRootIndex) throws IOException {
    final TargetTypeRegistry registry = TargetTypeRegistry.getInstance();
    int typeCount = in.readInt();
    while (typeCount-- > 0) {
      final String typeId = IOUtil.readString(in);
      int targetCount = in.readInt();
      BuildTargetType<?> type = registry.getTargetType(typeId);
      BuildTargetLoader<?> loader = type != null ? type.createLoader(model) : null;
      while (targetCount-- > 0) {
        final String id = IOUtil.readString(in);
        boolean loaded = false;
        if (loader != null) {
          BuildTarget<?> target = loader.createTarget(id);
          if (target != null) {
            getDelta(target).load(in, target, buildRootIndex);
            myInitialScanPerformed.add(target);
            loaded = true;
          }
        }
        if (!loaded) {
          LOG.info("Skipping unknown target (typeId=" + typeId + ", type=" + type + ", id=" + id + ")");
          FilesDelta.skip(in);
        }
      }
    }
  }

  public void clearRecompile(final BuildRootDescriptor rd) {
    getDelta(rd.getTarget()).clearRecompile(rd);
  }

  public long getEventRegistrationStamp(File file) {
    synchronized (myRegistrationStamps) {
      return myRegistrationStamps.getLong(file);
    }
  }

  public boolean hasWorkToDo(BuildTarget<?> target) {
    if (!myInitialScanPerformed.contains(target)) {
      return true;
    }
    FilesDelta delta = myDeltas.get(target);
    return delta != null && delta.hasChanges();
  }

  /**
   * @return true if there were changed files reported for the specified target, _after_ the target compilation had been started
   */
  public boolean hasUnprocessedChanges(@NotNull CompileContext context, @NotNull BuildTarget<?> target) {
    if (!myInitialScanPerformed.contains(target)) {
      return false;
    }
    final FilesDelta delta = myDeltas.get(target);
    if (delta == null) {
      return false;
    }
    final long targetBuildStart = context.getCompilationStartStamp(target);
    if (targetBuildStart <= 0L) {
      return false;
    }
    final CompileScope scope = context.getScope();
    final BuildRootIndex rootIndex = context.getProjectDescriptor().getBuildRootIndex();
    try {
      delta.lockData();
      final long now = System.currentTimeMillis();
      for (Set<File> files : delta.getSourcesToRecompile().values()) {
        files_loop:
        for (File file : files) {
          final long fileStamp;
          if (getEventRegistrationStamp(file) > targetBuildStart || (fileStamp = FSOperations.lastModified(file)) > targetBuildStart && fileStamp < now) {
            if (scope.isAffected(target, file)) {
              for (BuildRootDescriptor rd : rootIndex.findAllParentDescriptors(file, context)) {
                if (rd.isGenerated()) { // do not send notification for generated sources
                  continue files_loop;
                }
              }
              if (LOG.isDebugEnabled()) {
                LOG.debug("Unprocessed changes detected for target " + target +
                            "; file: " + file.getPath() +
                            "; targetBuildStart=" + targetBuildStart +
                            "; eventRegistrationStamp=" + getEventRegistrationStamp(file) +
                            "; lastModified=" + FSOperations.lastModified(file)
                );
              }
              return true;
            }
          }
        }
      }
    }
    finally {
      delta.unlockData();
    }
    return false;
  }

  public void markInitialScanPerformed(BuildTarget<?> target) {
    myInitialScanPerformed.add(target);
  }

  public void registerDeleted(@Nullable CompileContext context,
                              BuildTarget<?> target,
                              File file,
                              @Nullable StampsStorage<? extends StampsStorage.Stamp> stampStorage) throws IOException {
    registerDeleted(context, target, file);
    if (stampStorage != null) {
      stampStorage.removeStamp(file, target);
    }
  }

  public void registerDeleted(@Nullable CompileContext context, BuildTarget<?> target, final File file) {
    final FilesDelta currentDelta = getRoundDelta(CURRENT_ROUND_DELTA_KEY, context);
    if (currentDelta != null) {
      currentDelta.addDeleted(file);
    }
    final FilesDelta nextDelta = getRoundDelta(NEXT_ROUND_DELTA_KEY, context);
    if (nextDelta != null) {
      nextDelta.addDeleted(file);
    }
    getDelta(target).addDeleted(file);
  }

  public void clearDeletedPaths(BuildTarget<?> target) {
    final FilesDelta delta = myDeltas.get(target);
    if (delta != null) {
      delta.clearDeletedPaths();
    }
  }

  public Collection<String> getAndClearDeletedPaths(BuildTarget<?> target) {
    final FilesDelta delta = myDeltas.get(target);
    if (delta != null) {
      return delta.getAndClearDeletedPaths();
    }
    return Collections.emptyList();
  }

  private @NotNull FilesDelta getDelta(BuildTarget<?> buildTarget) {
    synchronized (myDeltas) {
      FilesDelta delta = myDeltas.get(buildTarget);
      if (delta == null) {
        delta = new FilesDelta();
        myDeltas.put(buildTarget, delta);
      }
      return delta;
    }
  }


  public boolean isInitialScanPerformed(BuildTarget<?> target) {
    return !myAlwaysScanFS && myInitialScanPerformed.contains(target);
  }

  @ApiStatus.Internal
  public @NotNull FilesDelta getEffectiveFilesDelta(@NotNull CompileContext context, BuildTarget<?> target) {
    if (target instanceof ModuleBuildTarget) {
      // multiple compilation rounds are applicable to ModuleBuildTarget only
      final FilesDelta lastRoundDelta = getRoundDelta(CURRENT_ROUND_DELTA_KEY, context);
      if (lastRoundDelta != null) {
        return lastRoundDelta;
      }
    }
    return getDelta(target);
  }

  public boolean isMarkedForRecompilation(@Nullable CompileContext context, CompilationRound round, BuildRootDescriptor rd, File file) {
    FilesDelta delta = getRoundDelta(round == CompilationRound.NEXT? NEXT_ROUND_DELTA_KEY : CURRENT_ROUND_DELTA_KEY, context);
    if (delta == null) {
      delta = getDelta(rd.getTarget());
    }

    return delta.isMarkedRecompile(rd, file);
  }

  /**
   * Note: marked file will well be visible as "dirty" only on the next compilation round!
   */
  public boolean markDirty(@Nullable CompileContext context,
                           File file,
                           final BuildRootDescriptor rd,
                           @Nullable StampsStorage<? extends StampsStorage.Stamp> stampStorage,
                           boolean saveEventStamp) throws IOException {
    return markDirty(context, CompilationRound.NEXT, file, rd, stampStorage, saveEventStamp);
  }

  public boolean markDirty(@Nullable CompileContext context,
                           CompilationRound round,
                           File file,
                           final BuildRootDescriptor rd,
                           @Nullable StampsStorage<? extends StampsStorage.Stamp> stampStorage,
                           boolean saveEventStamp) throws IOException {
    final FilesDelta roundDelta = getRoundDelta(round == CompilationRound.NEXT? NEXT_ROUND_DELTA_KEY : CURRENT_ROUND_DELTA_KEY, context);
    if (roundDelta != null && isInCurrentContextTargets(context, rd)) {
      roundDelta.markRecompile(rd, file);
    }

    final FilesDelta filesDelta = getDelta(rd.getTarget());
    filesDelta.lockData();
    try {
      final boolean marked = filesDelta.markRecompile(rd, file);
      if (marked) {
        if (LOG.isDebugEnabled()) {
          LOG.debug(rd.getTarget() + ": MARKED DIRTY: " + file.getPath());
        }
        if (saveEventStamp) {
          final long eventStamp = System.currentTimeMillis();
          synchronized (myRegistrationStamps) {
            myRegistrationStamps.put(file, eventStamp);
          }
        }
        if (stampStorage != null) {
          stampStorage.removeStamp(file, rd.getTarget());
        }
      }
      else {
        if (LOG.isDebugEnabled()) {
          LOG.debug(rd.getTarget() + ": NOT MARKED DIRTY: " + file.getPath());
        }
      }
      return marked;
    }
    finally {
     filesDelta.unlockData();
    }
  }

  private static boolean isInCurrentContextTargets(CompileContext context, BuildRootDescriptor rd) {
    if (context == null) {
      return false;
    }
    Set<? extends BuildTarget<?>> targets = CONTEXT_TARGETS_KEY.get(context, Collections.emptySet());
    return targets.contains(rd.getTarget());
  }

  public boolean markDirtyIfNotDeleted(@Nullable CompileContext context,
                                       CompilationRound round,
                                       File file,
                                       final BuildRootDescriptor rd,
                                       @Nullable StampsStorage<? extends StampsStorage.Stamp> stampStorage) throws IOException {
    final boolean marked = getDelta(rd.getTarget()).markRecompileIfNotDeleted(rd, file);
    if (marked && stampStorage != null) {
      stampStorage.removeStamp(file, rd.getTarget());
    }
    if (marked) {
      final FilesDelta roundDelta = getRoundDelta(round == CompilationRound.NEXT? NEXT_ROUND_DELTA_KEY : CURRENT_ROUND_DELTA_KEY, context);
      if (roundDelta != null) {
        if (isInCurrentContextTargets(context, rd)) {
          roundDelta.markRecompile(rd, file);
        }
      }
    }
    return marked;
  }

  public void clearAll() {
    clearContextRoundData(null);
    clearContextChunk(null);
    myInitialScanPerformed.clear();
    myDeltas.clear();
    synchronized (myRegistrationStamps) {
      myRegistrationStamps.clear();
    }
  }

  public void clearContextRoundData(@Nullable CompileContext context) {
    setRoundDelta(NEXT_ROUND_DELTA_KEY, context, null);
    setRoundDelta(CURRENT_ROUND_DELTA_KEY, context, null);
  }

  public void clearContextChunk(@Nullable CompileContext context) {
    setContextTargets(context, null);
  }

  public void beforeChunkBuildStart(@NotNull CompileContext context, BuildTargetChunk chunk) {
    setContextTargets(context, chunk.getTargets());
  }

  public void beforeNextRoundStart(@NotNull CompileContext context, ModuleChunk chunk) {
    FilesDelta currentDelta = getRoundDelta(NEXT_ROUND_DELTA_KEY, context);
    if (currentDelta == null) {
      // this is the initial round.
      // Need to make a snapshot of the FS state so that all builders in the chain see the same picture
      final List<FilesDelta> deltas = new SmartList<>();
      for (ModuleBuildTarget target : chunk.getTargets()) {
        deltas.add(getDelta(target));
      }
      currentDelta = new FilesDelta(deltas);
    }
    setRoundDelta(CURRENT_ROUND_DELTA_KEY, context, currentDelta);
    setRoundDelta(NEXT_ROUND_DELTA_KEY, context, new FilesDelta());
  }

  public <R extends BuildRootDescriptor, T extends BuildTarget<R>> boolean processFilesToRecompile(CompileContext context, final @NotNull T target, final FileProcessor<R, T> processor) throws IOException {
    final CompileScope scope = context.getScope();
    final FilesDelta delta = getEffectiveFilesDelta(context, target);
    delta.lockData();
    try {
      for (Map.Entry<BuildRootDescriptor, Set<File>> entry : delta.getSourcesToRecompile().entrySet()) {
        //noinspection unchecked
        R root = (R)entry.getKey();
        if (!target.equals(root.getTarget())) {
          // the data can contain roots from other targets (e.g. when compiling module cycles)
          continue;
        }
        for (File file : entry.getValue()) {
          if (!scope.isAffected(target, file)) {
            continue;
          }
          if (!processor.apply(target, file, root)) {
            return false;
          }
        }
      }
      return true;
    }
    finally {
      delta.unlockData();
    }
  }

  /**
   * @return true if marked something, false otherwise
   */
  public boolean markAllUpToDate(CompileContext context, final BuildRootDescriptor rd, final StampsStorage stampsStorage) throws IOException {
    boolean marked = false;
    final BuildTarget<?> target = rd.getTarget();
    final FilesDelta delta = getDelta(target);
    final long targetBuildStartStamp = context.getCompilationStartStamp(target);
    // prevent modifications to the data structure from external FS events
    delta.lockData();
    try {
      final Set<File> files = delta.clearRecompile(rd);
      if (files != null) {
        CompileScope scope = context.getScope();
        for (File file : files) {
          if (scope.isAffected(target, file)) {
            Path nioFile = file.toPath();
            long currentFileTimestamp = FSOperations.lastModified(nioFile);
            StampsStorage.Stamp stamp = stampsStorage.getCurrentStamp(nioFile);
            if (!rd.isGenerated() && (currentFileTimestamp > targetBuildStartStamp || getEventRegistrationStamp(file) > targetBuildStartStamp)) {
              // if the file was modified after the compilation had started,
              // do not save the stamp considering file dirty
              // Important!
              // Event registration stamp check is essential for the files that were actually changed _before_ targetBuildStart,
              // but the corresponding change event was received and processed _after_ targetBuildStart
              if (Utils.IS_TEST_MODE) {
                LOG.info("Timestamp after compilation started; marking dirty again: " + file.getPath());
              }
              delta.markRecompile(rd, file);
            }
            else {
              marked = true;
              stampsStorage.saveStamp(file, target, stamp); // todo: ask jeka
            }
          }
          else {
            if (Utils.IS_TEST_MODE) {
              LOG.info("Not affected by compile scope; marking dirty again: " + file.getPath());
            }
            delta.markRecompile(rd, file);
          }
        }
      }
      return marked;
    }
    finally {
      delta.unlockData();
    }
  }

  private static void setContextTargets(@Nullable CompileContext context, @Nullable Set<? extends BuildTarget<?>> targets) {
    if (context != null) {
      CONTEXT_TARGETS_KEY.set(context, targets);
    }
  }

  private static @Nullable FilesDelta getRoundDelta(@NotNull Key<FilesDelta> key, @Nullable CompileContext context) {
    return context != null? key.get(context) : null;
  }

  private static void setRoundDelta(@NotNull Key<FilesDelta> key, @Nullable CompileContext context, @Nullable FilesDelta delta) {
    if (context != null) {
      key.set(context, delta);
    }
  }
}
