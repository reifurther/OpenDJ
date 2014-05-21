/*
 * CDDL HEADER START
 *
 * The contents of this file are subject to the terms of the
 * Common Development and Distribution License, Version 1.0 only
 * (the "License").  You may not use this file except in compliance
 * with the License.
 *
 * You can obtain a copy of the license at legal-notices/CDDLv1_0.txt
 * or http://forgerock.org/license/CDDLv1.0.html.
 * See the License for the specific language governing permissions
 * and limitations under the License.
 *
 * When distributing Covered Code, include this CDDL HEADER in each
 * file and include the License file at legal-notices/CDDLv1_0.txt.
 * If applicable, add the following below this CDDL HEADER, with the
 * fields enclosed by brackets "[]" replaced with your own identifying
 * information:
 *      Portions Copyright [yyyy] [name of copyright owner]
 *
 * CDDL HEADER END
 *
 *
 *      Copyright 2014 ForgeRock AS.
 */
package org.opends.server.replication.server.changelog.file;

import static org.opends.server.loggers.debug.DebugLogger.*;
import static org.opends.server.util.StaticUtils.*;
import static org.opends.messages.ReplicationMessages.*;

import java.io.Closeable;
import java.io.File;
import java.io.FileFilter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import org.forgerock.util.Reject;
import org.opends.server.loggers.ErrorLogger;
import org.opends.server.loggers.debug.DebugTracer;
import org.opends.server.replication.server.changelog.api.ChangelogException;
import org.opends.server.replication.server.changelog.api.DBCursor;
import org.opends.server.replication.server.changelog.file.LogFile.LogFileCursor;
import org.opends.server.util.StaticUtils;

import com.forgerock.opendj.util.Pair;

/**
 * A multi-file log that features appending key-value records and reading them
 * using a {@code DBCursor}.
 * The records must be appended to the log in ascending order of the keys.
 * <p>
 * A log is defined for a path - the log path - and contains one to many log files:
 * <ul>
 * <li>it has always at least one log file, the head log file, named "head.log",
 * which is used to append the records.</li>
 * <li>it may have from zero to many read-only log files, which are named after
 * the pattern '[lowkey]_[highkey}.log' where [lowkey] and [highkey] are respectively
 * the string representation of lowest and highest key present in the log file.</li>
 * </ul>
 * A read-only log file is created each time the head log file has reached the
 * maximum size limit. The head log file is then rotated to the read-only file and a
 * new empty head log file is opened. There is no limit on the number of read-only
 * files, but they can be purged.
 * <p>
 * A log is obtained using the {@code Log.openLog()} method and must always be
 * released using the {@code close()} method.
 * <p>
 * Usage example:
 * <pre>
 * {@code
 *   Log<K, V> log = null;
 *   try
 *   {
 *     log = Log.openLog(logPath, parser, maxFileSize);
 *     log.append(key, value);
 *     DBCursor<K, V> cursor = log.getCursor(someKey);
 *     // use cursor, then close cursor
 *   }
 *   finally
 *   {
 *     log.close();
 *   }
 * }
 * </pre>
 *
 * @param <K>
 *          Type of the key of a record, which must be comparable.
 * @param <V>
 *          Type of the value of a record.
 */
final class Log<K extends Comparable<K>, V> implements Closeable
{
  private static final DebugTracer TRACER = getTracer();

  private static final String LOG_FILE_SUFFIX = ".log";

  static final String HEAD_LOG_FILE_NAME = "head" + LOG_FILE_SUFFIX;

  private static final String LOG_FILE_NAME_SEPARATOR = "_";

  private static final FileFilter READ_ONLY_LOG_FILES_FILTER = new FileFilter()
  {
    @Override
    public boolean accept(File file)
    {
      return file.isFile() && file.getName().endsWith(LOG_FILE_SUFFIX) &&
          !file.getName().equals(HEAD_LOG_FILE_NAME);
    }
  };

  /** Map that holds the unique log instance for each log path. */
  private static final Map<File, Log<?, ?>> logsCache = new HashMap<File, Log<?, ?>>();

  /**
   * The number of references on this log instance. It is incremented each time
   * a log is opened on the same log path. The log is effectively closed only
   * when the {@code close()} method is called and this value is at most 1.
   */
  private int referenceCount;

  /** The path of directory for this log, where log files are stored. */
  private final File logPath;

  /** The parser used for encoding/decoding of records. */
  private final RecordParser<K, V> recordParser;

  /**
   * Indicates if this log is closed. When the log is closed, all methods return
   * immediately with no effect.
   */
  private boolean isClosed;

  /**
   * The log files contained in this log, ordered by key.
   * <p>
   * The head log file is always present and is associated with the maximum
   * possible key, given by the record parser.
   * <p>
   * The read-only log files are associated with the highest key they contain.
   */
  private final TreeMap<K, LogFile<K, V>> logFiles = new TreeMap<K, LogFile<K, V>>();

  /**
   * The list of non-empty cursors opened on this log. Opened cursors may have
   * to be updated when rotating the head log file.
   */
  private final List<LogCursor<K, V>> openCursors = new CopyOnWriteArrayList<LogCursor<K, V>>();

  /**
   * A log file is rotated once it has exceeded this size limit. The log file can have
   * a size much larger than this limit if the last record written has a huge size.
   *
   * TODO : to be replaced later by a (or a list of) configurable Rotation policy
   * eg, List<RotationPolicy> rotationPolicies = new ArrayList<RotationPolicy>();
   */
  private final long sizeLimitPerLogFileInBytes;

  /**
   * The exclusive lock used for writes and lifecycle operations on this log:
   * initialize, clear, sync and close.
   */
  private final Lock exclusiveLock;

  /**
   * The shared lock used for reads and cursor operations on this log.
   */
  private final Lock sharedLock;

  /**
   * Open a log with the provided log path, record parser and maximum size per
   * log file.
   * <p>
   * If no log exists for the provided path, a new one is created.
   *
   * @param <K>
   *          Type of the key of a record, which must be comparable.
   * @param <V>
   *          Type of the value of a record.
   * @param logPath
   *          Path of the log.
   * @param parser
   *          Parser for encoding/decoding of records.
   * @param sizeLimitPerFileInBytes
   *          Limit in bytes before rotating the head log file of the log.
   * @return a log
   * @throws ChangelogException
   *           If a problem occurs during initialization.
   */
  static synchronized <K extends Comparable<K>, V> Log<K, V> openLog(final File logPath,
      final RecordParser<K, V> parser, final long sizeLimitPerFileInBytes) throws ChangelogException
  {
    Reject.ifNull(logPath, parser);
    @SuppressWarnings("unchecked")
    Log<K, V> log = (Log<K, V>) logsCache.get(logPath);
    if (log == null)
    {
      log = new Log<K, V>(logPath, parser, sizeLimitPerFileInBytes);
      logsCache.put(logPath, log);
    }
    else
    {
      // TODO : check that parser and size limit are compatible with the existing one,
      // and issue a warning if it is not the case
      log.referenceCount++;
    }
    return log;
  }

  /**
   * Release a reference to the log corresponding to provided path. The log is
   * closed if this is the last reference.
   */
  private static synchronized void releaseLog(final File logPath)
  {
    Log<?, ?> log = logsCache.get(logPath);
    if (log == null)
    {
      // this should never happen
      ErrorLogger.logError(
          ERR_CHANGELOG_UNREFERENCED_LOG_WHILE_RELEASING.get(logPath.getPath()));
      return;
    }
    if (log.referenceCount > 1)
    {
      log.referenceCount--;
    }
    else
    {
      log.doClose();
      logsCache.remove(logPath);
    }
  }

  /**
   * Creates a new log.
   *
   * @param logPath
   *            The directory path of the log.
   * @param parser
   *          Parser of records.
   * @param sizeLimitPerFile
   *            Limit in bytes before rotating a log file.
   * @throws ChangelogException
   *            If a problem occurs during initialization.
   */
  private Log(final File logPath, final RecordParser<K, V> parser, final long sizeLimitPerFile)
      throws ChangelogException
  {
    this.logPath = logPath;
    this.recordParser = parser;
    this.sizeLimitPerLogFileInBytes = sizeLimitPerFile;
    this.referenceCount = 1;

    final ReadWriteLock lock = new ReentrantReadWriteLock(false);
    this.exclusiveLock = lock.writeLock();
    this.sharedLock = lock.readLock();

    createOrOpenLogFiles();
  }

  /** Create or open log files used by this log. */
  private void createOrOpenLogFiles() throws ChangelogException
  {
    exclusiveLock.lock();
    try
    {
      createRootDirIfNotExists();
      openHeadLogFile();
      for (final File file : getReadOnlyLogFiles())
      {
        openReadOnlyLogFile(file);
      }
      isClosed = false;
    }
    catch (ChangelogException e)
    {
      // ensure all log files opened at this point are closed
      close();
      throw new ChangelogException(
          ERR_CHANGELOG_UNABLE_TO_INITIALIZE_LOG.get(logPath.getPath()), e);
    }
    finally
    {
      exclusiveLock.unlock();
    }
  }

  private File[] getReadOnlyLogFiles() throws ChangelogException
  {
    File[] files = logPath.listFiles(READ_ONLY_LOG_FILES_FILTER);
    if (files == null)
    {
      throw new ChangelogException(
          ERR_CHANGELOG_UNABLE_TO_RETRIEVE_READ_ONLY_LOG_FILES_LIST.get(logPath.getPath()));
    }
    return files;
  }

  private void createRootDirIfNotExists() throws ChangelogException
  {
    if (!logPath.exists())
    {
      if (!logPath.mkdirs())
      {
        throw new ChangelogException(ERR_CHANGELOG_UNABLE_TO_CREATE_LOG_DIRECTORY.get(logPath.getPath()));
      }
    }
  }

  /**
   * Returns the path of this log.
   *
   * @return the path of this log directory
   */
  public File getPath()
  {
    return logPath;
  }

  /**
   * Add the provided record at the end of this log.
   * <p>
   * In order to ensure that record is written out of buffers and persisted
   * to file system, it is necessary to explicitely call the
   * {@code syncToFileSystem()} method.
   *
   * @param record
   *          The record to add.
   * @throws ChangelogException
   *           If the record can't be added to the log.
   */
  public void append(final Record<K, V> record) throws ChangelogException
  {
    // If this exclusive lock happens to be a bottleneck :
    // 1. use a shared lock for appending the record first
    // 2. switch to an exclusive lock only if rotation is needed
    // See http://sources.forgerock.org/cru/CR-3548#c27521 for full detail
    exclusiveLock.lock();
    try
    {
      if (isClosed)
      {
        return;
      }
      LogFile<K, V> headLogFile = getHeadLogFile();
      if (headLogFile.getSizeInBytes() > sizeLimitPerLogFileInBytes)
      {
        ErrorLogger.logError(INFO_CHANGELOG_LOG_FILE_ROTATION.get(logPath.getPath(), headLogFile.getSizeInBytes()));

        rotateHeadLogFile();
        headLogFile = getHeadLogFile();
      }
      headLogFile.append(record);
    }
    finally
    {
      exclusiveLock.unlock();
    }
  }

  /**
   * Synchronize all records added with the file system, ensuring that records
   * are effectively persisted.
   * <p>
   * After a successful call to this method, it is guaranteed that all records
   * added to the log are persisted to the file system.
   *
   * @throws ChangelogException
   *           If the synchronization fails.
   */
  public void syncToFileSystem() throws ChangelogException
  {
    exclusiveLock.lock();
    try
    {
      getHeadLogFile().syncToFileSystem();
    }
    finally
    {
      exclusiveLock.unlock();
    }
  }

  /**
   * Returns a cursor that allows to retrieve the records from this log,
   * starting at the first position.
   * <p>
   * The returned cursor initially points to record corresponding to the first
   * key, that is {@code cursor.getRecord()} is equals to the record
   * corresponding to the first key before any call to {@code cursor.next()}
   * method.
   *
   * @return a cursor on the log records, which is never {@code null}
   * @throws ChangelogException
   *           If the cursor can't be created.
   */
  public RepositionableCursor<K, V> getCursor() throws ChangelogException
  {
    LogCursor<K, V> cursor = null;
    sharedLock.lock();
    try
    {
      if (isClosed)
      {
        return new EmptyLogCursor<K, V>();
      }
      cursor = new LogCursor<K, V>(this);
      cursor.positionTo(null, false);
      registerCursor(cursor);
      return cursor;
    }
    catch (ChangelogException e)
    {
      StaticUtils.close(cursor);
      throw e;
    }
    finally
    {
      sharedLock.unlock();
    }
  }

  /**
   * Returns a cursor that allows to retrieve the records from this log,
   * starting at the position defined by the provided key.
   * <p>
   * The returned cursor initially points to record corresponding to the key,
   * that is {@code cursor.getRecord()} is equals to the record corresponding to
   * the key before any call to {@code cursor.next()} method.
   *
   * @param key
   *          Key to use as a start position for the cursor. If key is
   *          {@code null}, cursor will point at the first record of the log.
   * @return a cursor on the log records, which is never {@code null}
   * @throws ChangelogException
   *           If the cursor can't be created.
   */
  public RepositionableCursor<K, V> getCursor(final K key) throws ChangelogException
  {
    return getCursor(key, false);
  }

  /**
   * Returns a cursor that allows to retrieve the records from this log,
   * starting at the position defined by the smallest key that is higher than
   * the provided key.
   * <p>
   * The returned cursor initially points to record corresponding to the key
   * found, that is {@code cursor.getRecord()} is equals to the record
   * corresponding to the key found before any call to {@code cursor.next()}
   * method.
   *
   * @param key
   *          Key to use as a start position for the cursor. If key is
   *          {@code null}, cursor will point at the first record of the log.
   * @return a cursor on the log records, which is never {@code null}
   * @throws ChangelogException
   *           If the cursor can't be created.
   */
  public RepositionableCursor<K, V> getNearestCursor(final K key) throws ChangelogException
  {
    return getCursor(key, true);
  }

  /** Returns a cursor starting from a key, using the strategy corresponding to provided boolean. */
  private RepositionableCursor<K, V> getCursor(final K key, boolean findNearest) throws ChangelogException
  {
    if (key == null)
    {
      return getCursor();
    }
    LogCursor<K, V> cursor = null;
    sharedLock.lock();
    try
    {
      if (isClosed)
      {
        return new EmptyLogCursor<K, V>();
      }
      cursor = new LogCursor<K, V>(this);
      final boolean isFound = cursor.positionTo(key, findNearest);
      // for nearest case, it is ok if the target is not found
      if (isFound || findNearest)
      {
        registerCursor(cursor);
        return cursor;
      }
      else
      {
        StaticUtils.close(cursor);
        return new EmptyLogCursor<K, V>();
      }
    }
    catch (ChangelogException e)
    {
      StaticUtils.close(cursor);
      throw e;
    }
    finally
    {
      sharedLock.unlock();
    }
  }


  /**
   * Returns the oldest (first) record from this log.
   *
   * @return the oldest record, which may be {@code null} if there is no record
   *         in the log.
   * @throws ChangelogException
   *           If an error occurs while retrieving the record.
   */
  public Record<K, V> getOldestRecord() throws ChangelogException
  {
    return getOldestLogFile().getOldestRecord();
  }


  /**
   * Returns the newest (last) record from this log.
   *
   * @return the newest record, which may be {@code null}
   * @throws ChangelogException
   *           If an error occurs while retrieving the record.
   */
  public Record<K, V> getNewestRecord() throws ChangelogException
  {
    return getHeadLogFile().getNewestRecord();
  }

  /**
   * Returns the number of records in the log.
   *
   * @return the number of records
   * @throws ChangelogException
   *            If a problem occurs.
   */
  public long getNumberOfRecords() throws ChangelogException
  {
    long count = 0;
    for (final LogFile<K, V> logFile : logFiles.values())
    {
      count += logFile.getNumberOfRecords();
    }
    return count;
  }

  /**
   * Purge the log up to and excluding the provided key.
   *
   * @param purgeKey
   *            the key up to which purging must happen
   * @return the oldest non purged record, or {@code null}
   *         if no record was purged
   * @throws ChangelogException
   *           if a database problem occurs.
   */
  public Record<K,V> purgeUpTo(final K purgeKey) throws ChangelogException
  {
    exclusiveLock.lock();
    try
    {
      if (isClosed)
      {
        return null;
      }
      final SortedMap<K, LogFile<K, V>> logFilesToPurge = logFiles.headMap(purgeKey);
      if (logFilesToPurge.isEmpty())
      {
        return null;
      }
      final List<String> undeletableFiles = new ArrayList<String>();
      final Iterator<Entry<K, LogFile<K, V>>> entriesToPurge = logFilesToPurge.entrySet().iterator();
      while (entriesToPurge.hasNext())
      {
        final LogFile<K, V> logFile = entriesToPurge.next().getValue();
        try
        {
          logFile.close();
          logFile.delete();
          entriesToPurge.remove();
        }
        catch (ChangelogException e)
        {
          // The deletion of log file on file system has failed
          undeletableFiles.add(logFile.getFile().getPath());
        }
      }
      if (!undeletableFiles.isEmpty())
      {
        throw new ChangelogException(
            ERR_CHANGELOG_UNABLE_TO_DELETE_LOG_FILE_WHILE_PURGING.get(
                StaticUtils.listToString(undeletableFiles, ", ")));
      }
      return getOldestRecord();
    }
    finally
    {
      exclusiveLock.unlock();
    }

  }

  /**
   * Empties the log, discarding all records it contains.
   *
   * @throws ChangelogException
   *           If cursors are opened on this log, or if a problem occurs during
   *           clearing operation.
   */
  public void clear() throws ChangelogException
  {
    exclusiveLock.lock();
    try
    {
      if (isClosed)
      {
        return;
      }
      if (!openCursors.isEmpty())
      {
        // TODO: throwing an exception makes the replication/totalupdate.txt robot functional test fail because
        // there is one open cursor when clearing.
        // Switch to logging until this issue is solved
        // throw new ChangelogException(Message.raw("Can't clean log '%s' because there are %d cursor(s) opened on it",
        //    logPath.getPath(), openCursors.size()));
        ErrorLogger.logError(
            ERR_CHANGELOG_CURSOR_OPENED_WHILE_CLEANING_LOG.get(logPath.getPath(), openCursors.size()));
      }

      // delete all log files
      final List<String> undeletableFiles = new ArrayList<String>();
      for (LogFile<K, V> logFile : logFiles.values())
      {
        try
        {
          logFile.close();
          logFile.delete();
        }
        catch (ChangelogException e)
        {
          undeletableFiles.add(logFile.getFile().getPath());
        }
      }
      if (!undeletableFiles.isEmpty())
      {
        throw new ChangelogException(ERR_CHANGELOG_UNABLE_TO_DELETE_LOG_FILE.get(
            StaticUtils.listToString(undeletableFiles, ", ")));
      }
      logFiles.clear();

      // recreate an empty head log file
      openHeadLogFile();
    }
    catch (Exception e)
    {
      throw new ChangelogException(ERR_ERROR_CLEARING_DB.get(logPath.getPath(), stackTraceToSingleLineString(e)));
    }
    finally
    {
      exclusiveLock.unlock();
    }
  }

  /** {@inheritDoc} */
  @Override
  public void close()
  {
    releaseLog(logPath);
  }

  /** Effectively close this log. */
  private void doClose()
  {
    exclusiveLock.lock();
    try
    {
      if (isClosed)
      {
        return;
      }
      if (!openCursors.isEmpty())
      {
        ErrorLogger.logError(
            ERR_CHANGELOG_CURSOR_OPENED_WHILE_CLOSING_LOG.get(logPath.getPath(), openCursors.size()));
      }
      StaticUtils.close(logFiles.values());
      isClosed = true;
    }
    finally
    {
      exclusiveLock.unlock();
    }
  }

  private LogFile<K, V> getHeadLogFile()
  {
    return logFiles.lastEntry().getValue();
  }

  private LogFile<K, V> getOldestLogFile()
  {
    return logFiles.firstEntry().getValue();
  }

  /** Rotate the head log file to a read-only log file, and open a new empty head log file to write in. */
  private void rotateHeadLogFile() throws ChangelogException
  {
    final LogFile<K, V> headLogFile = getHeadLogFile();
    final File readOnlyLogFile = new File(logPath, generateReadOnlyFileName(headLogFile));
    headLogFile.close();
    renameHeadLogFileTo(readOnlyLogFile);

    openHeadLogFile();
    openReadOnlyLogFile(readOnlyLogFile);
    updateCursorsOpenedOnHead();
  }

  private void renameHeadLogFileTo(final File rotatedLogFile) throws ChangelogException
  {
    final File headLogFile = new File(logPath, HEAD_LOG_FILE_NAME);
    try
    {
      StaticUtils.renameFile(headLogFile, rotatedLogFile);
    }
    catch (IOException e)
    {
      throw new ChangelogException(
          ERR_CHANGELOG_UNABLE_TO_RENAME_HEAD_LOG_FILE.get(HEAD_LOG_FILE_NAME, rotatedLogFile.getPath()), e);
    }
  }

  /**
   * Returns the key bounds for the provided log file.
   *
   * @return the pair of (lowest key, highest key) that correspond to records
   *         stored in the corresponding log file.
   * @throws ChangelogException
   *            if an error occurs while retrieving the keys
   */
   private Pair<K, K> getKeyBounds(final LogFile<K, V> logFile) throws ChangelogException
   {
     try
     {
       final String name = logFile.getFile().getName();
       final String[] keys = name.substring(0, name.length() - Log.LOG_FILE_SUFFIX.length())
           .split(LOG_FILE_NAME_SEPARATOR);
       return Pair.of(recordParser.decodeKeyFromString(keys[0]), recordParser.decodeKeyFromString(keys[1]));
     }
     catch (Exception e)
     {
       throw new ChangelogException(
           ERR_CHANGELOG_UNABLE_TO_RETRIEVE_KEY_BOUNDS_FROM_FILE.get(logFile.getFile().getPath()), e);
     }
   }

   /**
    * Returns the file name to use for the read-only version of the provided
    * log file.
    * <p>
    * The file name is based on the lowest and highest key in the log file.
    *
    * @return the name to use for the read-only version of the log file
    * @throws ChangelogException
    *            If an error occurs.
    */
  private String generateReadOnlyFileName(final LogFile<K,V> logFile) throws ChangelogException
  {
    final K lowestKey = logFile.getOldestRecord().getKey();
    final K highestKey = logFile.getNewestRecord().getKey();
    return recordParser.encodeKeyToString(lowestKey) + LOG_FILE_NAME_SEPARATOR
       + recordParser.encodeKeyToString(highestKey) + LOG_FILE_SUFFIX;
  }

  /** Update the open cursors after a rotation of the head log file. */
  private void updateCursorsOpenedOnHead() throws ChangelogException
  {
    for (LogCursor<K, V> cursor : openCursors)
    {
      final CursorState<K, V> state = cursor.getState();
      // Need to update the cursor only if it is pointing to the head log file
      if (isHeadLogFile(state.logFile))
      {
        updateOpenCursor(cursor, state);
      }
    }
  }

  /**
   * Update the provided open cursor with the provided state.
   * <p>
   * The cursor must report the previous state on the head log file to the same
   * state (position in file, current record) in the read-only log file just created.
   */
  private void updateOpenCursor(final LogCursor<K,V> cursor, final CursorState<K, V> state) throws ChangelogException
  {
    final K previousKey = logFiles.lowerKey(recordParser.getMaxKey());
    final LogFile<K, V> logFile = findLogFileFor(previousKey);
    cursor.reinitializeTo(new CursorState<K, V>(logFile, state.filePosition, state.record));
  }

  private void openHeadLogFile() throws ChangelogException
  {
    logFiles.put(recordParser.getMaxKey(),
        LogFile.newAppendableLogFile(new File(logPath,  HEAD_LOG_FILE_NAME), recordParser));
  }

  private void openReadOnlyLogFile(final File logFilePath) throws ChangelogException
  {
    final LogFile<K, V> logFile = LogFile.newReadOnlyLogFile(logFilePath, recordParser);
    final Pair<K, K> bounds = getKeyBounds(logFile);
    logFiles.put(bounds.getSecond(), logFile);
  }

  private void registerCursor(final LogCursor<K, V> cursor)
  {
    openCursors.add(cursor);
  }

  private void unregisterCursor(final LogCursor<K, V> cursor)
  {
    openCursors.remove(cursor);
  }

  /**
   * Returns the log file that is just after the provided log file wrt the order
   * defined on keys, or {@code null} if the provided log file is the last one
   * (the head log file).
   */
  private LogFile<K, V> getNextLogFile(final LogFile<K, V> currentLogFile) throws ChangelogException
  {
    if (isHeadLogFile(currentLogFile))
    {
      return null;
    }
    final Pair<K, K> bounds = getKeyBounds(currentLogFile);
    return logFiles.higherEntry(bounds.getSecond()).getValue();
  }

  private boolean isHeadLogFile(final LogFile<K, V> logFile)
  {
    return logFile.getFile().getName().equals(Log.HEAD_LOG_FILE_NAME);
  }

  /** Returns the log file that should contain the provided key. */
  private LogFile<K, V> findLogFileFor(final K key)
  {
    if (key == null || logFiles.lowerKey(key) == null)
    {
      return getOldestLogFile();
    }
    return logFiles.ceilingEntry(key).getValue();
  }

  /**
   * Represents a cursor than can be repositioned on a given key.
   */
  static interface RepositionableCursor<K extends Comparable<K>,V> extends DBCursor<Record<K, V>>
  {
    /**
     * Position the cursor to the record corresponding to the provided key or to
     * the nearest key (the lowest key higher than the provided key).
     *
     * @param key
     *          Key to use as a start position for the cursor. If key is
     *          {@code null}, use the key of the first record instead.
     * @param findNearest
     *          If {@code true}, start position is the lowest key that is higher
     *          than the provided key, otherwise start position is the provided
     *          key.
     * @return {@code true} if cursor is successfully positionned to the key or
     *         the nearest key, {@code false} otherwise.
     * @throws ChangelogException
     *           If an error occurs when positioning cursor.
     */
    boolean positionTo(K key, boolean findNearest) throws ChangelogException;
  }

  /**
   * Implements a cursor on the log.
   * <p>
   * The cursor initially points to a record, that is {@code cursor.getRecord()}
   * is equals to the first record available from the cursor before any call to
   * {@code cursor.next()} method.
   * <p>
   * The cursor uses the log shared lock to ensure reads are not done during a rotation.
   */
  private static class LogCursor<K extends Comparable<K>, V> implements RepositionableCursor<K, V>
  {
    private final Log<K, V> log;

    private LogFile<K, V> currentLogFile;
    private LogFileCursor<K, V> currentCursor;

    /**
     * Creates a cursor on the provided log.
     *
     * @param log
     *           The log on which the cursor read records.
     * @throws ChangelogException
     *           If an error occurs when creating the cursor.
     */
    private LogCursor(final Log<K, V> log) throws ChangelogException
    {
      this.log = log;
    }

    /** {@inheritDoc} */
    @Override
    public Record<K, V> getRecord()
    {
      return currentCursor.getRecord();
    }

    /** {@inheritDoc} */
    @Override
    public boolean next() throws ChangelogException
    {
      log.sharedLock.lock();
      try
      {
        final boolean hasNext = currentCursor.next();
        if (hasNext)
        {
          return true;
        }
        final LogFile<K, V> logFile = log.getNextLogFile(currentLogFile);
        if (logFile != null)
        {
          switchToLogFile(logFile);
          return true;
        }
        return false;
      }
      finally
      {
        log.sharedLock.unlock();
      }
    }

    /** {@inheritDoc} */
    @Override
    public void close()
    {
      log.sharedLock.lock();
      try
      {
        StaticUtils.close(currentCursor);
        log.unregisterCursor(this);
      }
      finally
      {
        log.sharedLock.unlock();
      }
    }

    /** {@inheritDoc} */
    @Override
    public boolean positionTo(final K key, final boolean findNearest) throws ChangelogException
    {
      log.sharedLock.lock();
      try
      {
        final LogFile<K, V> logFile = log.findLogFileFor(key);
        if (logFile != currentLogFile)
        {
          switchToLogFile(logFile);
        }
        if (key != null)
        {
          boolean isFound = currentCursor.positionTo(key, findNearest);
          if (isFound && getRecord() == null)
          {
            // The key to position to may be in the next file, force the switch
            isFound = next();
          }
          return isFound;
        }
        return true;
      }
      finally
      {
        log.sharedLock.unlock();
      }
    }

    /** Returns the state of this cursor. */
    private CursorState<K, V> getState() throws ChangelogException
    {
      return new CursorState<K, V>(currentLogFile, currentCursor.getFilePosition(), currentCursor.getRecord());
    }

    /** Reinitialize this cursor to the provided state. */
    private void reinitializeTo(final CursorState<K, V> cursorState) throws ChangelogException
    {
      StaticUtils.close(currentCursor);
      currentLogFile = cursorState.logFile;
      currentCursor = currentLogFile.getCursorInitialisedTo(cursorState.record, cursorState.filePosition);
    }

    /** Switch the cursor to the provided log file. */
    private void switchToLogFile(final LogFile<K, V> logFile) throws ChangelogException
    {
      StaticUtils.close(currentCursor);
      currentLogFile = logFile;
      currentCursor = currentLogFile.getCursor();
    }
  }

  /** An empty cursor, that always return null records and false to {@code next()} method. */
  static final class EmptyLogCursor<K extends Comparable<K>, V> implements RepositionableCursor<K,V>
  {
    /** {@inheritDoc} */
    @Override
    public Record<K,V> getRecord()
    {
      return null;
    }

    /** {@inheritDoc} */
    @Override
    public boolean next()
    {
      return false;
    }

    /** {@inheritDoc} */
    @Override
    public boolean positionTo(K key, boolean returnLowestHigher) throws ChangelogException
    {
      return false;
    }

    /** {@inheritDoc} */
    @Override
    public void close()
    {
      // nothing to do
    }

    /** {@inheritDoc} */
    @Override
    public String toString()
    {
      return "EmptyLogCursor";
    }
  }

  /**
   * Represents the state of a cursor.
   * <p>
   * The state is used to update a cursor when rotating the head log file : the
   * state of cursor on head log file must be reported to the new read-only log
   * file that is created when rotating.
   */
  private static class CursorState<K extends Comparable<K>, V>
  {
    /** The log file. */
    private final LogFile<K, V> logFile;

    /**
     * The position of the reader on the log file. It is the offset from the
     * beginning of the file, in bytes, at which the next read occurs.
     */
    private final long filePosition;

    /** The record the cursor is pointing to. */
    private final Record<K,V> record;

    private CursorState(final LogFile<K, V> logFile, final long filePosition, final Record<K, V> record)
    {
      this.logFile = logFile;
      this.filePosition = filePosition;
      this.record = record;
    }
  }

}
