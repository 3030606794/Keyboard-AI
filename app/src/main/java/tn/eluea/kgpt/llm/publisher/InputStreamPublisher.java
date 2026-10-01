/*
 * Copyright (c) 2025 Amr Aldeeb @Eluea
 * GitHub: https://github.com/Eluea
 * Telegram: https://t.me/Eluea
 *
 * This file is part of KGPT.
 * Based on original code from KeyboardGPT by Mino260806.
 * Original: https://github.com/Mino260806/KeyboardGPT
 *
 * Licensed under the GPLv3.
 */
package tn.eluea.kgpt.llm.publisher;

import org.reactivestreams.Publisher;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.function.Function;

public class InputStreamPublisher implements Publisher<String> {
    private final InputStream mInputStream;
    private final Function<String, String> mReplace;
    private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor();

    public InputStreamPublisher(InputStream inputStream, Function<String, String> replace) {
        mInputStream = inputStream;
        mReplace = replace;
    }

    @Override
    public void subscribe(Subscriber<? super String> subscriber) {
        Subscription subscription = new Subscription() {
            private volatile boolean cancelled = false;
            private final java.util.concurrent.atomic.AtomicBoolean started = new java.util.concurrent.atomic.AtomicBoolean();

            @Override
            public void request(long n) {
                if (n <= 0) {
                    subscriber.onError(new IllegalArgumentException("Demand must be positive"));
                    return;
                }

                if (cancelled || !started.compareAndSet(false, true)) return;
                executor.submit(() -> {
                    try (BufferedReader reader = new BufferedReader(new InputStreamReader(mInputStream, java.nio.charset.StandardCharsets.UTF_8))) {
                        String line;
                        while (!cancelled && (line = reader.readLine()) != null) {
                            subscriber.onNext(mReplace.apply(line));
                        }
                        if (!cancelled) {
                            subscriber.onComplete();
                        }
                    } catch (Throwable e) {
                        if (!cancelled) {
                            subscriber.onError(e);
                        }
                    } finally {
                        executor.shutdown();
                    }
                });
            }

            @Override
            public void cancel() {
                cancelled = true;
                try {
                    // Forcefully close the underlying stream so readLine() unblocks immediately.
                    mInputStream.close();
                } catch (Throwable ignored) {
                }
                try {
                    executor.shutdownNow();
                } catch (Throwable ignored) {
                    try { executor.shutdown(); } catch (Throwable ignored2) {}
                }
            }

        };

        subscriber.onSubscribe(subscription);
    }
}
