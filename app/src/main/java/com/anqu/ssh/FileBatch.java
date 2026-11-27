package com.anqu.ssh;

import java.io.IOException;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Function;

final class FileBatch {
    private FileBatch() {}

    interface Operation<T> { void run(T item) throws Exception; }

    record Report(int complete, int failed, String firstIssue) {
        String summary() {
            return "完成 " + complete + " 项" + (failed == 0 ? "" : " · 失败 " + failed + " 项")
                + (firstIssue.isEmpty() ? "" : "\n" + firstIssue);
        }
    }

    static final class Interrupted extends IOException {
        Interrupted(String message, Exception cause) { super(message, cause); }
    }

    static <T> Report run(RemoteFiles files, List<T> items, BooleanSupplier stopped,
                          Consumer<String> progress, Function<Exception, String> describe,
                          Operation<T> operation) throws Exception {
        int complete = 0;
        int failed = 0;
        String first = "";
        for (int i = 0; i < items.size(); i++) {
            if (stopped.getAsBoolean()) throw new IOException("操作已取消");
            progress.accept((i + 1) + " / " + items.size());
            try {
                operation.run(items.get(i));
                complete++;
            } catch (Exception error) {
                if (files.lost(error)) {
                    throw new Interrupted("已完成 " + complete + " 项"
                        + (failed == 0 ? "" : "，失败 " + failed + " 项")
                        + "。连接断开，当前项结果未知，剩余 " + (items.size() - i - 1) + " 项未处理。", error);
                }
                if (first.isEmpty()) first = describe.apply(error);
                failed++;
            }
        }
        return new Report(complete, failed, first);
    }
}
