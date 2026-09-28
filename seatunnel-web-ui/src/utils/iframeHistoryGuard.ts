/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import { rewriteInAppLocation } from './iframeLayout';

type HistoryLike = {
  push(to: unknown, state?: unknown): void;
  replace(to: unknown, state?: unknown): void;
};

let installed = false;

/**
 * Patch Umi history after createHistory(). Must not run during app.tsx module
 * init — importing history from `@umijs/max` there trips a circular dependency
 * and leaves the splash screen up forever.
 */
export const installIframeHistoryGuard = (history: HistoryLike | undefined): void => {
  if (installed || !history?.push || !history?.replace) {
    return;
  }

  installed = true;

  const rawPush = history.push.bind(history);
  const rawReplace = history.replace.bind(history);

  history.push = (to: unknown, state?: unknown) => {
    const next = rewriteInAppLocation(to as string | { pathname?: string });
    return state === undefined ? rawPush(next) : rawPush(next, state);
  };

  history.replace = (to: unknown, state?: unknown) => {
    const next = rewriteInAppLocation(to as string | { pathname?: string });
    return state === undefined ? rawReplace(next) : rawReplace(next, state);
  };
};
