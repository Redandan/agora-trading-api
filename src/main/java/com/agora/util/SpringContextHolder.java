package com.agora.util;

import org.springframework.beans.BeansException;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Component;

/**
 * Spring ApplicationContext 持有者
 * 用于在静态方法中获取 Spring Bean
 */
@Component
public class SpringContextHolder implements ApplicationContextAware {
    
    private static ApplicationContext applicationContext;
    
    @Override
    public void setApplicationContext(@NonNull ApplicationContext applicationContext) throws BeansException {
        SpringContextHolder.applicationContext = applicationContext;
    }
    
    /**
     * 获取 ApplicationContext
     * 
     * @return ApplicationContext，如果未初始化则返回 null
     */
    public static ApplicationContext getApplicationContext() {
        return applicationContext;
    }
    
    /**
     * 根据类型获取 Bean
     * 
     * @param clazz Bean 类型
     * @param <T> Bean 类型
     * @return Bean 实例，如果不存在或 ApplicationContext 未初始化则返回 null
     */
    public static <T> T getBean(Class<T> clazz) {
        if (applicationContext == null) {
            return null;
        }
        try {
            return applicationContext.getBean(clazz);
        } catch (BeansException e) {
            return null;
        }
    }
    
    /**
     * 根据名称获取 Bean
     * 
     * @param name Bean 名称
     * @return Bean 实例，如果不存在或 ApplicationContext 未初始化则返回 null
     */
    public static Object getBean(String name) {
        if (applicationContext == null) {
            return null;
        }
        try {
            return applicationContext.getBean(name);
        } catch (BeansException e) {
            return null;
        }
    }
    
    /**
     * 检查 Spring 上下文是否可用
     * 
     * @return 如果 ApplicationContext 已初始化返回 true，否则返回 false
     */
    public static boolean isSpringContextAvailable() {
        return applicationContext != null;
    }
}

