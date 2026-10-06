package com.chemera.server.stats;

import org.apache.ibatis.executor.statement.StatementHandler;
import org.apache.ibatis.plugin.Interceptor;
import org.apache.ibatis.plugin.Intercepts;
import org.apache.ibatis.plugin.Invocation;
import org.apache.ibatis.plugin.Signature;
import org.springframework.stereotype.Component;

import java.sql.Connection;

/**
 * 数一条 SQL 发了没有（G8 的"单次意图 ≤6 条"那条回归就靠它）。
 *
 * <p>为什么卡在 {@code StatementHandler.prepare} 而不是 {@code Executor.query/update}：
 * prepare 是"真的要到数据库去一趟"那一刻，分页、嵌套 select、MyBatis 内部转手都各算一次，
 * 而 Executor 层的两个方法签名重载多、缓存命中时根本不发语句，数出来的"条数"会偏小——
 * 这条指标的意义正是"谁在给一次点击加查询"，宁可数得实。
 *
 * <p>只做计数，不改行为：任何异常都吞掉，观测面把自己弄挂接口是不可接受的。
 */
@Component
@Intercepts(@Signature(type = StatementHandler.class, method = "prepare", args = {Connection.class, Integer.class}))
public class SqlCountInterceptor implements Interceptor {

    @Override
    public Object intercept(Invocation inv) throws Throwable {
        SqlCounter.inc();
        return inv.proceed();
    }
}
