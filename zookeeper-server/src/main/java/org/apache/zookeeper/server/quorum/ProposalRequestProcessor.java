/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.zookeeper.server.quorum;

import org.apache.zookeeper.server.Request;
import org.apache.zookeeper.server.RequestProcessor;
import org.apache.zookeeper.server.SyncRequestProcessor;
import org.apache.zookeeper.server.quorum.Leader.XidRolloverException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * This RequestProcessor simply forwards requests to an AckRequestProcessor and
 * SyncRequestProcessor.
 */
public class ProposalRequestProcessor implements RequestProcessor {
    private static final Logger LOG =
        LoggerFactory.getLogger(ProposalRequestProcessor.class);

    //leader角色才有这个请求处理器，这里能拿到LeaderZooKeeperServer
    LeaderZooKeeperServer zks;

    //下一个处理器
    RequestProcessor nextProcessor;

    //同步处理器
    SyncRequestProcessor syncProcessor;

    public ProposalRequestProcessor(LeaderZooKeeperServer zks,
            RequestProcessor nextProcessor) {
        this.zks = zks;
        this.nextProcessor = nextProcessor;  //一般是CommitProcessor
        AckRequestProcessor ackProcessor = new AckRequestProcessor(zks.getLeader());
        syncProcessor = new SyncRequestProcessor(zks, ackProcessor);
    }

    /**
     * initialize this processor
     */
    public void initialize() {
        //启动同步处理器
        syncProcessor.start();
    }

    public void processRequest(Request request) throws RequestProcessorException {
        // LOG.warn("Ack>>> cxid = " + request.cxid + " type = " +
        // request.type + " id = " + request.sessionId);
        // request.addRQRec(">prop");


        /* In the following IF-THEN-ELSE block, we process syncs on the leader.
         * If the sync is coming from a follower, then the follower
         * handler adds it to syncHandler. Otherwise, if it is a client of
         * the leader that issued the sync command, then syncHandler won't
         * contain the handler. In this case, we add it to syncHandler, and
         * call processRequest on the next processor.
         */

        //如果是client的同步的请求
        if (request instanceof LearnerSyncRequest){
            //非事物请求交给下一个 CommitProcessor
            zks.getLeader().processSync((LearnerSyncRequest)request);
        } else {
            nextProcessor.processRequest(request);
            //hdr不为空，说明是事务请求，委托给leader，发送proposal消息
            if (request.getHdr() != null) {
                try {
                    //leader发出提议,集群进行投票
                    zks.getLeader().propose(request);
                } catch (XidRolloverException e) {
                    throw new RequestProcessorException(e.getMessage(), e);
                }

                //并且自己先持久化到txnLog日志里面
                //事务请求需要syncProcessor进行处理
                syncProcessor.processRequest(request);
            }
        }
    }

    public void shutdown() {
        LOG.info("Shutting down");
        nextProcessor.shutdown();  //ProposalRequestProcessor后面两个Processor都要关闭
        syncProcessor.shutdown();
    }

}
