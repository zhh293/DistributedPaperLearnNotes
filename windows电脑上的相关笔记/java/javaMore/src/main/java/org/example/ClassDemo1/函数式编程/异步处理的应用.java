package org.example.ClassDemo1.函数式编程;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.logging.Logger;

public class 异步处理的应用 {
    public static void main(String[] args) throws InterruptedException {
       /* try(ExecutorService executorService = Executors.newFixedThreadPool(3)){
            System.out.println(1);
            executorService.submit(()->{
                System.out.println(2);
            });
            System.out.println(4);
        }catch (Exception e){
            e.printStackTrace();
        }*/

        CompletableFuture.runAsync(()->{ System.out.println(3);});
        Thread.sleep(100);
       // CompletableFuture.supplyAsync();

       /* service.submit(() -> {
            monthlySalesReport((map)->{
                /*for (Map.Entry<YearMonth, Long> e : map.entrySet()) {
                    logger.info(e.toString());
                }
              String string = map.entrySet().stream()
        .map(e -> e.toString()).collect(Collectors.joining("\n"));
try {
    Files.writeString(Path.of("./result.txt"), string);
} catch (IOException e) {
    throw new RuntimeException(e);
}
            });
        });
        logger.info("执行其它操作");
    }
}

private static void monthlySalesReport(Consumer<Map<YearMonth, Long>> consumer) {
    try (Stream<String> lines = Files.lines(Path.of("./data.txt"))) {
        Map<YearMonth, Long> map = lines.skip(1)
                .map(line -> line.split(","))
                .collect(groupingBy(array -> YearMonth.from(formatter.parse(array[TIME])), TreeMap::new, counting()));
        consumer.accept(map);
    } catch (IOException e) {
        throw new RuntimeException(e);
    }
}*/

    }
}




