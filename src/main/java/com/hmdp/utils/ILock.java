package com.hmdp.utils;

public interface ILock {
    /*
    *
    * @param timeoutSec 锁持有的超时时间，超时自动释放
    * 获取锁
    *
    * */
    boolean tryLock(long timeoutSec);


    /*
    *  释放锁
    * */
    void unlock();
}
