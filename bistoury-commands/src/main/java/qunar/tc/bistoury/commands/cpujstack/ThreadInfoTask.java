/*
 * Copyright (C) 2019 Qunar, Inc.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package qunar.tc.bistoury.commands.cpujstack;

import com.fasterxml.jackson.core.type.TypeReference;
import com.google.common.base.Strings;
import com.google.common.collect.Maps;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.ListeningExecutorService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qunar.tc.bistoury.agent.common.ResponseHandler;
import qunar.tc.bistoury.agent.common.cpujstack.KvUtils;
import qunar.tc.bistoury.agent.common.cpujstack.ThreadInfo;
import qunar.tc.bistoury.agent.common.kv.KvDb;
import qunar.tc.bistoury.common.JacksonSerializer;
import qunar.tc.bistoury.remoting.netty.AgentRemotingExecutor;
import qunar.tc.bistoury.remoting.netty.Task;

import java.util.Map;
import java.util.concurrent.Callable;

/**
 * @author zhenyu.nie created on 2019 2019/1/9 19:35
 */
public class ThreadInfoTask implements Task {

    private static final Logger logger = LoggerFactory.getLogger(ThreadInfoTask.class);

    private static final ListeningExecutorService agentExecutor = AgentRemotingExecutor.getExecutor();

    private static final TypeReference<Map<String, ThreadInfo>> TYPE_REFERENCE = new TypeReference<Map<String, ThreadInfo>>() {
    };

    private volatile ListenableFuture<Integer> future;

    private final String id;

    private final long maxRunningMs;

    private final KvDb kvDb;

    private final ResponseHandler handler;

    private final String time;

    public ThreadInfoTask(String id, long maxRunningMs, KvDb kvDb, ResponseHandler handler, String time) {
        this.id = id;
        this.maxRunningMs = maxRunningMs;
        this.kvDb = kvDb;
        this.handler = handler;
        this.time = time;
    }

    @Override
    public String getId() {
        return id;
    }

    @Override
    public long getMaxRunningMs() {
        return maxRunningMs;
    }

    @Override
    public ListenableFuture<Integer> execute() {
        this.future = agentExecutor.submit(new Callable<Integer>() {
            @Override
            public Integer call() {
                Map<String, Object> map = Maps.newHashMap();
                map.put("type", "jstackThreads");
                map.put("time", time);
                String threadInfoStr = kvDb.get(KvUtils.getThreadInfoKey(time));
                Map<String, ThreadInfo> threadInfo = Maps.newHashMap();
                if (!Strings.isNullOrEmpty(threadInfoStr)) {
                    threadInfo = JacksonSerializer.deSerialize(threadInfoStr, TYPE_REFERENCE);
                }
                addMomentCpuTimeInfo(threadInfo, time);
                map.put("threadInfo", threadInfo);
                String jstack = kvDb.get(KvUtils.getJStackResultKey(time));
                map.put("jstack", Strings.nullToEmpty(jstack));
                handler.handle(JacksonSerializer.serializeToBytes(map));
                return null;
            }
        });
        return future;
    }

    private void addMomentCpuTimeInfo(Map<String, ThreadInfo> threadInfo, String time) {
        for (ThreadInfo info : threadInfo.values()) {
            String momentCpuTime = kvDb.get(KvUtils.getThreadMomentCpuTimeKey(time, info.getId()));
            if (momentCpuTime == null) {
                momentCpuTime = "0";
            }
            info.setCpuTime(Integer.parseInt(momentCpuTime));
        }
    }

    @Override
    public void cancel() {
        try {
            if (future != null) {
                future.cancel(true);
                future = null;
            }
        } catch (Exception e) {
            logger.error("cancel thread info task error", e);
        }
    }
}
