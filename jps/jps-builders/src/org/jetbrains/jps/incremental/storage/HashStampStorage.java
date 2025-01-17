// Copyright 2000-2024 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package org.jetbrains.jps.incremental.storage;

import com.intellij.util.ArrayUtil;
import com.intellij.util.io.DataExternalizer;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.jps.builders.BuildTarget;
import org.jetbrains.jps.incremental.relativizer.PathRelativizerService;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;

import static org.jetbrains.jps.incremental.FileHashUtilKt.getFileHash;
import static org.jetbrains.jps.incremental.storage.FileTimestampStorage.FileTimestamp;
import static org.jetbrains.jps.incremental.storage.HashStampStorage.HashStampPerTarget;

final class HashStampStorage extends AbstractStateStorage<String, HashStampPerTarget[]> implements StampsStorage<HashStampStorage.HashStamp> {
  private final FileTimestampStorage myTimestampStorage;
  private final PathRelativizerService myRelativizer;
  private final BuildTargetsState myTargetsState;
  private final File myFileStampRoot;

  HashStampStorage(File dataStorageRoot, PathRelativizerService relativizer, BuildTargetsState targetsState) throws IOException {
    super(new File(calcStorageRoot(dataStorageRoot), "data"), PathStringDescriptor.INSTANCE, new StateExternalizer());
    myTimestampStorage = new FileTimestampStorage(dataStorageRoot, targetsState);
    myFileStampRoot = calcStorageRoot(dataStorageRoot);
    myRelativizer = relativizer;
    myTargetsState = targetsState;
  }

  private @NotNull String relativePath(@NotNull File file) {
    return myRelativizer.toRelative(file.getAbsolutePath());
  }

  private static @NotNull File calcStorageRoot(File dataStorageRoot) {
    return new File(dataStorageRoot, "hashes");
  }

  @Override
  public File getStorageRoot() {
    return myFileStampRoot;
  }

  @Override
  public void saveStamp(File file, BuildTarget<?> buildTarget, HashStamp stamp) throws IOException {
    myTimestampStorage.saveStamp(file, buildTarget, FileTimestamp.fromLong(stamp.myTimestamp));
    int targetId = myTargetsState.getBuildTargetId(buildTarget);
    String path = relativePath(file);
    update(path, updateFilesStamp(getState(path), targetId, stamp));
  }

  private static HashStampPerTarget @NotNull [] updateFilesStamp(HashStampPerTarget[] oldState, final int targetId, HashStamp stamp) {
    final HashStampPerTarget newItem = new HashStampPerTarget(targetId, stamp.myHash);
    if (oldState == null) {
      return new HashStampPerTarget[]{newItem};
    }
    for (int i = 0, length = oldState.length; i < length; i++) {
      if (oldState[i].targetId == targetId) {
        oldState[i] = newItem;
        return oldState;
      }
    }
    return ArrayUtil.append(oldState, newItem);
  }

  @Override
  public void removeStamp(File file, BuildTarget<?> buildTarget) throws IOException {
    myTimestampStorage.removeStamp(file, buildTarget);
    String path = relativePath(file);
    HashStampPerTarget[] state = getState(path);
    if (state != null) {
      int targetId = myTargetsState.getBuildTargetId(buildTarget);
      for (int i = 0; i < state.length; i++) {
        if (state[i].targetId == targetId) {
          if (state.length == 1) {
            remove(path);
          }
          else {
            HashStampPerTarget[] newState = ArrayUtil.remove(state, i);
            update(path, newState);
            break;
          }
        }
      }
    }
  }

  @Override
  public HashStamp getPreviousStamp(File file, BuildTarget<?> target) throws IOException {
    FileTimestamp previousTimestamp = myTimestampStorage.getPreviousStamp(file, target);
    HashStampPerTarget[] state = getState(relativePath(file));
    if (state != null) {
      int targetId = myTargetsState.getBuildTargetId(target);
      for (HashStampPerTarget filesStampPerTarget : state) {
        if (filesStampPerTarget.targetId == targetId) {
          return new HashStamp(filesStampPerTarget.hash, previousTimestamp.asLong());
        }
      }
    }
    return HashStamp.EMPTY;
  }

  public Long getStoredFileHash(File file, BuildTarget<?> target) throws IOException {
    HashStampPerTarget[] state = getState(relativePath(file));
    if (state == null) {
      return null;
    }

    int targetId = myTargetsState.getBuildTargetId(target);
    for (HashStampPerTarget filesStampPerTarget : state) {
      if (filesStampPerTarget.targetId == targetId) {
        return filesStampPerTarget.hash;
      }
    }
    return null;
  }

  @Override
  public HashStamp getCurrentStamp(Path file) throws IOException {
    FileTimestamp currentTimestamp = myTimestampStorage.getCurrentStamp(file);
    return new HashStamp(getFileHash(file), currentTimestamp.asLong());
  }

  @Override
  public boolean isDirtyStamp(@NotNull Stamp stamp, File file) throws IOException {
    if (!(stamp instanceof HashStamp)) {
      return true;
    }

    HashStamp filesStamp = (HashStamp)stamp;
    if (!myTimestampStorage.isDirtyStamp(FileTimestamp.fromLong(filesStamp.myTimestamp), file)) {
      return false;
    }

    Long hash = filesStamp.myHash;
    if (hash == null) {
      return true;
    }

    return hash != getFileHash(file.toPath());
  }

  @Override
  public boolean isDirtyStamp(Stamp stamp, File file, @NotNull BasicFileAttributes attrs) throws IOException {
    if (!(stamp instanceof HashStamp)) {
      return true;
    }

    HashStamp filesStamp = (HashStamp)stamp;
    if (!myTimestampStorage.isDirtyStamp(FileTimestamp.fromLong(filesStamp.myTimestamp), file, attrs)) {
      return false;
    }

    Long hash = filesStamp.myHash;
    if (hash == null) {
      return true;
    }

    return hash != getFileHash(file.toPath());
  }

  @Override
  public void force() {
    super.force();
    myTimestampStorage.force();
  }

  @Override
  public void clean() throws IOException {
    super.clean();
    myTimestampStorage.clean();
  }

  @Override
  public boolean wipe() {
    return super.wipe() && myTimestampStorage.wipe();
  }

  @Override
  public void close() throws IOException {
    super.close();
    myTimestampStorage.close();
  }

  static final class HashStampPerTarget {
    public final int targetId;
    public final long hash;

    private HashStampPerTarget(int targetId, long hash) {
      this.targetId = targetId;
      this.hash = hash;
    }
  }

  static final class HashStamp implements StampsStorage.Stamp {
    static HashStamp EMPTY = new HashStamp(null, -1L);

    private final Long myHash;
    private final long myTimestamp;

    private HashStamp(Long hash, long timestamp) {
      myHash = hash;
      myTimestamp = timestamp;
    }

    @Override
    public String toString() {
      return "HashStamp{" +
             "myHash=" + myHash +
             ", myTimestamp=" + myTimestamp +
             '}';
    }
  }

  private static final class StateExternalizer implements DataExternalizer<HashStampPerTarget[]> {
    @Override
    public void save(@NotNull DataOutput out, HashStampPerTarget[] value) throws IOException {
      out.writeInt(value.length);
      for (HashStampPerTarget target : value) {
        out.writeInt(target.targetId);
        out.writeLong(target.hash);
      }
    }

    @Override
    public HashStampPerTarget[] read(@NotNull DataInput in) throws IOException {
      int size = in.readInt();
      HashStampPerTarget[] targets = new HashStampPerTarget[size];
      for (int i = 0; i < size; i++) {
        int id = in.readInt();
        long hash = in.readLong();
        targets[i] = new HashStampPerTarget(id, hash);
      }
      return targets;
    }
  }
}
