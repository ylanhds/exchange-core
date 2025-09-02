package exchange.core2.tests.examples;

import exchange.core2.core.ExchangeApi;
import exchange.core2.core.ExchangeCore;
import exchange.core2.core.IEventsHandler;
import exchange.core2.core.SimpleEventsProcessor;
import exchange.core2.core.common.*;
import exchange.core2.core.common.api.*;
import exchange.core2.core.common.api.binary.BatchAddSymbolsCommand;
import exchange.core2.core.common.api.reports.SingleUserReportQuery;
import exchange.core2.core.common.api.reports.SingleUserReportResult;
import exchange.core2.core.common.api.reports.TotalCurrencyBalanceReportQuery;
import exchange.core2.core.common.api.reports.TotalCurrencyBalanceReportResult;
import exchange.core2.core.common.cmd.CommandResultCode;
import exchange.core2.core.common.config.ExchangeConfiguration;
import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;

/**
 * 示例测试类，演示如何使用 ExchangeCore 进行基本的交易操作。
 * 包括用户创建、资金充值、订单提交与撮合、订单簿查询、余额查询等操作。
 */
@Slf4j
public class ITCoreExample {

    /**
     * 示例测试方法，演示完整的交易流程：
     * 1. 初始化交易所核心；
     * 2. 添加交易对；
     * 3. 创建用户并充值；
     * 4. 提交买卖订单并撮合；
     * 5. 查询订单簿和用户余额；
     * 6. 撤销订单并提现；
     * 7. 查询手续费收入。
     *
     * @throws Exception 如果测试过程中发生异常
     */
    @Test
    public void sampleTest() throws Exception {

        // 创建事件处理器，用于处理交易、减少、拒绝等事件
        SimpleEventsProcessor eventsProcessor = new SimpleEventsProcessor(new IEventsHandler() {
            @Override
            public void tradeEvent(TradeEvent tradeEvent) {
                System.out.println("Trade event: " + tradeEvent);
            }

            @Override
            public void reduceEvent(ReduceEvent reduceEvent) {
                System.out.println("Reduce event: " + reduceEvent);
            }

            @Override
            public void rejectEvent(RejectEvent rejectEvent) {
                System.out.println("Reject event: " + rejectEvent);
            }

            @Override
            public void commandResult(ApiCommandResult commandResult) {
                System.out.println("Command result: " + commandResult);
            }

            @Override
            public void orderBook(OrderBook orderBook) {
                System.out.println("OrderBook event: " + orderBook);
            }
        });

        // 使用默认配置构建交易所核心
        ExchangeConfiguration conf = ExchangeConfiguration.defaultBuilder().build();

        // 构建交易所核心实例
        ExchangeCore exchangeCore = ExchangeCore.builder()
                .resultsConsumer(eventsProcessor)
                .exchangeConfiguration(conf)
                .build();

        // 启动交易所核心线程
        exchangeCore.startup();

        // 获取交易所API接口
        ExchangeApi api = exchangeCore.getApi();

        // 定义币种代码常量
        final int currencyCodeXbt = 11; // BTC
        final int currencyCodeLtc = 15; // LTC

        // 定义交易对符号常量
        final int symbolXbtLtc = 241;

        Future<CommandResultCode> future;

        // 创建交易对规格并提交
        CoreSymbolSpecification symbolSpecXbtLtc = CoreSymbolSpecification.builder()
                .symbolId(symbolXbtLtc)         // 交易对ID
                .type(SymbolType.CURRENCY_EXCHANGE_PAIR) // 类型为币币交易对
                .baseCurrency(currencyCodeXbt)    // 基础币种为BTC
                .quoteCurrency(currencyCodeLtc)   // 报价币种为LTC
                .baseScaleK(1_000_000L) // 1手 = 1,000,000 satoshi (0.01 BTC)
                .quoteScaleK(10_000L)   // 1价格步长 = 10,000 litoshi
                .takerFee(1900L)        // 吃单手续费 1900 litoshi 每手
                .makerFee(700L)         // 挂单手续费 700 litoshi 每手
                .build();

        future = api.submitBinaryDataAsync(new BatchAddSymbolsCommand(symbolSpecXbtLtc));
        System.out.println("BatchAddSymbolsCommand result: " + future.get());

        // 创建用户 uid=301
        future = api.submitCommandAsync(ApiAddUser.builder()
                .uid(301L)
                .build());
        System.out.println("ApiAddUser 1 result: " + future.get());

        // 创建用户 uid=302
        future = api.submitCommandAsync(ApiAddUser.builder()
                .uid(302L)
                .build());
        System.out.println("ApiAddUser 2 result: " + future.get());

        // 用户301充值20 LTC
        future = api.submitCommandAsync(ApiAdjustUserBalance.builder()
                .uid(301L)
                .currency(currencyCodeLtc)
                .amount(2_000_000_000L) // 20 LTC = 2,000,000,000 litoshi
                .transactionId(1L)
                .build());
        System.out.println("ApiAdjustUserBalance 1 result: " + future.get());

        // 用户302充值0.10 BTC
        future = api.submitCommandAsync(ApiAdjustUserBalance.builder()
                .uid(302L)
                .currency(currencyCodeXbt)
                .amount(10_000_000L) // 0.1 BTC = 10,000,000 satoshi
                .transactionId(2L)
                .build());
        System.out.println("ApiAdjustUserBalance 2 result: " + future.get());

        // 用户301提交买单（GTC订单）
        future = api.submitCommandAsync(ApiPlaceOrder.builder()
                .uid(301L)
                .orderId(5001L)
                .price(15_400L) // 1.54 LTC/BTC，价格步长为10000，所以是15400
                .reservePrice(15_600L) // 预留价格上限
                .size(12L) // 12手
                .action(OrderAction.BID) // 买入
                .orderType(OrderType.GTC) // GTC订单
                .symbol(symbolXbtLtc)
                .build());
        System.out.println("ApiPlaceOrder 1 result: " + future.get());

        // 用户302提交卖单（IOC订单）
        future = api.submitCommandAsync(ApiPlaceOrder.builder()
                .uid(302L)
                .orderId(5002L)
                .price(15_250L) // 1.525 LTC/BTC
                .size(10L) // 10手
                .action(OrderAction.ASK) // 卖出
                .orderType(OrderType.IOC) // IOC订单
                .symbol(symbolXbtLtc)
                .build());
        System.out.println("ApiPlaceOrder 2 result: " + future.get());

        // 请求订单簿数据
        CompletableFuture<L2MarketData> orderBookFuture = api.requestOrderBookAsync(symbolXbtLtc, 10);
        System.out.println("ApiOrderBookRequest result: " + orderBookFuture.get());

        // 用户301移动订单价格至1.53 LTC/BTC
        future = api.submitCommandAsync(ApiMoveOrder.builder()
                .uid(301L)
                .orderId(5001L)
                .newPrice(15_300L) // 新价格
                .symbol(symbolXbtLtc)
                .build());
        System.out.println("ApiMoveOrder 2 result: " + future.get());

        // 用户301撤销剩余订单
        future = api.submitCommandAsync(ApiCancelOrder.builder()
                .uid(301L)
                .orderId(5001L)
                .symbol(symbolXbtLtc)
                .build());
        System.out.println("ApiCancelOrder 2 result: " + future.get());

        // 查询用户301账户余额
        Future<SingleUserReportResult> report1 = api.processReport(new SingleUserReportQuery(301), 0);
        System.out.println("SingleUserReportQuery 1 accounts: " + report1.get().getAccounts());

        // 查询用户302账户余额
        Future<SingleUserReportResult> report2 = api.processReport(new SingleUserReportQuery(302), 0);
        System.out.println("SingleUserReportQuery 2 accounts: " + report2.get().getAccounts());

        // 用户301提现0.1 BTC
        future = api.submitCommandAsync(ApiAdjustUserBalance.builder()
                .uid(301L)
                .currency(currencyCodeXbt)
                .amount(-10_000_000L) // 提现0.1 BTC
                .transactionId(3L)
                .build());
        System.out.println("ApiAdjustUserBalance 1 result: " + future.get());

        // 查询平台手续费收入
        Future<TotalCurrencyBalanceReportResult> totalsReport = api.processReport(new TotalCurrencyBalanceReportQuery(), 0);
        System.out.println("LTC fees collected: " + totalsReport.get().getFees().get(currencyCodeLtc));
    }
}
