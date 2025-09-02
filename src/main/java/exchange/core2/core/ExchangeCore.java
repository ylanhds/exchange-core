/*
 * Copyright 2019 Maksim Zheravin
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package exchange.core2.core;

import com.lmax.disruptor.EventHandler;
import com.lmax.disruptor.EventTranslator;
import com.lmax.disruptor.RingBuffer;
import com.lmax.disruptor.TimeoutException;
import com.lmax.disruptor.dsl.Disruptor;
import com.lmax.disruptor.dsl.EventHandlerGroup;
import com.lmax.disruptor.dsl.ProducerType;
import exchange.core2.core.common.CoreWaitStrategy;
import exchange.core2.core.common.cmd.CommandResultCode;
import exchange.core2.core.common.cmd.OrderCommand;
import exchange.core2.core.common.cmd.OrderCommandType;
import exchange.core2.core.common.config.ExchangeConfiguration;
import exchange.core2.core.common.config.PerformanceConfiguration;
import exchange.core2.core.common.config.SerializationConfiguration;
import exchange.core2.core.orderbook.IOrderBook;
import exchange.core2.core.processors.*;
import exchange.core2.core.processors.journaling.ISerializationProcessor;
import lombok.Builder;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.function.ObjLongConsumer;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * 主要的交易所核心类。
 * 构建配置并启动Disruptor。
 */
@Slf4j
public final class ExchangeCore {

    /**
     * Disruptor实例，用于高性能事件处理
     */
    private final Disruptor<OrderCommand> disruptor;

    /**
     * 环形缓冲区，用于存储订单命令
     */
    private final RingBuffer<OrderCommand> ringBuffer;

    /**
     * 交易所API接口
     */
    private final ExchangeApi api;

    /**
     * 序列化处理器，用于持久化和恢复状态
     */
    private final ISerializationProcessor serializationProcessor;

    /**
     * 交易所配置
     */
    private final ExchangeConfiguration exchangeConfiguration;

    // 核心只能启动和停止一次
    private boolean started = false;
    private boolean stopped = false;

    // 是否启用MatcherTradeEvent对象池
    public static final boolean EVENTS_POOLING = false;

    /**
     * 交易所核心构造函数。
     *
     * @param resultsConsumer       - 已处理命令的自定义消费者
     * @param exchangeConfiguration - 交易所配置
     */
    @Builder
    public ExchangeCore(final ObjLongConsumer<OrderCommand> resultsConsumer,
                        final ExchangeConfiguration exchangeConfiguration) {

        log.debug("根据配置构建交易所核心: {}", exchangeConfiguration);

        this.exchangeConfiguration = exchangeConfiguration;

        // 获取性能配置
        final PerformanceConfiguration perfCfg = exchangeConfiguration.getPerformanceCfg();

        // 获取环形缓冲区大小
        final int ringBufferSize = perfCfg.getRingBufferSize();

        // 获取线程工厂
        final ThreadFactory threadFactory = perfCfg.getThreadFactory();

        // 获取等待策略
        final CoreWaitStrategy coreWaitStrategy = perfCfg.getWaitStrategy();

        // 创建Disruptor实例
        this.disruptor = new Disruptor<>(
                OrderCommand::new,
                ringBufferSize,
                threadFactory,
                ProducerType.MULTI, // 多个网关线程写入
                coreWaitStrategy.getDisruptorWaitStrategyFactory().get());

        // 获取环形缓冲区
        this.ringBuffer = disruptor.getRingBuffer();

        // 创建交易所API实例
        this.api = new ExchangeApi(ringBuffer, perfCfg.getBinaryCommandsLz4CompressorFactory().get());

        // 获取订单簿工厂
        final IOrderBook.OrderBookFactory orderBookFactory = perfCfg.getOrderBookFactory();

        // 获取匹配引擎和风险引擎数量
        final int matchingEnginesNum = perfCfg.getMatchingEnginesNum();
        final int riskEnginesNum = perfCfg.getRiskEnginesNum();

        // 获取序列化配置
        final SerializationConfiguration serializationCfg = exchangeConfiguration.getSerializationCfg();

        // 创建序列化处理器
        serializationProcessor = serializationCfg.getSerializationProcessorFactory().apply(exchangeConfiguration);

        // 创建共享对象池
        final int poolInitialSize = (matchingEnginesNum + riskEnginesNum) * 8;
        final int chainLength = EVENTS_POOLING ? 1024 : 1;
        final SharedPool sharedPool = new SharedPool(poolInitialSize * 4, poolInitialSize, chainLength);

        // 创建并附加异常处理器
        final DisruptorExceptionHandler<OrderCommand> exceptionHandler = new DisruptorExceptionHandler<>("main", (ex, seq) -> {
            log.error("在序列={}上抛出异常", seq, ex);
            // TODO 在发布时重新抛出异常
            ringBuffer.publishEvent(SHUTDOWN_SIGNAL_TRANSLATOR);
            disruptor.shutdown();
        });

        // 设置默认异常处理器
        disruptor.setDefaultExceptionHandler(exceptionHandler);

        // 建议CompletableFuture使用与Disruptor相同的CPU插槽
        final ExecutorService loaderExecutor = Executors.newFixedThreadPool(matchingEnginesNum + riskEnginesNum, threadFactory);

        // 开始创建匹配引擎
        final Map<Integer, CompletableFuture<MatchingEngineRouter>> matchingEngineFutures = IntStream.range(0, matchingEnginesNum)
                .boxed()
                .collect(Collectors.toMap(
                        shardId -> shardId,
                        shardId -> CompletableFuture.supplyAsync(
                                () -> new MatchingEngineRouter(shardId, matchingEnginesNum, serializationProcessor, orderBookFactory, sharedPool, exchangeConfiguration),
                                loaderExecutor)));

        // TODO 在将要执行的同一线程中创建处理器??

        // 开始创建风险引擎
        final Map<Integer, CompletableFuture<RiskEngine>> riskEngineFutures = IntStream.range(0, riskEnginesNum)
                .boxed()
                .collect(Collectors.toMap(
                        shardId -> shardId,
                        shardId -> CompletableFuture.supplyAsync(
                                () -> new RiskEngine(shardId, riskEnginesNum, serializationProcessor, sharedPool, exchangeConfiguration),
                                loaderExecutor)));

        // 创建匹配引擎事件处理器
        final EventHandler<OrderCommand>[] matchingEngineHandlers = matchingEngineFutures.values().stream()
                .map(CompletableFuture::join)
                .map(mer -> (EventHandler<OrderCommand>) (cmd, seq, eob) -> mer.processOrder(seq, cmd))
                .toArray(ExchangeCore::newEventHandlersArray);

        // 获取风险引擎映射
        final Map<Integer, RiskEngine> riskEngines = riskEngineFutures.entrySet().stream()
                .collect(Collectors.toMap(
                        Map.Entry::getKey,
                        entry -> entry.getValue().join()));


        // 创建两步处理器列表
        final List<TwoStepMasterProcessor> procR1 = new ArrayList<>(riskEnginesNum);
        final List<TwoStepSlaveProcessor> procR2 = new ArrayList<>(riskEnginesNum);

        // 1. 分组处理器 (G)
        final EventHandlerGroup<OrderCommand> afterGrouping =
                disruptor.handleEventsWith((rb, bs) -> new GroupingProcessor(rb, rb.newBarrier(bs), perfCfg, coreWaitStrategy, sharedPool));

        // 2. [日志记录 (J)] 与 风险持有 (R1) + 匹配引擎 (ME) 并行处理

        boolean enableJournaling = serializationCfg.isEnableJournaling();
        final EventHandler<OrderCommand> jh = enableJournaling ? serializationProcessor::writeToJournal : null;

        if (enableJournaling) {
            afterGrouping.handleEventsWith(jh);
        }

        // 为每个风险引擎添加风险持有处理器
        riskEngines.forEach((idx, riskEngine) -> afterGrouping.handleEventsWith(
                (rb, bs) -> {
                    final TwoStepMasterProcessor r1 = new TwoStepMasterProcessor(rb, rb.newBarrier(bs), riskEngine::preProcessCommand, exceptionHandler, coreWaitStrategy, "R1_" + idx);
                    procR1.add(r1);
                    return r1;
                }));

        // 在风险持有处理器之后处理匹配引擎
        disruptor.after(procR1.toArray(new TwoStepMasterProcessor[0])).handleEventsWith(matchingEngineHandlers);

        // 3. 匹配引擎 (ME) 之后的风险释放 (R2)
        final EventHandlerGroup<OrderCommand> afterMatchingEngine = disruptor.after(matchingEngineHandlers);

        // 为每个风险引擎添加风险释放处理器
        riskEngines.forEach((idx, riskEngine) -> afterMatchingEngine.handleEventsWith(
                (rb, bs) -> {
                    final TwoStepSlaveProcessor r2 = new TwoStepSlaveProcessor(rb, rb.newBarrier(bs), riskEngine::handlerRiskRelease, exceptionHandler, "R2_" + idx);
                    procR2.add(r2);
                    return r2;
                }));


        // 4. 结果处理器 (E) 在匹配引擎 (ME) + [日志记录 (J)] 之后
        final EventHandlerGroup<OrderCommand> mainHandlerGroup = enableJournaling
                ? disruptor.after(arraysAddHandler(matchingEngineHandlers, jh))
                : afterMatchingEngine;

        // 创建结果处理器
        final ResultsHandler resultsHandler = new ResultsHandler(resultsConsumer);

        // 处理最终结果
        mainHandlerGroup.handleEventsWith((cmd, seq, eob) -> {
            resultsHandler.onEvent(cmd, seq, eob);
            api.processResult(seq, cmd); // TODO 慢 ?(volatile操作)
        });

        // 将从处理器附加到主处理器
        IntStream.range(0, riskEnginesNum).forEach(i -> procR1.get(i).setSlaveProcessor(procR2.get(i)));

        // 关闭加载执行器
        try {
            loaderExecutor.shutdown();
            loaderExecutor.awaitTermination(1, TimeUnit.SECONDS);
        } catch (InterruptedException ex) {
            throw new RuntimeException(ex);
        }
    }

    /**
     * 启动Disruptor
     */
    public synchronized void startup() {
        if (!started) {
            log.debug("启动Disruptor...");
            disruptor.start();
            started = true;

            // 重放日志并启用日志记录
            serializationProcessor.replayJournalFullAndThenEnableJouraling(exchangeConfiguration.getInitStateCfg(), api);
        }
    }

    /**
     * 提供ExchangeApi实例。
     *
     * @return ExchangeApi实例（始终是同一对象）
     */
    public ExchangeApi getApi() {
        return api;
    }

    /**
     * 关闭信号事件转换器
     */
    private static final EventTranslator<OrderCommand> SHUTDOWN_SIGNAL_TRANSLATOR = (cmd, seq) -> {
        cmd.command = OrderCommandType.SHUTDOWN_SIGNAL;
        cmd.resultCode = CommandResultCode.NEW;
    };

    /**
     * 关闭Disruptor
     */
    public synchronized void shutdown() {
        shutdown(-1, TimeUnit.MILLISECONDS);
    }

    /**
     * 如果交易所核心无法正常停止，将抛出IllegalStateException。
     *
     * @param timeout  等待所有事件处理完成的时间。<code>-1</code>表示无限超时
     * @param timeUnit 超时时间的单位
     */
    public synchronized void shutdown(final long timeout, final TimeUnit timeUnit) {
        if (!stopped) {
            stopped = true;
            // TODO 首先停止接受新事件
            try {
                log.info("关闭Disruptor...");
                ringBuffer.publishEvent(SHUTDOWN_SIGNAL_TRANSLATOR);
                disruptor.shutdown(timeout, timeUnit);
                log.info("Disruptor已停止");
            } catch (TimeoutException e) {
                throw new IllegalStateException("无法正常停止Disruptor。可能并非所有事件都已执行。");
            }
        }
    }

    /**
     * 向事件处理器数组添加额外的处理器
     *
     * @param handlers 原始事件处理器数组
     * @param extraHandler 额外的事件处理器
     * @return 包含额外处理器的新数组
     */
    private static EventHandler<OrderCommand>[] arraysAddHandler(EventHandler<OrderCommand>[] handlers, EventHandler<OrderCommand> extraHandler) {
        final EventHandler<OrderCommand>[] result = Arrays.copyOf(handlers, handlers.length + 1);
        result[handlers.length] = extraHandler;
        return result;
    }

    /**
     * 创建指定大小的事件处理器数组
     *
     * @param size 数组大小
     * @return 事件处理器数组
     */
    @SuppressWarnings(value = {"unchecked"})
    private static EventHandler<OrderCommand>[] newEventHandlersArray(int size) {
        return new EventHandler[size];
    }
}
