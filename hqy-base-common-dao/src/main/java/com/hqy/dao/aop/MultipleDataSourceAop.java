package com.hqy.dao.aop;

import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * 多数据源自动切换通知类<br>
 * @author qy
 * @project: hqy-parent-all
 * @create 2021-08-09 18:25
 */
@Component
@Slf4j
@Aspect
@Order(0) // execute before @Transactional
public class MultipleDataSourceAop {
}
