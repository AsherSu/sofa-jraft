/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.alipay.sofa.jraft;

import com.alipay.sofa.jraft.closure.LoadSnapshotClosure;
import com.alipay.sofa.jraft.closure.SaveSnapshotClosure;
import com.alipay.sofa.jraft.entity.LeaderChangeContext;
import com.alipay.sofa.jraft.error.RaftException;
import com.alipay.sofa.jraft.option.FSMCallerOptions;
import com.alipay.sofa.jraft.util.Describer;

/**
 * 有限状态机调用者 (Finite state machine caller)。
 * 它是 Raft 核心引擎与用户自定义业务状态机（StateMachine）之间的桥梁。
 * 负责将日志应用、快照保存/加载、Leader 状态变更等事件通过内部队列（Disruptor）异步分发给状态机执行。
 *
 * @author boyan (boyan@alibaba-inc.com)
 *
 * 2018-Apr-03 11:07:52 AM
 */
public interface FSMCaller extends Lifecycle<FSMCallerOptions>, Describer {

    /**
     * 监听 lastAppliedLogIndex（最后一次应用到状态机的日志索引）更新事件的监听器。
     *
     * @author dennis
     */
    interface LastAppliedLogIndexListener {

        /**
         * 当 lastAppliedLogIndex 更新时被回调。
         *
         * @param lastAppliedLogIndex 最后被应用到状态机的日志索引
         */
        void onApplied(final long lastAppliedLogIndex);
    }

    /**
     * 判断当前执行的线程，是否是专门用来调用业务状态机（FSM）回调方法的后台线程。
     * （这通常用来做安全检查，防止在非 FSM 线程中执行了不该执行的状态机操作）
     * * @return 如果是 FSM 线程返回 true
     */
    boolean isRunningOnFSMThread();

    /**
     * 添加一个 lastAppliedLogIndex 更新监听器。
     */
    void addLastAppliedLogIndexListener(final LastAppliedLogIndexListener listener);

    /**
     * 【核心方法：日志提交】
     * 当日志条目在 Raft 集群中达成多数派共识（被 Committed）时调用。
     * 注意：这里只是把任务放进 FSMCaller 的异步队列中，并不是立刻执行业务逻辑。
     *
     * @param committedIndex 已提交的日志索引
     * @return 成功放入队列返回 true
     */
    boolean onCommitted(final long committedIndex);

    /**
     * 给定指定的 <tt>requiredCapacity</tt>（所需容量），检查内部的异步队列（如 Disruptor RingBuffer）
     * 是否还有足够的空间来接收新的 FSM 任务。
     * * @param requiredCapacity 需要的容量大小
     * @return 如果空间充足返回 true
     */
    public boolean hasAvailableCapacity(final int requiredCapacity);

    /**
     * 【核心方法：加载快照】
     * 当节点落后太多，收到 Leader 发来的快照时，调用此方法通知状态机加载快照数据。
     *
     * @param done 异步回调句柄
     * @return 成功发出加载指令返回 true
     */
    boolean onSnapshotLoad(final LoadSnapshotClosure done);

    /**
     * 同步触发保存快照的操作。
     * 强制要求：这个方法【必须】在状态机业务线程内部调用，不能跨线程调用。
     * * @param done 异步回调句柄
     */
    public void onSnapshotSaveSync(SaveSnapshotClosure done);

    /**
     * 【核心方法：保存快照】
     * 异步触发保存快照的操作。通常由定时器触发，通知业务状态机把当前内存数据序列化存盘。
     *
     * @param done 异步回调句柄
     * @return 成功发出保存指令返回 true
     */
    boolean onSnapshotSave(final SaveSnapshotClosure done);

    /**
     * 【状态变更】当当前节点卸任 Leader（停止作为 Leader 运行）时调用。
     * 业务层通常在这里清理旧 Leader 的专属状态（如释放分布式锁、拒绝挂起的客户端请求）。
     *
     * @param status 状态/错误信息
     * @return 成功发出指令返回 true
     */
    boolean onLeaderStop(final Status status);

    /**
     * 【状态变更】当当前节点竞选成功，开始作为新 Leader 运行时调用。
     *
     * @param term 当前的新任期号
     * @return 成功发出指令返回 true
     */
    boolean onLeaderStart(final long term);

    /**
     * 【状态变更】当当前节点开始跟随一个新的 Leader 时调用。
     * 业务层可以通过 ctx 获取新 Leader 的 IP 地址，从而将客户端的写请求重定向给它。
     *
     * @param ctx Leader 变更上下文（包含新老大是谁、当前任期等）
     * @return 成功发出指令返回 true
     */
    boolean onStartFollowing(final LeaderChangeContext ctx);

    /**
     * 【状态变更】当当前节点停止跟随当前 Leader 时调用（比如发生网络分区，或者开始新一轮选举）。
     *
     * @param ctx Leader 变更上下文
     * @return 成功发出指令返回 true
     */
    boolean onStopFollowing(final LeaderChangeContext ctx);

    /**
     * 当发生严重的 Raft 级别错误时调用，通知状态机进行错误处理或停机保护。
     *
     * @param error 错误信息
     * @return 成功发出指令返回 true
     */
    boolean onError(final RaftException error);

    /**
     * 获取状态机当前最后一次实际执行（Apply）的日志索引。
     * 业务状态机真实的数据进度。
     */
    long getLastAppliedIndex();

    /**
     * 获取当前 Raft 集群已经达成共识（Commit）的最新日志索引。
     * 正常情况下：LastCommittedIndex >= LastAppliedIndex（即提交进度永远领先或等于执行进度）。
     */
    long getLastCommittedIndex();

    /**
     * 在节点关闭（shutdown）后调用，阻塞当前线程，直到 FSMCaller 的后台处理线程完全停止。
     *
     * @throws InterruptedException 如果在等待时当前线程被中断
     */
    void join() throws InterruptedException;
}