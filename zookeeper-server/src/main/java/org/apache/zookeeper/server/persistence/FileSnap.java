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
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.zip.Adler32;
import java.util.zip.CheckedInputStream;
import java.util.zip.CheckedOutputStream;

import org.apache.jute.BinaryInputArchive;
import org.apache.jute.BinaryOutputArchive;
import org.apache.jute.InputArchive;
import org.apache.jute.OutputArchive;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.apache.zookeeper.server.DataTree;
import org.apache.zookeeper.server.util.SerializeUtils;

/**
 * This class implements the snapshot interface.
 * it is responsible for storing, serializing
 * and deserializing the right snapshot.
 * and provides access to the snapshots.
 */
public class FileSnap implements SnapShot {
    // snapshot目录文件
    File snapDir;
    // 是否已经关闭标识
    private volatile boolean close = false;
    // 版本号
    private static final int VERSION = 2;
    // database id
    private static final long dbId = -1;
    private static final Logger LOG = LoggerFactory.getLogger(FileSnap.class);

    // snapshot文件的魔数(类似class文件的魔数)
    public final static int SNAP_MAGIC
            = ByteBuffer.wrap("ZKSN".getBytes()).getInt();

    public static final String SNAPSHOT_FILE_PREFIX = "snapshot";

    public FileSnap(File snapDir) {
        this.snapDir = snapDir;
    }

    /**
     * deserialize a data tree from the most recent snapshot
     *
     * @return the zxid of the snapshot
     */
    public long deserialize(DataTree dt, Map<Long, Integer> sessions)
            throws IOException {
        // 查找100个合法的snapshot文件
        List<File> snapList = findNValidSnapshots(100);
        if (snapList.size() == 0) {
            return -1L;
        }

        File snap = null;
        // 默认为不合法
        boolean foundValid = false;

        for (int i = 0, snapListSize = snapList.size(); i < snapListSize; i++) {
            snap = snapList.get(i);
            LOG.info("Reading snapshot " + snap);
            // 读取指定的snapshot文件
            try (InputStream snapIS = new BufferedInputStream(new FileInputStream(snap));
                 // 验证
                 CheckedInputStream crcIn = new CheckedInputStream(snapIS, new Adler32())) {
                InputArchive ia = BinaryInputArchive.getArchive(crcIn);
                // 反序列化
                deserialize(dt, sessions, ia);
                // 获取验证的值Checksum
                long checkSum = crcIn.getChecksum().getValue();
                // 从文件中读取val值
                long val = ia.readLong("val");
                if (val != checkSum) {   // 比较验证，不相等，抛出异常
                    throw new IOException("CRC corruption in snapshot :  " + snap);
                }
                // 合法
                foundValid = true;
                break;
            } catch (IOException e) {
                LOG.warn("problem reading snap file " + snap, e);
            }
        }

        if (!foundValid) {  // 遍历所有文件都未验证成功
            throw new IOException("Not able to find valid snapshots in " + snapDir);
        }

        // 从文件名中解析出zxid
        dt.lastProcessedZxid = Util.getZxidFromName(snap.getName(), SNAPSHOT_FILE_PREFIX);
        return dt.lastProcessedZxid;
    }

    /**
     * deserialize the datatree from an inputarchive
     *
     * @param dt       the datatree to be serialized into
     * @param sessions the sessions to be filled up
     * @param ia       the input archive to restore from
     * @throws IOException
     */
    public void deserialize(DataTree dt, Map<Long, Integer> sessions,
                            InputArchive ia) throws IOException {
        FileHeader header = new FileHeader();
        header.deserialize(ia, "fileheader");
        if (header.getMagic() != SNAP_MAGIC) {
            throw new IOException("mismatching magic headers "
                    + header.getMagic() +
                    " !=  " + FileSnap.SNAP_MAGIC);
        }
        SerializeUtils.deserializeSnapshot(dt, ia, sessions);
    }

    /**
     * find the most recent snapshot in the database.
     *
     * @return the file containing the most recent snapshot
     */
    public File findMostRecentSnapshot() throws IOException {
        List<File> files = findNValidSnapshots(1);
        if (files.size() == 0) {
            return null;
        }
        return files.get(0);
    }

    /**
     * find the last (maybe) valid n snapshots. this does some
     * minor checks on the validity of the snapshots. It just
     * checks for / at the end of the snapshot. This does
     * not mean that the snapshot is truly valid but is
     * valid with a high probability. also, the most recent
     * will be first on the list.
     *
     * @param n the number of most recent snapshots
     * @return the last n snapshots (the number might be
     * less than n in case enough snapshots are not available).
     * @throws IOException
     */
    private List<File> findNValidSnapshots(int n) throws IOException {
        // 按照zxid对snapshot文件进行降序排序
        List<File> files = Util.sortDataDir(snapDir.listFiles(), SNAPSHOT_FILE_PREFIX, false);
        int count = 0;
        List<File> list = new ArrayList<File>();
        for (File f : files) {  // 遍历snapshot文件
            try {
                // 验证文件是否合法，在写snapshot文件时服务器宕机
                // 此时的snapshot文件非法;  非snapshot文件也非法
                if (Util.isValidSnapshot(f)) {
                    // 合法则添加
                    list.add(f);
                    count++;

                    // 等于n则跳出循环
                    if (count == n) {
                        break;
                    }
                }
            } catch (IOException e) {
                LOG.info("invalid snapshot " + f, e);
            }
        }
        return list;
    }

    /**
     * find the last n snapshots. this does not have
     * any checks if the snapshot might be valid or not
     *
     * @param n the number of most recent snapshots
     * @return the last n snapshots
     * @throws IOException
     */
    public List<File> findNRecentSnapshots(int n) throws IOException {
        List<File> files = Util.sortDataDir(snapDir.listFiles(), SNAPSHOT_FILE_PREFIX, false);
        int count = 0;
        List<File> list = new ArrayList<File>();
        for (File f : files) {
            if (count == n)
                break;
            if (Util.getZxidFromName(f.getName(), SNAPSHOT_FILE_PREFIX) != -1) {
                count++;
                list.add(f);
            }
        }
        return list;
    }

    /**
     * serialize the datatree and sessions
     *
     * @param dt       the datatree to be serialized
     * @param sessions the sessions to be serialized
     * @param oa       the output archive to serialize into
     * @param header   the header of this snapshot
     * @throws IOException
     */
    protected void serialize(DataTree dt, Map<Long, Integer> sessions,
                             OutputArchive oa, FileHeader header) throws IOException {
        // 文件头为null
        if (header == null)
            throw new IllegalStateException(
                    "Snapshot's not open for writing: uninitialized header");

        // 将header序列化
        header.serialize(oa, "fileheader");

        // 将dt、sessions序列化
        SerializeUtils.serializeSnapshot(dt, oa, sessions);
    }

    /**
     * serialize the datatree and session into the file snapshot
     *
     * @param dt       the datatree to be serialized
     * @param sessions the sessions to be serialized
     * @param snapShot the file to store snapshot into
     */
    public synchronized void serialize(DataTree dt, Map<Long, Integer> sessions, File snapShot)
            throws IOException {
        if (!close) {
            // 输出流
            try (OutputStream sessOS = new BufferedOutputStream(new FileOutputStream(snapShot));
                 CheckedOutputStream crcOut = new CheckedOutputStream(sessOS, new Adler32())) {
                //CheckedOutputStream cout = new CheckedOutputStream()
                OutputArchive oa = BinaryOutputArchive.getArchive(crcOut);

                // 新生文件头
                FileHeader header = new FileHeader(SNAP_MAGIC, VERSION, dbId);

                // 序列化dt、sessions、header
                serialize(dt, sessions, oa, header);

                // 获取验证的值
                long val = crcOut.getChecksum().getValue();
                oa.writeLong(val, "val");
                oa.writeString("/", "path");

                // 强制刷新
                sessOS.flush();
            }
        } else {
            throw new IOException("FileSnap has already been closed");
        }
    }

    /**
     * synchronized close just so that if serialize is in place
     * the close operation will block and will wait till serialize
     * is done and will set the close flag
     */
    @Override
    public synchronized void close() throws IOException {
        close = true;
    }

}
