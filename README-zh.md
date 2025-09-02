# exchange-core
[![Build Status](https://travis-ci.org/mzheravin/exchange-core.svg?branch=master)](https://travis-ci.org/mzheravin/exchange-core)
[![Javadocs](https://www.javadoc.io/badge/exchange.core2/exchange-core.svg)](https://www.javadoc.io/doc/exchange.core2/exchange-core)
[![Language grade: Java](https://img.shields.io/lgtm/grade/java/g/mzheravin/exchange-core.svg?logo=lgtm&logoWidth=18)](https://lgtm.com/projects/g/mzheravin/exchange-core/context:java)
[![][license img]][license]

Exchange-core 是一个**开源的市场交易核心引擎**，基于
[LMAX Disruptor](https://github.com/LMAX-Exchange/disruptor),
[Eclipse Collections](https://www.eclipse.org/collections/) (ex. Goldman Sachs GS Collections),
[Real Logic Agrona](https://github.com/real-logic/agrona),
[OpenHFT Chronicle-Wire](https://github.com/OpenHFT/Chronicle-Wire),
[LZ4 Java](https://github.com/lz4/lz4-java),
and [Adaptive Radix Trees](https://db.in.tum.de/~leis/papers/ART.pdf).

Exchange-core 包含：
- 订单匹配引擎
- 风险控制和账户模块
- 磁盘日志记录和快照模块
- 交易、管理和报告 API

专为高可扩展性和在高负载条件下 24/7 无停顿运行而设计，并提供低延迟响应：
- 300 万用户，总计 1000 万个账户
- 10 万个订单簿（交易对），总计 400 万个挂单
- 在每秒超过 100 万次操作的吞吐量下，目标端到端最差延迟小于 1 毫秒
- 大型市价单的匹配时间约为 150 纳秒

在已有 10 年历史的硬件（Intel® Xeon® X5690）上，单一订单簿配置能够以中等的延迟 degradation 处理每秒 500 万次操作：

|速率|50.0%|90.0%|95.0%|99.0%|99.9%|99.99%|最差|
|----|-----|-----|-----|-----|-----|------|-----|
|125K|0.6µs|0.9µs|1.0µs|1.4µs|4µs  |24µs  |41µs |
|250K|0.6µs|0.9µs|1.0µs|1.4µs|9µs  |27µs  |41µs |
|500K|0.6µs|0.9µs|1.0µs|1.6µs|14µs |29µs  |42µs |
|  1M|0.5µs|0.9µs|1.2µs|4µs  |22µs |31µs  |45µs |
|  2M|0.5µs|1.2µs|3.9µs|10µs |30µs |39µs  |60µs |
|  3M|0.7µs|3.6µs|6.2µs|15µs |36µs |45µs  |60µs |
|  4M|1.0µs|6.0µs|9µs  |25µs |45µs |55µs  |70µs |
|  5M|1.5µs|9.5µs|16µs |42µs |150µs|170µs |190µs|
|  6M|5µs  |30µs |45µs |300µs|500µs|520µs |540µs|
|  7M|60µs |1.3ms|1.5ms|1.8ms|1.9ms|1.9ms |1.9ms|

![延迟 HDR 直方图](hdr-histogram.png)

基准测试配置：
- 单一交易对订单簿。
- 3,000,000 条入站消息分布如下：9% GTC 订单，3% IOC 订单，6% 取消命令，82% 移动命令。约 6% 的消息会触发一笔或多笔交易。
- 1,000 个活跃用户账户。
- 平均约有 1,000 个限价单处于活跃状态，分布在约 750 个不同的价格档位。
- 延迟结果仅针对风险处理和订单匹配。其他如网络接口延迟、IPC、日志记录等不包括在内。
- 测试数据不是突发性的，意味着命令之间的间隔是恒定的（根据目标吞吐量，间隔在 0.2~8µs 之间）。
- 整个测试过程中 BBO（最佳买卖报价）价格没有显著变化。没有雪崩订单。
- 延迟基准测试没有协调遗漏（coordinated omission）效应。任何处理延迟都会影响后续消息的测量。
- 在每个基准测试周期（3,000,000 条消息）运行之前/之后触发 GC（垃圾回收）。
- RHEL 7.5，网络延迟优化的 tuned-adm 配置文件，双路 X5690 6 核 3.47GHz，一个 CPU 插座被隔离且为 tickless，Spectre/Meltdown 保护已禁用。
- Java 版本 8u192，更新的 Java 8 版本可能存在[性能缺陷](https://bugs.java.com/bugdatabase/view_bug.do?bug_id=JDK-8221355)

### 特性
- 为高频交易（HFT）优化。优先级是限价单移动操作的平均延迟（目前约为 ~0.5µs）。取消操作约 ~0.7µs，下新订单约 ~1.0µs；
- 账户数据和订单簿的工作状态存储在内存中。
- 事件溯源（Event-sourcing） - 支持磁盘日志记录和日志重放、状态快照（序列化）和恢复操作，LZ4 压缩。
- 无锁且无竞争的订单匹配和风险控制算法。
- 无浮点运算，不可能出现有效位数损失。
- 匹配引擎和风险控制操作是原子性的和确定性的。
- 流水线式多核处理（基于 LMAX Disruptor）：每个 CPU 核心负责特定的处理阶段、用户账户分片或交易对订单簿分片。
- 两种不同的风险处理模式（按交易对指定）：直接交易（direct-exchange）和保证金交易（margin-trade）。
- 挂单/吃单费用（以报价货币单位定义）。
- 两种订单簿实现：简单实现（"Naive"）和性能实现（"Direct"）。
- 订单类型：立即成交或取消（IOC），一直有效直至取消（GTC），全部成交或取消预算（FOK-B）。
- 测试 - 单元测试、集成测试、压力测试、完整性/一致性测试。
- 低 GC 压力，对象池化，单一环形缓冲区。
- 线程亲和性（需要 JNA）。
- 用户暂停/恢复操作（减少内存消耗）。
- 核心报告 API（用户余额、未平仓合约）。

### 安装
1.  运行 `mvn install` 将库安装到您的 Maven 本地仓库中。
2.  在您项目的 `pom.xml` 中添加以下 Maven 依赖项：
```
<dependency>
    <groupId>exchange.core2</groupId>
    <artifactId>exchange-core</artifactId>
    <version>0.5.3</version>
</dependency>
```

或者，您可以克隆此存储库并运行[示例测试](https://github.com/mzheravin/exchange-core/tree/master/src/test/java/exchange/core2/tests/examples/ITCoreExample.java)。

### 使用示例
创建并启动空的交易核心：
```java
// 简单的异步事件处理器
SimpleEventsProcessor eventsProcessor = new SimpleEventsProcessor(new IEventsHandler() {
    @Override
    public void tradeEvent(TradeEvent tradeEvent) {
        System.out.println("成交事件: " + tradeEvent);
    }

    @Override
    public void reduceEvent(ReduceEvent reduceEvent) {
        System.out.println("减量事件: " + reduceEvent);
    }

    @Override
    public void rejectEvent(RejectEvent rejectEvent) {
        System.out.println("拒绝事件: " + rejectEvent);
    }

    @Override
    public void commandResult(ApiCommandResult commandResult) {
        System.out.println("命令结果: " + commandResult);
    }

    @Override
    public void orderBook(OrderBook orderBook) {
        System.out.println("订单簿事件: " + orderBook);
    }
});

// 默认的交易配置
ExchangeConfiguration conf = ExchangeConfiguration.defaultBuilder().build();

// 无序列化
Supplier<ISerializationProcessor> serializationProcessorFactory = () -> DummySerializationProcessor.INSTANCE;

// 构建交易核心
ExchangeCore exchangeCore = ExchangeCore.builder()
        .resultsConsumer(eventsProcessor)
        .serializationProcessorFactory(serializationProcessorFactory)
        .exchangeConfiguration(conf)
        .build();

// 启动 disruptor 线程
exchangeCore.startup();

// 获取用于发布命令的交易 API
ExchangeApi api = exchangeCore.getApi();
```

创建新的交易对：
```java
// 货币代码常量
final int currencyCodeXbt = 11;
final int currencyCodeLtc = 15;

// 交易对常量
final int symbolXbtLtc = 241;

// 创建交易对规格并发布
CoreSymbolSpecification symbolSpecXbtLtc = CoreSymbolSpecification.builder()
        .symbolId(symbolXbtLtc)         // 交易对 id
        .type(SymbolType.CURRENCY_EXCHANGE_PAIR)
        .baseCurrency(currencyCodeXbt)    // 基础货币 = satoshi (1E-8)
        .quoteCurrency(currencyCodeLtc)   // 报价货币 = litoshi (1E-8)
        .baseScaleK(1_000_000L) // 1 手 = 1M satoshi (0.01 BTC)
        .quoteScaleK(10_000L)   // 1 价格步长 = 10K litoshi
        .takerFee(1900L)        // 吃单费用每手 1900 litoshi
        .makerFee(700L)         // 挂单费用每手 700 litoshi
        .build();

future = api.submitBinaryDataAsync(new BatchAddSymbolsCommand(symbolSpecXbtLtc));
```

创建新用户：
```java
// 创建用户 uid=301
future = api.submitCommandAsync(ApiAddUser.builder()
        .uid(301L)
        .build());

// 创建用户 uid=302
future = api.submitCommandAsync(ApiAddUser.builder()
        .uid(302L)
        .build());
```

执行存款：
```java
// 第一个用户存入 20 LTC
future = api.submitCommandAsync(ApiAdjustUserBalance.builder()
        .uid(301L)
        .currency(currencyCodeLtc)
        .amount(2_000_000_000L)
        .transactionId(1L)
        .build());

// 第二个用户存入 0.10 BTC
future = api.submitCommandAsync(ApiAdjustUserBalance.builder()
        .uid(302L)
        .currency(currencyCodeXbt)
        .amount(10_000_000L)
        .transactionId(2L)
        .build());
```

下单：
```java
// 第一个用户下一直有效直至取消（GTC）的买单（Bid）
// 他假设 BTCLTC 汇率为 1 BTC 兑换 154 LTC
// 1 手（0.01BTC）的买入价是 1.54 LTC => 1_5400_0000 litoshi => 10K * 15_400 (以价格步长表示)
future = api.submitCommandAsync(ApiPlaceOrder.builder()
        .uid(301L)
        .orderId(5001L)
        .price(15_400L)
        .reservePrice(15_600L) // 可以将买单移动到最高 1.56 LTC，而无需替换它
        .size(12L) // 订单大小为 12 手
        .action(OrderAction.BID)
        .orderType(OrderType.GTC) // 一直有效直至取消（Good-till-Cancel）
        .symbol(symbolXbtLtc)
        .build());

// 第二个用户下立即成交或取消（IOC）的卖单（Ask）
// 他假设最差以 1 BTC 兑换 152.5 LTC 的汇率卖出
future = api.submitCommandAsync(ApiPlaceOrder.builder()
        .uid(302L)
        .orderId(5002L)
        .price(15_250L)
        .size(10L) // 订单大小为 10 手
        .action(OrderAction.ASK)
        .orderType(OrderType.IOC) // 立即成交或取消（Immediate-or-Cancel）
        .symbol(symbolXbtLtc)
        .build());
```

请求订单簿：
```java
future = api.requestOrderBookAsync(symbolXbtLtc, 10);
```

GTC 订单操作：
```java
// 第一个用户将剩余订单移动到价格 1.53 LTC
future = api.submitCommandAsync(ApiMoveOrder.builder()
        .uid(301L)
        .orderId(5001L)
        .newPrice(15_300L)
        .symbol(symbolXbtLtc)
        .build());
        
// 第一个用户取消剩余订单
future = api.submitCommandAsync(ApiCancelOrder.builder()
        .uid(301L)
        .orderId(5001L)
        .symbol(symbolXbtLtc)
        .build());
```

检查用户余额和 GTC 订单：
```java
Future<SingleUserReportResult> report = api.processReport(new SingleUserReportQuery(301), 0);
```

检查系统余额：
```java
// 检查收取的费用
Future<TotalCurrencyBalanceReportResult> totalsReport = api.processReport(new TotalCurrencyBalanceReportQuery(), 0);
System.out.println("收取的 LTC 费用: " + totalsReport.get().getFees().get(currencyCodeLtc));
```

### 测试
- 延迟测试: mvn -Dtest=PerfLatency#testLatencyMargin test
- 吞吐量测试: mvn -Dtest=PerfThroughput#testThroughputMargin test
- 间歇性延迟测试 (hiccups test): mvn -Dtest=PerfHiccups#testHiccups test
- 序列化测试: mvn -Dtest=PerfPersistence#testPersistenceMargin test

### 待办事项 (TODOs)
- 市场数据馈送（完整订单日志，L2 市场数据，BBO，成交记录）
- 清算和结算
- 报告
- 集群
- FIX 和 REST API 网关
- 加密货币支付网关
- 更多测试和基准测试
- NUMA 感知和 CPU 布局自定义配置

### 贡献
Exchange-core 是一个开源项目，欢迎贡献！

### 支持
- [Telegram 讨论组 (t.me/exchangecoretalks)](https://t.me/exchangecoretalks)
- [Telegram 新闻频道 (t.me/exchangecore)](https://t.me/exchangecore)

[许可证]:LICENSE.txt
[许可证 img]:https://img.shields.io/badge/License-Apache%202-blue.svg