package com.hqy.account.service.impl;

import com.alibaba.fastjson.JSONObject;
import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.hqy.account.service.AccountService;
import com.hqy.account.service.TccAccountService;
import com.hqy.order.common.entity.Account;
import io.seata.core.context.RootContext;
import io.seata.rm.tcc.api.BusinessActionContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

/**
 * @author qiyuan.hong
 * @version 1.0
 * @date 2022/4/29 14:35
 */
@Slf4j
@Service
public class TccAccountServiceImpl implements TccAccountService {

    @Resource
    private AccountService service;

    /**
     * 用于标记账户交易在当前tcc事务中是否进行过空回滚 防止悬挂。
     */
    private static final Cache<String, Boolean> BLANK_ROLLBACK_CACHE =
            CacheBuilder.newBuilder().initialCapacity(256).expireAfterAccess(1, TimeUnit.HOURS).build();


    @Override
    @Transactional(rollbackFor = Exception.class, propagation = Propagation.REQUIRED)
    public boolean modifyAccount(Account beforeAccount, Account afterAccount) {
        //防悬挂控制
        if (Boolean.TRUE.equals(BLANK_ROLLBACK_CACHE.getIfPresent(beforeAccount.getId() + ""))) {
            return false;
        }
        log.info("xid = {}", RootContext.getXID());
        //TODO 同理 正式业务中 一定要加锁
        return service.update(afterAccount);
    }

    @Override
    public boolean commitTcc(BusinessActionContext context) {
        return true;
    }

    @Override
    public boolean cancel(BusinessActionContext context) {
        try {
            JSONObject beforeAccount = (JSONObject) context.getActionContext().get("beforeAccount");
            Account account = beforeAccount.toJavaObject(Account.class);
            //查询库存在不在
            Account accountFromDb = service.queryById(account.getId());
            if (Objects.isNull(accountFromDb)) {
                //标记已进行空回滚 悬挂控制
                BLANK_ROLLBACK_CACHE.put(account.getId() + "", true);
                //空回滚
                return true;
            }
            //TODO 正常下单业务中 这种更新库存动作必须加锁 可以使乐观锁 或 悲观锁
            return service.update(account);

        } catch (Exception e) {
            log.error(e.getMessage(), e);
            //正常来说应该控制 重试次数... demo不控制了....
            return false;
        }
    }

}
