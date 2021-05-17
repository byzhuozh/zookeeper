/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 * <p>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.zookeeper.server.persistence;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.Closeable;
import java.io.EOFException;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.zip.Adler32;
import java.util.zip.Checksum;

import org.apache.jute.BinaryInputArchive;
import org.apache.jute.BinaryOutputArchive;
import org.apache.jute.InputArchive;
import org.apache.jute.OutputArchive;
import org.apache.jute.Record;
import org.apache.zookeeper.server.ServerStats;
import org.apache.zookeeper.server.util.SerializeUtils;
import org.apache.zookeeper.txn.TxnHeader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * This class implements the TxnLog interface. It provides api's
 * to access the txnlogs and add entries to it.
 * <p>
 * The format of a Transactional log is as follows:
 * <blockquote><pre>
 * LogFile:
 *     FileHeader TxnList ZeroPad
 *
 * FileHeader: {
 *     magic 4bytes (ZKLG)
 *     version 4bytes
 *     dbid 8bytes
 *   }
 *
 * TxnList:
 *     Txn || Txn TxnList
 *
 * Txn:
 *     checksum Txnlen TxnHeader Record 0x42
 *
 * checksum: 8bytes Adler32 is currently used
 *   calculated across payload -- Txnlen, TxnHeader, Record and 0x42
 *
 * Txnlen:
 *     len 4bytes
 *
 * TxnHeader: {
 *     sessionid 8bytes
 *     cxid 4bytes
 *     zxid 8bytes
 *     time 8bytes
 *     type 4bytes
 *   }
 *
 * Record:
 *     See Jute definition file for details on the various record types
 *
 * ZeroPad:
 *     0 padded to EOF (filled during preallocation stage)
 * </pre></blockquote>
 */
public class FileTxnLog implements TxnLog, Closeable {

    private static final Logger LOG;

    // 魔术数字，默认为1514884167
    public final static int TXNLOG_MAGIC = ByteBuffer.wrap("ZKLG".getBytes()).getInt();

    public final static int VERSION = 2;

    public static final String LOG_FILE_PREFIX = "log";

    static final String FSYNC_WARNING_THRESHOLD_MS_PROPERTY = "fsync.warningthresholdms";
    static final String ZOOKEEPER_FSYNC_WARNING_THRESHOLD_MS_PROPERTY = "zookeeper." + FSYNC_WARNING_THRESHOLD_MS_PROPERTY;

    /**
     * Maximum time we allow for elapsed fsync before WARNing
     */
    // 进行同步时，发出warn之前所能等待的最长时间
    private final static long fsyncWarningThresholdMS;

    static {
        LOG = LoggerFactory.getLogger(FileTxnLog.class);

        /** Local variable to read fsync.warningthresholdms into */
        Long fsyncWarningThreshold;
        if ((fsyncWarningThreshold = Long.getLong(ZOOKEEPER_FSYNC_WARNING_THRESHOLD_MS_PROPERTY)) == null)
            fsyncWarningThreshold = Long.getLong(FSYNC_WARNING_THRESHOLD_MS_PROPERTY, 1000);
        fsyncWarningThresholdMS = fsyncWarningThreshold;
    }

    // 最大(新)的zxid
    long lastZxidSeen;

    // 存储数据相关的流
    volatile BufferedOutputStream logStream = null;
    volatile OutputArchive oa;
    volatile FileOutputStream fos = null;

    // log目录文件
    File logDir;

    // 是否强制同步
    private final boolean forceSync = !System.getProperty("zookeeper.forceSync", "yes").equals("no");

    // 数据库id
    long dbId;

    // 流列表
    private LinkedList<FileOutputStream> streamsToFlush = new LinkedList<FileOutputStream>();

    File logFileWrite = null;
    private FilePadding filePadding = new FilePadding();

    private ServerStats serverStats;

    private volatile long syncElapsedMS = -1L;

    /**
     * constructor for FileTxnLog. Take the directory
     * where the txnlogs are stored
     *
     * @param logDir the directory where the txnlogs are stored
     */
    public FileTxnLog(File logDir) {
        this.logDir = logDir;
    }

    /**
     * method to allow setting preallocate size
     * of log file to pad the file.
     *
     * @param size the size to set to in bytes
     */
    public static void setPreallocSize(long size) {
        FilePadding.setPreallocSize(size);
    }

    /**
     * Setter for ServerStats to monitor fsync threshold exceed
     *
     * @param serverStats used to update fsyncThresholdExceedCount
     */
    @Override
    public synchronized void setServerStats(ServerStats serverStats) {
        this.serverStats = serverStats;
    }

    /**
     * creates a checksum algorithm to be used
     *
     * @return the checksum used for this txnlog
     */
    protected Checksum makeChecksumAlgorithm() {
        return new Adler32();
    }

    /**
     * rollover the current log file to a new one.
     *
     * @throws IOException
     */
    public synchronized void rollLog() throws IOException {
        if (logStream != null) {
            this.logStream.flush();
            this.logStream = null;
            oa = null;
        }
    }

    /**
     * close all the open file handles
     *
     * @throws IOException
     */
    public synchronized void close() throws IOException {
        if (logStream != null) {
            logStream.close();
        }
        for (FileOutputStream log : streamsToFlush) {
            log.close();
        }
    }

    /**
     * append an entry to the transaction log
     *
     * @param hdr the header of the transaction
     * @param txn the transaction part of the entry
     *            returns true iff something appended, otw false
     */
    public synchronized boolean append(TxnHeader hdr, Record txn)
            throws IOException {
        // 校验事务头部不为空
        if (hdr == null) {
            return false;
        }

        // 事务的zxid小于等于最后的zxid
        if (hdr.getZxid() <= lastZxidSeen) {
            LOG.warn("Current zxid " + hdr.getZxid()
                    + " is <= " + lastZxidSeen + " for "
                    + hdr.getType());
        } else {
            lastZxidSeen = hdr.getZxid();
        }

        // 日志流为空
        if (logStream == null) {
            if (LOG.isInfoEnabled()) {
                LOG.info("Creating new log file: " + Util.makeLogName(hdr.getZxid()));
            }

            logFileWrite = new File(logDir, Util.makeLogName(hdr.getZxid()));
            fos = new FileOutputStream(logFileWrite);
            logStream = new BufferedOutputStream(fos);
            oa = BinaryOutputArchive.getArchive(logStream);
            FileHeader fhdr = new FileHeader(TXNLOG_MAGIC, VERSION, dbId);
            // 序列化
            fhdr.serialize(oa, "fileheader");
            // Make sure that the magic number is written before padding.
            // 刷新到磁盘
            logStream.flush();
            // 当前通道的大小
            filePadding.setCurrentSize(fos.getChannel().position());
            // 添加fos
            streamsToFlush.add(fos);
        }

        // 填充文件
        filePadding.padFile(fos.getChannel());

        // 将事务头和事务数据序列化成 Byte Buffer
        byte[] buf = Util.marshallTxnEntry(hdr, txn);
        if (buf == null || buf.length == 0) {
            throw new IOException("Faulty serialization for header " +
                    "and txn");
        }
        //生成校验值，用了Adler32算法
        Checksum crc = makeChecksumAlgorithm();
        // 使用Byte数组来更新当前的Checksum
        crc.update(buf, 0, buf.length);
        // 写long类型数据
        oa.writeLong(crc.getValue(), "txnEntryCRC");
        // 将序列化的事务记录写入OutputArchive
        Util.writeTxnBytes(oa, buf);

        return true;
    }

    /**
     * Find the log file that starts at, or just before, the snapshot. Return
     * this and all subsequent logs. Results are ordered by zxid of file,
     * ascending order.
     *
     * @param logDirList   array of files
     * @param snapshotZxid return files at, or before this zxid
     * @return 找出刚刚小于或者等于snapshot的所有log文件
     */
    public static File[] getLogFiles(File[] logDirList, long snapshotZxid) {
        // 按照zxid对文件进行排序
        List<File> files = Util.sortDataDir(logDirList, LOG_FILE_PREFIX, true);
        long logZxid = 0;
        // Find the log file that starts before or at the same time as the
        // zxid of the snapshot
        for (File f : files) {
            //从名字中取zxid
            long fzxid = Util.getZxidFromName(f.getName(), LOG_FILE_PREFIX);
            // 跳过大于snapshotZxid的文件
            if (fzxid > snapshotZxid) {
                continue;
            }
            // the files
            // are sorted with zxid's
            // 找出文件中最大的zxid (同时还需要小于等于snapshotZxid)
            if (fzxid > logZxid) {
                logZxid = fzxid;
            }
        }

        List<File> v = new ArrayList<File>(5);
        for (File f : files) {
            //遍历，把所有早于这个id的文件都不要。
            long fzxid = Util.getZxidFromName(f.getName(), LOG_FILE_PREFIX);
            // 跳过小于logZxid的文件
            if (fzxid < logZxid) {
                continue;
            }
            v.add(f);
        }
        // 转化成File[] 类型后返回
        return v.toArray(new File[0]);

    }

    /**
     * get the last zxid that was logged in the transaction logs
     *
     * @return the last zxid logged in the transaction logs
     * <p>
     * 获取记录在log中的最后一个zxid
     */
    public long getLastLoggedZxid() {
        // 获取已排好序的所有的log文件
        File[] files = getLogFiles(logDir.listFiles(), 0);

        // 获取最大的zxid (最后一个log文件对应的zxid)
        long maxLog = files.length > 0 ?
                Util.getZxidFromName(files[files.length - 1].getName(), LOG_FILE_PREFIX) : -1;

        // if a log file is more recent we must scan it to find
        // the highest zxid
        long zxid = maxLog;
        TxnIterator itr = null;
        try {
            // 新生 FileTxnLog
            FileTxnLog txn = new FileTxnLog(logDir);

            // 开始读取从给定 zxid 之后的所有事务
            //所有id>maxlog的文件的都在itr中
            //更新：又看了下这里的代码，这个read函数是这里最核心的部分，内部构造了一个迭代器，会根据传入的zxid定位到对应文件的位置并以依次访问
            //https://www.cnblogs.com/leesf456/p/6279956.html这篇博客这里写的很好，可以看。
            itr = txn.read(maxLog);
            while (true) {
                if (!itr.next())
                    break;
                // 获取事务头
                TxnHeader hdr = itr.getHeader();
                // 获取zxid
                zxid = hdr.getZxid();
            }
        } catch (IOException e) {
            LOG.warn("Unexpected exception", e);
        } finally {
            close(itr);
        }
        return zxid;
    }

    private void close(TxnIterator itr) {
        if (itr != null) {
            try {
                itr.close();
            } catch (IOException ioe) {
                LOG.warn("Error closing file iterator", ioe);
            }
        }
    }

    /**
     * commit the logs. make sure that everything hits the
     * disk
     */
    public synchronized void commit() throws IOException {
        if (logStream != null) {
            // 强制刷到磁盘
            logStream.flush();
        }

        for (FileOutputStream log : streamsToFlush) {
            // 强制刷到磁盘
            log.flush();

            if (forceSync) {   // 是否强制同步
                long startSyncNS = System.nanoTime();

                FileChannel channel = log.getChannel();
                channel.force(false);
                // 计算流式的时间
                syncElapsedMS = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startSyncNS);

                // 大于阈值时则会警告
                if (syncElapsedMS > fsyncWarningThresholdMS) {
                    if (serverStats != null) {
                        serverStats.incrementFsyncThresholdExceedCount();
                    }
                    LOG.warn("fsync-ing the write ahead log in "
                            + Thread.currentThread().getName()
                            + " took " + syncElapsedMS
                            + "ms which will adversely effect operation latency. "
                            + "File size is " + channel.size() + " bytes. "
                            + "See the ZooKeeper troubleshooting guide");
                }
            }
        }

        // 移除流并关闭
        while (streamsToFlush.size() > 1) {
            streamsToFlush.removeFirst().close();
        }
    }

    /**
     * @return elapsed sync time of transaction log in milliseconds
     */
    public long getTxnLogSyncElapsedTime() {
        return syncElapsedMS;
    }

    /**
     * start reading all the transactions from the given zxid
     *
     * @param zxid the zxid to start reading transactions from
     * @return returns an iterator to iterate through the transaction
     * logs
     */
    public TxnIterator read(long zxid) throws IOException {
        return read(zxid, true);
    }

    /**
     * start reading all the transactions from the given zxid.
     *
     * @param zxid        the zxid to start reading transactions from
     * @param fastForward true if the iterator should be fast forwarded to point
     *                    to the txn of a given zxid, else the iterator will point to the
     *                    starting txn of a txnlog that may contain txn of a given zxid
     * @return returns an iterator to iterate through the transaction logs
     */
    public TxnIterator read(long zxid, boolean fastForward) throws IOException {
        // 返回事务文件访问迭代器
        return new FileTxnIterator(logDir, zxid, fastForward);
    }

    /**
     * truncate the current transaction logs
     *
     * @param zxid the zxid to truncate the logs to
     * @return true if successful false if not
     */
    public boolean truncate(long zxid) throws IOException {
        FileTxnIterator itr = null;
        try {
            // 获取迭代器
            itr = new FileTxnIterator(this.logDir, zxid);
            PositionInputStream input = itr.inputStream;
            if (input == null) {
                throw new IOException("No log files found to truncate! This could " +
                        "happen if you still have snapshots from an old setup or " +
                        "log files were deleted accidentally or dataLogDir was changed in zoo.cfg.");
            }
            long pos = input.getPosition();

            // now, truncate at the current position
            // 从当前位置开始清空
            RandomAccessFile raf = new RandomAccessFile(itr.logFile, "rw");
            raf.setLength(pos);
            raf.close();

            // 存在下一个log文件
            while (itr.goToNextLog()) {
                // 删除
                if (!itr.logFile.delete()) {
                    LOG.warn("Unable to truncate {}", itr.logFile);
                }
            }
        } finally {
            close(itr);
        }
        return true;
    }

    /**
     * read the header of the transaction file
     *
     * @param file the transaction file to read
     * @return header that was read from the file
     * @throws IOException
     */
    private static FileHeader readHeader(File file) throws IOException {
        InputStream is = null;
        try {
            is = new BufferedInputStream(new FileInputStream(file));
            InputArchive ia = BinaryInputArchive.getArchive(is);
            FileHeader hdr = new FileHeader();
            hdr.deserialize(ia, "fileheader");
            return hdr;
        } finally {
            try {
                if (is != null) is.close();
            } catch (IOException e) {
                LOG.warn("Ignoring exception during close", e);
            }
        }
    }

    /**
     * the dbid of this transaction database
     *
     * @return the dbid of this database
     */
    public long getDbId() throws IOException {
        FileTxnIterator itr = new FileTxnIterator(logDir, 0);
        FileHeader fh = readHeader(itr.logFile);
        itr.close();
        if (fh == null)
            throw new IOException("Unsupported Format.");
        return fh.getDbid();
    }

    /**
     * the forceSync value. true if forceSync is enabled, false otherwise.
     *
     * @return the forceSync value
     */
    public boolean isForceSync() {
        return forceSync;
    }

    /**
     * a class that keeps track of the position
     * in the input stream. The position points to offset
     * that has been consumed by the applications. It can
     * wrap buffered input streams to provide the right offset
     * for the application.
     */
    static class PositionInputStream extends FilterInputStream {
        long position;

        protected PositionInputStream(InputStream in) {
            super(in);
            position = 0;
        }

        @Override
        public int read() throws IOException {
            int rc = super.read();
            if (rc > -1) {
                position++;
            }
            return rc;
        }

        public int read(byte[] b) throws IOException {
            int rc = super.read(b);
            if (rc > 0) {
                position += rc;
            }
            return rc;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            int rc = super.read(b, off, len);
            if (rc > 0) {
                position += rc;
            }
            return rc;
        }

        @Override
        public long skip(long n) throws IOException {
            long rc = super.skip(n);
            if (rc > 0) {
                position += rc;
            }
            return rc;
        }

        public long getPosition() {
            return position;
        }

        @Override
        public boolean markSupported() {
            return false;
        }

        @Override
        public void mark(int readLimit) {
            throw new UnsupportedOperationException("mark");
        }

        @Override
        public void reset() {
            throw new UnsupportedOperationException("reset");
        }
    }

    /**
     * this class implements the txnlog iterator interface
     * which is used for reading the transaction logs
     */
    public static class FileTxnIterator implements TxnLog.TxnIterator {
        File logDir;
        long zxid;
        TxnHeader hdr;
        Record record;
        File logFile;
        InputArchive ia;
        static final String CRC_ERROR = "CRC check failed";

        PositionInputStream inputStream = null;
        //stored files is the list of files greater than
        //the zxid we are looking for.
        private ArrayList<File> storedFiles;

        /**
         * create an iterator over a transaction database directory
         *
         * @param logDir      the transaction database directory
         * @param zxid        the zxid to start reading from
         * @param fastForward true if the iterator should be fast forwarded to
         *                    point to the txn of a given zxid, else the iterator will
         *                    point to the starting txn of a txnlog that may contain txn of
         *                    a given zxid
         * @throws IOException
         */
        public FileTxnIterator(File logDir, long zxid, boolean fastForward)
                throws IOException {
            this.logDir = logDir;
            this.zxid = zxid;
            init();

            if (fastForward && hdr != null) {
                while (hdr.getZxid() < zxid) {
                    if (!next())
                        break;
                }
            }
        }

        /**
         * create an iterator over a transaction database directory
         *
         * @param logDir the transaction database directory
         * @param zxid   the zxid to start reading from
         * @throws IOException
         */
        public FileTxnIterator(File logDir, long zxid) throws IOException {
            this(logDir, zxid, true);
        }

        /**
         * initialize to the zxid specified
         * this is inclusive of the zxid
         *
         * @throws IOException
         */
        void init() throws IOException {
            // 新生成文件列表
            storedFiles = new ArrayList<File>();
            // 进行排序
            List<File> files = Util.sortDataDir(FileTxnLog.getLogFiles(logDir.listFiles(), 0), LOG_FILE_PREFIX, false);

            for (File f : files) {
                // 添加zxid大于等于指定zxid的文件
                if (Util.getZxidFromName(f.getName(), LOG_FILE_PREFIX) >= zxid) {
                    storedFiles.add(f);
                }

                // add the last logfile that is less than the zxid
                // 只添加一个zxid小于指定zxid的文件，然后退出
                else if (Util.getZxidFromName(f.getName(), LOG_FILE_PREFIX) < zxid) {
                    storedFiles.add(f);
                    break;
                }
            }

            // 进入下一个log文件
            goToNextLog();

            next();
        }

        /**
         * Return total storage size of txnlog that will return by this iterator.
         */
        public long getStorageSize() {
            long sum = 0;
            for (File f : storedFiles) {
                sum += f.length();
            }
            return sum;
        }

        /**
         * go to the next logfile
         *
         * @return true if there is one and false if there is no
         * new file to be read
         * @throws IOException
         */
        private boolean goToNextLog() throws IOException {
            if (storedFiles.size() > 0) {
                // 取最后一个log文件
                this.logFile = storedFiles.remove(storedFiles.size() - 1);
                // 针对该文件，创建InputArchive
                ia = createInputArchive(this.logFile);
                return true;
            }
            return false;
        }

        /**
         * read the header from the inputarchive
         *
         * @param ia the inputarchive to be read from
         * @param is the inputstream
         * @throws IOException
         */
        protected void inStreamCreated(InputArchive ia, InputStream is)
                throws IOException {
            FileHeader header = new FileHeader();
            header.deserialize(ia, "fileheader");
            if (header.getMagic() != FileTxnLog.TXNLOG_MAGIC) {
                throw new IOException("Transaction log: " + this.logFile + " has invalid magic number "
                        + header.getMagic()
                        + " != " + FileTxnLog.TXNLOG_MAGIC);
            }
        }

        /**
         * Invoked to indicate that the input stream has been created.
         *
         * @param is input archive
         * @param is file input stream associated with the input archive.
         * @throws IOException
         **/
        protected InputArchive createInputArchive(File logFile) throws IOException {
            if (inputStream == null) {
                inputStream = new PositionInputStream(new BufferedInputStream(new FileInputStream(logFile)));
                LOG.debug("Created new input stream " + logFile);
                ia = BinaryInputArchive.getArchive(inputStream);
                inStreamCreated(ia, inputStream);
                LOG.debug("Created new input archive " + logFile);
            }
            return ia;
        }

        /**
         * create a checksum algorithm
         *
         * @return the checksum algorithm
         */
        protected Checksum makeChecksumAlgorithm() {
            return new Adler32();
        }

        /**
         * the iterator that moves to the next transaction
         *
         * @return true if there is more transactions to be read
         * false if not.
         */
        public boolean next() throws IOException {
            if (ia == null) {
                return false;
            }
            try {
                // 读取长整形crcValue
                long crcValue = ia.readLong("crcvalue");

                // 通过input archive读取一个事务条目
                byte[] bytes = Util.readTxnBytes(ia);

                // Since we preallocate, we define EOF to be an
                if (bytes == null || bytes.length == 0) {   // 对bytes进行判断
                    throw new EOFException("Failed to read " + logFile);
                }

                // EOF or corrupted record
                // validate CRC
                // 验证CRC
                Checksum crc = makeChecksumAlgorithm();

                // 更新
                crc.update(bytes, 0, bytes.length);

                // 验证不相等，抛出异常
                if (crcValue != crc.getValue())
                    throw new IOException(CRC_ERROR);

                // 新生成TxnHeader
                hdr = new TxnHeader();

                // 将Txn反序列化，并且将对应的TxnHeader反序列化至hdr，整个Record反序列化至record
                record = SerializeUtils.deserializeTxn(bytes, hdr);
            } catch (EOFException e) {
                LOG.debug("EOF exception " + e);
                inputStream.close();
                inputStream = null;
                ia = null;
                hdr = null;
                // this means that the file has ended
                // we should go to the next file
                if (!goToNextLog()) {
                    return false;
                }
                // if we went to the next log file, we should call next() again
                return next();
            } catch (IOException e) {
                inputStream.close();
                throw e;
            }
            return true;
        }

        /**
         * return the current header
         *
         * @return the current header that
         * is read
         */
        public TxnHeader getHeader() {
            return hdr;
        }

        /**
         * return the current transaction
         *
         * @return the current transaction
         * that is read
         */
        public Record getTxn() {
            return record;
        }

        /**
         * close the iterator
         * and release the resources.
         */
        public void close() throws IOException {
            if (inputStream != null) {
                inputStream.close();
            }
        }
    }

}
