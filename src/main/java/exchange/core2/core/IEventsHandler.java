package exchange.core2.core;

import exchange.core2.core.common.OrderAction;
import exchange.core2.core.common.api.ApiCommand;
import exchange.core2.core.common.cmd.CommandResultCode;
import lombok.Data;

import java.util.List;

/**
 * 为非低延迟关键应用提供的便捷事件处理器接口。<br>
 * 自定义处理器实现应附加到 SimpleEventProcessor。<br>
 * 处理器方法按以下顺序从单线程调用：
 * <table summary="执行顺序">
 * <tr><td>1. </td><td> commandResult</td></tr>
 * <tr><td>2A.  </td><td> 可选的 reduceEvent <td> 可选的 tradeEvent</td></tr>
 * <tr><td>2B. </td><td> <td>可选的 rejectEvent</td></tr>
 * <tr><td>3. </td><td> orderBook - 对于 ApiOrderBookRequest 是必需的，对于其他命令是可选的</td></tr>
 * </table>
 * 如果任何处理器抛出异常，事件处理将立即停止 - 如有必要，您应考虑将逻辑包装在 try-catch 块中。
 */
public interface IEventsHandler {

    /**
     * 每次命令执行后调用此方法。
     *
     * @param commandResult - 描述原始命令、结果代码和分配的序列号的不可变对象
     */
    void commandResult(ApiCommandResult commandResult);

    /**
     * 如果订单执行导致一个或多个交易，则调用此方法。
     *
     * @param tradeEvent - 描述事件详情的不可变对象
     */
    void tradeEvent(TradeEvent tradeEvent);

    /**
     * 如果 IoC 订单无法在提供的价格限制内匹配，则调用此方法。
     *
     * @param rejectEvent - 描述事件详情的不可变对象
     */
    void rejectEvent(RejectEvent rejectEvent);

    /**
     * 如果成功执行了取消或减少命令，则调用此方法。
     *
     * @param reduceEvent - 描述事件详情的不可变对象
     */
    void reduceEvent(ReduceEvent reduceEvent);

    /**
     * 当匹配引擎将订单簿快照（L2MarketData）附加到命令时调用此方法。
     * 对于 ApiOrderBookRequest 总是会发生，对于其他命令有时会发生。
     *
     * @param orderBook - 包含 L2 订单簿快照的不可变对象
     */
    void orderBook(OrderBook orderBook);

    /**
     * API 命令执行结果类
     */
    @Data
    class ApiCommandResult {
        /**
         * 原始命令对象
         */
        public final ApiCommand command;

        /**
         * 命令执行结果代码
         */
        public final CommandResultCode resultCode;

        /**
         * 分配的序列号
         */
        public final long seq;
    }

    /**
     * 交易事件类
     */
    @Data
    class TradeEvent {
        /**
         * 交易对符号
         */
        public final int symbol;

        /**
         * 总交易量
         */
        public final long totalVolume;

        /**
         * 吃单方订单ID
         */
        public final long takerOrderId;

        /**
         * 吃单方用户ID
         */
        public final long takerUid;

        /**
         * 吃单方操作类型（买入/卖出）
         */
        public final OrderAction takerAction;

        /**
         * 吃单是否已完成
         */
        public final boolean takeOrderCompleted;

        /**
         * 时间戳
         */
        public final long timestamp;

        /**
         * 交易列表
         */
        public final List<Trade> trades;
    }

    /**
     * 单个交易信息类
     */
    @Data
    class Trade {
        /**
         * 挂单方订单ID
         */
        public final long makerOrderId;

        /**
         * 挂单方用户ID
         */
        public final long makerUid;

        /**
         * 挂单是否已完成
         */
        public final boolean makerOrderCompleted;

        /**
         * 交易价格
         */
        public final long price;

        /**
         * 交易数量
         */
        public final long volume;
    }

    /**
     * 订单减少事件类
     */
    @Data
    class ReduceEvent {
        /**
         * 交易对符号
         */
        public final int symbol;

        /**
         * 减少的订单数量
         */
        public final long reducedVolume;

        /**
         * 订单是否已完成
         */
        public final boolean orderCompleted;

        /**
         * 订单价格
         */
        public final long price;

        /**
         * 订单ID
         */
        public final long orderId;

        /**
         * 用户ID
         */
        public final long uid;

        /**
         * 时间戳
         */
        public final long timestamp;
    }

    /**
     * 订单拒绝事件类
     */
    @Data
    class RejectEvent {
        /**
         * 交易对符号
         */
        public final int symbol;

        /**
         * 被拒绝的订单数量
         */
        public final long rejectedVolume;

        /**
         * 订单价格
         */
        public final long price;

        /**
         * 订单ID
         */
        public final long orderId;

        /**
         * 用户ID
         */
        public final long uid;

        /**
         * 时间戳
         */
        public final long timestamp;
    }

    /**
     * 命令执行结果类
     */
    @Data
    class CommandExecutionResult {
        /**
         * 交易对符号
         */
        public final int symbol;

        /**
         * 交易数量
         */
        public final long volume;

        /**
         * 交易价格
         */
        public final long price;

        /**
         * 订单ID
         */
        public final long orderId;

        /**
         * 用户ID
         */
        public final long uid;

        /**
         * 时间戳
         */
        public final long timestamp;
    }

    /**
     * 订单簿类
     */
    @Data
    class OrderBook {
        /**
         * 交易对符号
         */
        public final int symbol;

        /**
         * 卖单列表
         */
        public final List<OrderBookRecord> asks;

        /**
         * 买单列表
         */
        public final List<OrderBookRecord> bids;

        /**
         * 时间戳
         */
        public final long timestamp;
    }

    /**
     * 订单簿记录类
     */
    @Data
    class OrderBookRecord {
        /**
         * 价格
         */
        public final long price;

        /**
         * 数量
         */
        public final long volume;

        /**
         * 订单数量
         */
        public final int orders;
    }
}
