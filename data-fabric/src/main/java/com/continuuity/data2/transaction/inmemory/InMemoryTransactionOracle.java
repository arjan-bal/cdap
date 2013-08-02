package com.continuuity.data2.transaction.inmemory;

import com.continuuity.api.common.Bytes;
import com.continuuity.data2.transaction.Transaction;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Maps;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongList;

import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Map;
import java.util.Set;

/**
 *
 */
// todo: synchronize all
// todo: optimize heavily
public class InMemoryTransactionOracle {

  // Minimum size of the excludedList for compaction. This is to avoid compacting on every commit.
  private static final int MIN_EXCLUDE_LIST_SIZE = 1000;

  private static LongArrayList excludedList;

  // todo: clean it up
  // todo: use moving array instead (use Long2ObjectMap<byte[]> in fastutil)
  // tx id (write pointer) -> changes made by this tx
  private static Map<Long, Set<byte[]>> committedChangeSets;
  // not committed yet
  private static Map<Long, Set<byte[]>> committingChangeSets;

  private static long readPointer;

  private static long nextWritePointer;

  // todo: do not use static fields, use proper singleton or smth else
  static {
    reset();
  }

  // public for unit-tests
  public static synchronized void reset() {
    excludedList = new LongArrayList();
    committedChangeSets = Maps.newHashMap();
    committingChangeSets = Maps.newHashMap();
    readPointer = 0;
    nextWritePointer = 1;
  }

  public static synchronized Transaction start() {
    Transaction tx = new Transaction(readPointer, nextWritePointer, getExcludedListAsArray(excludedList));
    excludedList.add(nextWritePointer);
    // it is important to keep it sorted, as client logic may depend on that
    // Using Collections.sort is somewhat inefficient as it convert the elements into Object[] and put it back.
    // todo: optimize the data structure.
    Collections.sort(excludedList);
    nextWritePointer++;
    return tx;
  }

  public static synchronized boolean canCommit(Transaction tx, Collection<byte[]> changeIds) {
    if (hasConflicts(tx, changeIds)) {
      return false;
    }

    // The change set will never get modified. Using a immutable has smaller footprint and could perform better.
    Set<byte[]> set = ImmutableSortedSet.copyOf(Bytes.BYTES_COMPARATOR, changeIds);
    committingChangeSets.put(tx.getWritePointer(), set);

    return true;
  }

  public static synchronized boolean commit(Transaction tx) {
    // todo: these should be atomic
    // NOTE: whether we succeed or not we don't need to keep changes in committing state: same tx cannot be attempted to
    //       commit twice
    Set<byte[]> changeSet = committingChangeSets.remove(tx.getWritePointer());

    if (changeSet != null) {
      // double-checking if there are conflicts: someone may have committed since canCommit check
      if (hasConflicts(tx, changeSet)) {
        return false;
      }

      committedChangeSets.put(tx.getWritePointer(), changeSet);
    }
    makeVisible(tx);
    return true;
  }

  public static synchronized boolean abort(Transaction tx) {
    committingChangeSets.remove(tx.getWritePointer());
    // makes tx visible (assumes that all operations were rolled back)
    makeVisible(tx);
    return true;
  }

  private static boolean hasConflicts(Transaction tx, Collection<byte[]> changeIds) {
    if (changeIds.isEmpty()) {
      return false;
    }

    // Go thru all tx committed after given tx was started and check if any of them has change
    // conflicting with the given
    return hasConflicts(tx, changeIds, committedChangeSets);

    // NOTE: we could try to optimize for some use-cases and also check those being committed for conflicts to
    //       avoid later the cost of rollback. This is very complex, but the cost of rollback is so high that we
    //       can go a bit crazy optimizing stuff around it...
  }

  private static boolean hasConflicts(Transaction tx, Collection<byte[]> changeIds, Map<Long, Set<byte[]>> changeSets) {
    for (Map.Entry<Long, Set<byte[]>> changeSet : changeSets.entrySet()) {
      // basically not visible but committed means "tx committed after given tx was started"
      if (!visible(tx, changeSet.getKey())) {
        if (containsAny(changeSet.getValue(), changeIds)) {
          return true;
        }
      }
    }
    return false;
  }

  private static boolean containsAny(Set<byte[]> set, Collection<byte[]> toSearch) {
    for (byte[] item : toSearch) {
      if (set.contains(item)) {
        return true;
      }
    }

    return false;
  }

  // todo: move to Tx?
  private static boolean visible(Transaction tx, long pointer) {
    if (pointer > tx.getReadPointer()) {
      return false;
    }

    // todo: optimize heavily
    // we rely on array of excludes to be sorted
    return Arrays.binarySearch(tx.getExcludedList(), pointer) < 0;
  }

  private static void makeVisible(Transaction tx) {
    excludedList.remove(tx.getWritePointer());
    // trim is needed as remove of LongArrayList would not shrink the backing array.
    // trim will do nothing if the size of excludedList is smaller than the MIN_EXCLUDE_LIST_SIZE.
    excludedList.trim(MIN_EXCLUDE_LIST_SIZE);

    // moving read pointer
    moveReadPointerIfNeeded(tx.getWritePointer());
  }

  private static void moveReadPointerIfNeeded(long committedWritePointer) {
    if (committedWritePointer > readPointer) {
      readPointer = committedWritePointer;
    }
  }

  private static long[] getExcludedListAsArray(LongList excludedList) {
    // todo: optimize (cache, etc. etc.)
    long[] result = new long[excludedList.size()];
    return excludedList.toArray(result);
  }
}
