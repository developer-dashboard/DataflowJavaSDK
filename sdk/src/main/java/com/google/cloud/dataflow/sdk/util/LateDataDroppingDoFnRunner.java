/*
 * Copyright (C) 2016 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */
package com.google.cloud.dataflow.sdk.util;

import com.google.cloud.dataflow.sdk.transforms.Aggregator;
import com.google.cloud.dataflow.sdk.transforms.DoFn;
import com.google.cloud.dataflow.sdk.transforms.windowing.BoundedWindow;
import com.google.cloud.dataflow.sdk.values.KV;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Function;
import com.google.common.base.Predicate;
import com.google.common.collect.Iterables;

import org.joda.time.Instant;

/**
 * A customized {@link DoFnRunner} that handles late data dropping for
 * a {@link KeyedWorkItem} input {@link DoFn}.
 *
 * <p>It expands windows before checking data lateness.
 *
 * <p>{@link KeyedWorkItem KeyedWorkItems} are always in empty windows.
 *
 * @param <K> key type
 * @param <InputT> input value element type
 * @param <OutputT> output value element type
 * @param <W> window type
 */
public class LateDataDroppingDoFnRunner<K, InputT, OutputT, W extends BoundedWindow>
    implements DoFnRunner<KeyedWorkItem<K, InputT>, KV<K, OutputT>> {
  private final DoFnRunner<KeyedWorkItem<K, InputT>, KV<K, OutputT>> doFnRunner;
  private final LateDataFilter lateDataFilter;

  public LateDataDroppingDoFnRunner(
      DoFnRunner<KeyedWorkItem<K, InputT>, KV<K, OutputT>> doFnRunner,
      WindowingStrategy<?, ?> windowingStrategy,
      TimerInternals timerInternals,
      Aggregator<Long, Long> droppedDueToLateness) {
    this.doFnRunner = doFnRunner;
    lateDataFilter = new LateDataFilter(windowingStrategy, timerInternals, droppedDueToLateness);
  }

  @Override
  public void startBundle() {
    doFnRunner.startBundle();
  }

  @Override
  public void processElement(WindowedValue<KeyedWorkItem<K, InputT>> elem) {
    Iterable<WindowedValue<InputT>> nonLateElements = lateDataFilter.filter(
        elem.getValue().key(), elem.getValue().elementsIterable());
    KeyedWorkItem<K, InputT> keyedWorkItem = KeyedWorkItems.workItem(
        elem.getValue().key(), elem.getValue().timersIterable(), nonLateElements);
    doFnRunner.processElement(elem.withValue(keyedWorkItem));
  }

  @Override
  public void finishBundle() {
    doFnRunner.finishBundle();
  }

  /**
   * It filters late data in a {@link KeyedWorkItem}.
   */
  @VisibleForTesting
  static class LateDataFilter {
    private final WindowingStrategy<?, ?> windowingStrategy;
    private final TimerInternals timerInternals;
    private final Aggregator<Long, Long> droppedDueToLateness;

    public LateDataFilter(
        WindowingStrategy<?, ?> windowingStrategy,
        TimerInternals timerInternals,
        Aggregator<Long, Long> droppedDueToLateness) {
      this.windowingStrategy = windowingStrategy;
      this.timerInternals = timerInternals;
      this.droppedDueToLateness = droppedDueToLateness;
    }

    /**
     * Returns an {@code Iterable<WindowedValue<InputT>>} that only contains
     * non-late input elements.
     */
    public <K, InputT> Iterable<WindowedValue<InputT>> filter(
        final K key, Iterable<WindowedValue<InputT>> elements) {
      Iterable<Iterable<WindowedValue<InputT>>> windowsExpandedElements = Iterables.transform(
          elements,
          new Function<WindowedValue<InputT>, Iterable<WindowedValue<InputT>>>() {
            @Override
            public Iterable<WindowedValue<InputT>> apply(final WindowedValue<InputT> input) {
              return Iterables.transform(
                  input.getWindows(),
                  new Function<BoundedWindow, WindowedValue<InputT>>() {
                    @Override
                    public WindowedValue<InputT> apply(BoundedWindow window) {
                      return WindowedValue.of(
                          input.getValue(), input.getTimestamp(), window, input.getPane());
                    }
                  });
            }});

      Iterable<WindowedValue<InputT>> concatElements = Iterables.concat(windowsExpandedElements);

      // Bump the counter separately since we don't want multiple iterations to
      // increase it multiple times.
      for (WindowedValue<InputT> input : concatElements) {
        BoundedWindow window = Iterables.getOnlyElement(input.getWindows());
        if (canDropDueToExpiredWindow(window)) {
          // The element is too late for this window.
          droppedDueToLateness.addValue(1L);
          WindowTracing.debug(
              "ReduceFnRunner.processElement: Dropping element at {} for key:{}; window:{} "
              + "since too far behind inputWatermark:{}; outputWatermark:{}",
              input.getTimestamp(), key, window, timerInternals.currentInputWatermarkTime(),
              timerInternals.currentOutputWatermarkTime());
        }
      }

      Iterable<WindowedValue<InputT>> nonLateElements = Iterables.filter(
          concatElements,
          new Predicate<WindowedValue<InputT>>() {
            @Override
            public boolean apply(WindowedValue<InputT> input) {
              BoundedWindow window = Iterables.getOnlyElement(input.getWindows());
              if (canDropDueToExpiredWindow(window)) {
                return false;
              } else {
                return true;
              }
            }
          });
      return nonLateElements;
    }

    /** Is {@code window} expired w.r.t. the garbage collection watermark? */
    private boolean canDropDueToExpiredWindow(BoundedWindow window) {
      Instant inputWM = timerInternals.currentInputWatermarkTime();
      return window.maxTimestamp().plus(windowingStrategy.getAllowedLateness()).isBefore(inputWM);
    }
  }
}
