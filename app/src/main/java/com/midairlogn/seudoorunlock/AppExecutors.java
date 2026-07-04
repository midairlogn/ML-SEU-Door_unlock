package com.midairlogn.seudoorunlock;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class AppExecutors {

    private static volatile ExecutorService instance;

    private AppExecutors() {}

    public static ExecutorService getInstance() {
        if (instance == null) {
            synchronized (AppExecutors.class) {
                if (instance == null) {
                    instance = Executors.newFixedThreadPool(2);
                }
            }
        }
        return instance;
    }
}
