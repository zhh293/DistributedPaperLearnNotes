package com.zhh.handsome.多把锁;

import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

public class 代码对比和演示 {
    // 账户类
    class Account {
        private String accountId;
        private int balance;
        private Lock lock = new ReentrantLock(); // 每个账户自己的锁

        public Account(String accountId, int balance) {
            this.accountId = accountId;
            this.balance = balance;
        }

        public String getAccountId() {
            return accountId;
        }

        public int getBalance() {
            return balance;
        }

        public void setBalance(int balance) {
            this.balance = balance;
        }

        public Lock getLock() {
            return lock;
        }
    }

    // 单锁版本：用一把锁保护所有账户
    class SingleLockBank {
        private Map<String, Account> accounts = new HashMap<>();
        private Lock lock = new ReentrantLock(); // 全局唯一的锁

        public void addAccount(Account account) {
            accounts.put(account.getAccountId(), account);
        }

        public void transfer(String fromId, String toId, int amount) {
            // 所有转账都需要先获取这把大锁
            lock.lock();
            try {
                Account fromAccount = accounts.get(fromId);
                Account toAccount = accounts.get(toId);

                if (fromAccount == null || toAccount == null) {
                    throw new IllegalArgumentException("账户不存在");
                }

                if (fromAccount.getBalance() >= amount) {
                    fromAccount.setBalance(fromAccount.getBalance() - amount);
                    toAccount.setBalance(toAccount.getBalance() + amount);
                }
            } finally {
                lock.unlock(); // 确保锁一定会释放
            }
        }
    }

    // 多锁版本：每个账户有自己的锁
    class MultiLockBank {
        private Map<String, Account> accounts = new HashMap<>();

        public void addAccount(Account account) {
            accounts.put(account.getAccountId(), account);
        }

        public void transfer(String fromId, String toId, int amount) {
            Account fromAccount = accounts.get(fromId);
            Account toAccount = accounts.get(toId);

            if (fromAccount == null || toAccount == null) {
                throw new IllegalArgumentException("账户不存在");
            }

            // 为避免死锁，先获取ID较小的账户的锁
            Account firstLockAccount = fromId.compareTo(toId) < 0 ? fromAccount : toAccount;
            Account secondLockAccount = fromId.compareTo(toId) < 0 ? toAccount : fromAccount;

            // 分别获取两个账户的锁
            firstLockAccount.getLock().lock();
            try {
                secondLockAccount.getLock().lock();
                try {
                    if (fromAccount.getBalance() >= amount) {
                        fromAccount.setBalance(fromAccount.getBalance() - amount);
                        toAccount.setBalance(toAccount.getBalance() + amount);
                    }
                } finally {
                    secondLockAccount.getLock().unlock();
                }
            } finally {
                firstLockAccount.getLock().unlock();
            }
        }
    }

    public class BankTransferExample {
        public void main(String[] args) throws InterruptedException {
            int accountCount = 10; // 10个账户
            int threadCount = 100; // 100个线程
            int transferPerThread = 10; // 每个线程执行10次转账

            // 创建账户
            List<Account> accounts = new ArrayList<>();
            for (int i = 0; i < accountCount; i++) {
                accounts.add(new Account("acc" + i, 10000));
            }

            // 测试单锁版本
            SingleLockBank singleLockBank = new SingleLockBank();
            accounts.forEach(singleLockBank::addAccount);
            long singleLockTime = testBankPerformance(singleLockBank, threadCount, transferPerThread, accountCount);

            // 测试多锁版本
            MultiLockBank multiLockBank = new MultiLockBank();
            accounts.forEach(acc -> {
                acc.setBalance(10000); // 重置余额
                multiLockBank.addAccount(acc);
            });
            long multiLockTime = testBankPerformance(multiLockBank, threadCount, transferPerThread, accountCount);

            // 输出结果
            System.out.printf("单锁版本耗时: %d 毫秒%n", singleLockTime);
            System.out.printf("多锁版本耗时: %d 毫秒%n", multiLockTime);
            System.out.printf("多锁版本比单锁版本快约 %.2f 倍%n", (double) singleLockTime / multiLockTime);
        }

        private static long testBankPerformance(Object bank, int threadCount, int transfersPerThread, int accountCount)
                throws InterruptedException {
            ExecutorService executor = Executors.newFixedThreadPool(threadCount);
            long startTime = System.currentTimeMillis();

            for (int i = 0; i < threadCount; i++) {
                executor.submit(() -> {
                    Random random = new Random();
                    for (int j = 0; j < transfersPerThread; j++) {
                        String fromId = "acc" + random.nextInt(accountCount);
                        String toId;
                        do {
                            toId = "acc" + random.nextInt(accountCount);
                        } while (fromId.equals(toId)); // 确保不是同一个账户

                        int amount = random.nextInt(100) + 1; // 1-100之间的随机金额

                        try {
                            if (bank instanceof SingleLockBank) {
                                ((SingleLockBank) bank).transfer(fromId, toId, amount);
                            } else {
                                ((MultiLockBank) bank).transfer(fromId, toId, amount);
                            }
                        } catch (Exception e) {
                            // 忽略异常
                        }
                    }
                });
            }

            executor.shutdown();
            executor.awaitTermination(1, TimeUnit.MINUTES);
            return System.currentTimeMillis() - startTime;
        }
    }
}
