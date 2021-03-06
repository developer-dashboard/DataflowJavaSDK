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
package com.google.cloud.dataflow.sdk.runners.inprocess;

import com.google.cloud.dataflow.sdk.runners.inprocess.InProcessPipelineRunner.CommittedBundle;
import com.google.cloud.dataflow.sdk.transforms.AppliedPTransform;

/**
 * A callback for completing a bundle of input.
 */
interface CompletionCallback {
  /**
   * Handle a successful result, returning the committed outputs of the result.
   */
  CommittedResult handleResult(
      CommittedBundle<?> inputBundle, InProcessTransformResult result);

  /**
   * Handle an input bundle that did not require processing.
   *
   * <p>This occurs when a Source has no splits that can currently produce outputs.
   */
  void handleEmpty(AppliedPTransform<?, ?, ?> transform);

  /**
   * Handle a result that terminated abnormally due to the provided {@link Throwable}.
   */
  void handleThrowable(CommittedBundle<?> inputBundle, Throwable t);
}
