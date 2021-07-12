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

package org.apache.zookeeper.server;

import java.io.Flushable;
import java.io.IOException;
import java.util.LinkedList;
import java.util.Random;
import java.util.concurrent.LinkedBlockingQueue;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * This RequestProcessor logs requests to disk. It batches the requests to do
 * the io efficiently. The request is not passed to the next RequestProcessor
 * until its log has been synced to disk.
 *
 * SyncRequestProcessor is used in 3 different cases
 * 1. Leader - Sync request to disk and forward it to AckRequestProcessor which
 *             send ack back to itself.
 * 2. Follower - Sync request to disk and forward request to
 *             SendAckRequestProcessor which send the packets to leader.
 *             SendAckRequestProcessor is flushable which allow us to force
 *             push packets to leader.
 * 3. Observer - Sync committed request to disk (received as INFORM packet).
 *             It never send ack back to the leader, so the nextProcessor will
 *             be null. This change the semantic of txnlog on the observer
 *             since it only contains committed txns.
 *
 *
 *   主要负责数据的持久化
 */
public class SyncRequestProcessor extends ZooKeeperCriticalThread implements
        RequestProcessor {
    private static final Logger LOG = LoggerFactory.getLogger(SyncRequestProcessor.class);
    private final ZooKeeperServer zks;

    // 请求队列
    private final LinkedBlockingQueue<Request> queuedRequests = new LinkedBlockingQueue<Request>();

    //下一个处理器
    private final RequestProcessor nextProcessor;

    //处理快照的线程
    private Thread snapInProcess = null;
    volatile private boolean running;

    /**
     * Transactions that have been written and are waiting to be flushed to
     * disk. Basically this is the list of SyncItems whose callbacks will be
     * invoked after flush returns successfully.
     */
    private final LinkedList<Request> toFlush = new LinkedList<Request>();
    private final Random r = new Random();
    /**
     * The number of log entries to log before starting a snapshot
     */
    private static int snapCount = ZooKeeperServer.getSnapCount();

    private final Request requestOfDeath = Request.requestOfDeath;

    public SyncRequestProcessor(ZooKeeperServer zks,
                                RequestProcessor nextProcessor) {
        super("SyncThread:" + zks.getServerId(), zks
                .getZooKeeperServerListener());
        this.zks = zks;
        this.nextProcessor = nextProcessor;
        running = true;
    }

    /**
     * used by tests to check for changing
     * snapcounts
     * @param count
     */
    public static void setSnapCount(int count) {
        snapCount = count;
    }

    /**
     * used by tests to get the snapcount
     * @return the snapcount
     */
    public static int getSnapCount() {
        return snapCount;
    }

    @Override
    public void run() {
        try {
            int logCount = 0;

            // we do this in an attempt to ensure that not all of the servers
            // in the ensemble take a snapshot at the same time
            //randRoll是一个 snapCount/2以内的随机数, 避免所有机器同时进行snapshot
            int randRoll = r.nextInt(snapCount / 2);
            while (true) {
                Request si = null;
                if (toFlush.isEmpty()) {  // 没有要刷到磁盘的请求
                    si = queuedRequests.take();
                } else {
                    //有需要刷到磁盘的请求
                    si = queuedRequests.poll();

                    //如果请求队列的当前请求为空
                    if (si == null) {
                        //刷到磁盘
                        flush(toFlush);
                        continue;
                    }
                }

                //结束标识请求
                if (si == requestOfDeath) {
                    break;
                }

                //请求队列取出了请求
                if (si != null) {
                    // track the number of records written to the log
                    // 请求添加至日志文件，只有事务性请求才会返回true
                    if (zks.getZKDatabase().append(si)) {
                        logCount++;

                        //如果logCount到了一定的量
                        if (logCount > (snapCount / 2 + randRoll)) {
                            //下一次的随机数重新选
                            randRoll = r.nextInt(snapCount / 2);

                            // roll the log
                            //事务日志滚动到另外一个文件记录
                            zks.getZKDatabase().rollLog();

                            // take a snapshot
                            //正在进行快照
                            if (snapInProcess != null && snapInProcess.isAlive()) {
                                LOG.warn("Too busy to snap, skipping");
                            } else {
                                snapInProcess = new ZooKeeperThread("Snapshot Thread") {
                                    public void run() {
                                        try {
                                            //进行快照,将sessions和datatree保存至snapshot文件
                                            zks.takeSnapshot();
                                        } catch (Exception e) {
                                            LOG.warn("Unexpected exception", e);
                                        }
                                    }
                                };

                                //启动线程
                                snapInProcess.start();
                            }

                            //重置
                            logCount = 0;
                        }
                    } else if (toFlush.isEmpty()) {  // 到磁盘的队列为空
                        // optimization for read heavy workloads
                        // iff this is a read, and there are no pending
                        // flushes (writes), then just pass this to the next
                        // processor
                        if (nextProcessor != null) {

                            //单机：将请求提交到下一个处理器   FinalRequestProcessor
                            nextProcessor.processRequest(si);

                            if (nextProcessor instanceof Flushable) {
                                //下个处理器可以刷，就刷
                                ((Flushable) nextProcessor).flush();
                            }
                        }
                        continue;
                    }

                    //刷的队列添加记录
                    toFlush.add(si);

                    //超过了1000条就一起刷到磁盘
                    if (toFlush.size() > 1000) {
                        flush(toFlush);
                    }
                }
            }
        } catch (Throwable t) {
            handleException(this.getName(), t);
        } finally {
            running = false;
        }
        LOG.info("SyncRequestProcessor exited!");
    }

    private void flush(LinkedList<Request> toFlush)
            throws IOException, RequestProcessorException {

        //队列为空，没有需要刷的
        if (toFlush.isEmpty())
            return;

        //事务日志刷到磁盘
        zks.getZKDatabase().commit();
        while (!toFlush.isEmpty()) {
            Request i = toFlush.remove();
            if (nextProcessor != null) {
                nextProcessor.processRequest(i);
            }
        }
        if (nextProcessor != null && nextProcessor instanceof Flushable) {
            //下个处理器也可以刷，就刷
            ((Flushable) nextProcessor).flush();
        }
    }

    /**
     * 队列添加requestOfDeath请求，线程结束后，调用flush函数，最后关闭nextProcessor
     */
    public void shutdown() {
        LOG.info("Shutting down");
        queuedRequests.add(requestOfDeath);
        try {
            if (running) {
                this.join();
            }
            if (!toFlush.isEmpty()) {
                flush(toFlush);
            }
        } catch (InterruptedException e) {
            LOG.warn("Interrupted while wating for " + this + " to finish");
        } catch (IOException e) {
            LOG.warn("Got IO exception during shutdown");
        } catch (RequestProcessorException e) {
            LOG.warn("Got request processor exception during shutdown");
        }
        if (nextProcessor != null) {
            nextProcessor.shutdown();
        }
    }

    public void processRequest(Request request) {
        // request.addRQRec(">sync");
        queuedRequests.add(request);
    }

}
